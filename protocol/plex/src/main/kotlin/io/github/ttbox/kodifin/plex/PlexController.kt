package io.github.ttbox.kodifin.plex

import io.github.ttbox.kodifin.model.Capability
import io.github.ttbox.kodifin.model.Device
import io.github.ttbox.kodifin.model.DeviceKind
import io.github.ttbox.kodifin.model.RemoteController
import io.github.ttbox.kodifin.model.RemoteError
import io.github.ttbox.kodifin.model.RemoteException
import io.github.ttbox.kodifin.model.RemoteKey
import io.github.ttbox.kodifin.model.fetchString
import io.github.ttbox.kodifin.model.requireKey
import io.github.ttbox.kodifin.model.UdpDestination
import io.github.ttbox.kodifin.model.UdpOptions
import io.github.ttbox.kodifin.model.UdpSocketFactory
import io.github.ttbox.kodifin.model.discover
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.Inet6Address
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.TimeSource

/**
 * Contrôle direct d'un lecteur Plex (Plex HTPC, Plex pour TV…) via l'API
 * « Remote Control » exposée sur le port 32500 du lecteur.
 *
 * L'extra [EXTRA_SCHEME] = `https` fait passer les appels en HTTPS ; la vérification du
 * certificat (éventuellement auto-signé) relève du client [http] fourni par l'appelant.
 */
class PlexController(
    override val device: Device,
    private val http: OkHttpClient,
    private val identity: PlexIdentity,
    /**
     * Résolution des noms courts (sans domaine) pour décider de l'envoi du jeton ; par défaut
     * le résolveur du client [http], celui-là même qui servira à la connexion.
     */
    private val dns: Dns = http.dns,
) : RemoteController {

    override val capabilities = setOf(Capability.NAVIGATION, Capability.PLAYBACK, Capability.VOLUME, Capability.SEEK)
    override val supportedKeys = COMMANDS.keys + setOf(RemoteKey.VOLUME_UP, RemoteKey.VOLUME_DOWN)

    private val base: HttpUrl? = baseUrl(device).toHttpUrlOrNull()
    private val commandId = AtomicInteger(0)

    /** Volume connu (0–100) ; -1 tant qu'il n'a pas été lu sur le lecteur ni fixé. */
    private val volume = AtomicInteger(-1)

    /** Identifiant du lecteur ciblé ; résolu par [connect] s'il n'est pas dans les extras. */
    @Volatile
    private var targetClientId: String? = device.extras[EXTRA_CLIENT_ID]
        // Identifiant `PLEX:<id>` issu de GDM ; un identifiant manuel `PLEX:hôte:port` n'en est pas un.
        ?: device.id.takeIf { it.startsWith("PLEX:") }?.removePrefix("PLEX:")?.takeIf { it.isNotBlank() && ':' !in it }

    /** Jeton du compte plex.tv, transmis uniquement à un lecteur local (voir [request]). */
    private val token: String? = device.extras[EXTRA_TOKEN]?.ifBlank { null }

    /**
     * Le jeton n'est joint que si l'hôte EFFECTIF de l'URL appelée est local, en HTTP comme en
     * HTTPS : un certificat auto-signé accepté par l'appelant n'authentifie pas l'hôte, et
     * l'envoyer à un hôte public l'exposerait. Un nom court n'est jugé local qu'après résolution
     * (bloquante, donc hors du fil principal) vers des adresses toutes privées.
     */
    private suspend fun request(url: HttpUrl): Request.Builder {
        val builder = identity.apply(Request.Builder().url(url))
        targetClientId?.let { builder.header("X-Plex-Target-Client-Identifier", it) }
        if (token != null) {
            val local = withContext(Dispatchers.IO) { isLocalHost(url.host, dns::lookup) }
            if (local) builder.header("X-Plex-Token", token)
        }
        return builder.get()
    }

    private fun url(path: String, params: Map<String, String>): HttpUrl {
        val root = base ?: throw RemoteException(RemoteError.InvalidAddress("${device.host}:${device.port}"))
        return root.newBuilder().encodedPath(path).apply {
            params.forEach { (k, v) -> addQueryParameter(k, v) }
            addQueryParameter("commandID", commandId.incrementAndGet().toString())
        }.build()
    }

    internal suspend fun command(path: String, params: Map<String, String> = emptyMap()) {
        http.fetchString(request(url(path, params)).build())
    }

    /** Résout l'identifiant du lecteur via `/resources` s'il manque (appareil saisi à la main). */
    override suspend fun connect() {
        if (targetClientId != null) return
        val xml = http.fetchString(request(url("/resources", emptyMap())).header("Accept", "application/xml").build())
        targetClientId = xmlElements(xml, "Player").firstNotNullOfOrNull { it["machineIdentifier"]?.ifBlank { null } }
            ?: throw RemoteException(RemoteError.UnexpectedResponse("Plex"))
    }

    override suspend fun send(key: RemoteKey) {
        requireKey(key)
        when (key) {
            RemoteKey.VOLUME_UP -> setVolume(currentVolume() + VOLUME_STEP)
            RemoteKey.VOLUME_DOWN -> setVolume(currentVolume() - VOLUME_STEP)
            else -> {
                val path = COMMANDS.getValue(key)
                // Les commandes /player/playback/* exigent le type de média visé.
                val params = if (path.startsWith("/player/playback/")) mapOf("type" to MEDIA_TYPE) else emptyMap()
                command(path, params)
            }
        }
    }

    /** Volume courant : lu sur la frise du lecteur la première fois, 50 à défaut. */
    private suspend fun currentVolume(): Int {
        volume.get().takeIf { it >= 0 }?.let { return it }
        val read = runCatching {
            val xml = http.fetchString(
                request(url("/player/timeline/poll", mapOf("wait" to "0"))).header("Accept", "application/xml").build(),
            )
            parseTimelineVolume(xml)
        }.getOrNull() ?: DEFAULT_VOLUME
        volume.compareAndSet(-1, read)
        return volume.get()
    }

    override suspend fun setVolume(percent: Int) {
        val target = percent.coerceIn(0, 100)
        volume.set(target)
        command("/player/playback/setParameters", mapOf("volume" to target.toString(), "type" to MEDIA_TYPE))
    }

    override suspend fun seekTo(positionMs: Long) {
        command("/player/playback/seekTo", mapOf("offset" to positionMs.coerceAtLeast(0).toString(), "type" to MEDIA_TYPE))
    }

    companion object {
        const val EXTRA_TOKEN = "token"
        const val EXTRA_CLIENT_ID = "clientIdentifier"

        /** Schéma des appels au lecteur : `https` ou, par défaut, `http`. */
        const val EXTRA_SCHEME = "scheme"
        const val GDM_ADDRESS = "239.0.0.250"
        const val GDM_PLAYER_PORT = 32412
        private const val MEDIA_TYPE = "video"
        private const val VOLUME_STEP = 10
        private const val DEFAULT_VOLUME = 50

        val COMMANDS: Map<RemoteKey, String> = mapOf(
            RemoteKey.UP to "/player/navigation/moveUp",
            RemoteKey.DOWN to "/player/navigation/moveDown",
            RemoteKey.LEFT to "/player/navigation/moveLeft",
            RemoteKey.RIGHT to "/player/navigation/moveRight",
            RemoteKey.SELECT to "/player/navigation/select",
            RemoteKey.BACK to "/player/navigation/back",
            RemoteKey.HOME to "/player/navigation/home",
            RemoteKey.MENU to "/player/navigation/contextMenu",
            RemoteKey.INFO to "/player/navigation/toggleOSD",
            RemoteKey.PLAY_PAUSE to "/player/playback/playPause",
            RemoteKey.STOP to "/player/playback/stop",
            RemoteKey.NEXT to "/player/playback/skipNext",
            RemoteKey.PREVIOUS to "/player/playback/skipPrevious",
            RemoteKey.REWIND to "/player/playback/stepBack",
            RemoteKey.FAST_FORWARD to "/player/playback/stepForward",
        )

        /** URL racine du lecteur : `https://hôte:port` si l'extra [EXTRA_SCHEME] vaut `https`. */
        internal fun baseUrl(device: Device): String =
            if (device.extras[EXTRA_SCHEME].equals("https", ignoreCase = true)) {
                "https://" + device.baseUrl.removePrefix("http://")
            } else {
                device.baseUrl
            }

        private val ELEMENT = Regex("<(\\w+)\\s([^>]*)>")
        private val ATTRIBUTE = Regex("([\\w:-]+)\\s*=\\s*\"([^\"]*)\"")

        /** Attributs des éléments XML [name] (lecture minimale, sans entités ni imbrication). */
        internal fun xmlElements(xml: String, name: String): List<Map<String, String>> =
            ELEMENT.findAll(xml).filter { it.groupValues[1] == name }.map { element ->
                ATTRIBUTE.findAll(element.groupValues[2]).associate { it.groupValues[1] to it.groupValues[2] }
            }.toList()

        /** Volume de la frise active (sinon de la première qui en annonce un) ; `null` si absent. */
        internal fun parseTimelineVolume(xml: String): Int? {
            val timelines = xmlElements(xml, "Timeline")
            val active = timelines.filter { it["state"] != null && it["state"] != "stopped" }
            return (active + timelines).firstNotNullOfOrNull { it["volume"]?.toIntOrNull() }?.coerceIn(0, 100)
        }

        private val DOTTED_QUAD = Regex("""(0|[1-9]\d{0,2})(\.(0|[1-9]\d{0,2})){3}""")
        private val HEX = Regex("0x[0-9a-f]*")
        private val LABEL = Regex("[a-z0-9]([a-z0-9-]*[a-z0-9])?")

        /**
         * Hôte du réseau local :
         * - adresse littérale privée (RFC 1918), de lien local, de boucle ou IPv6 ULA (`fc00::/7`) ;
         * - `localhost`, ou nom en `.local` (mDNS), `.lan` ou `.home.arpa` (RFC 8375) ;
         * - nom court (sans domaine) UNIQUEMENT s'il résout, via [resolve], vers des adresses
         *   toutes locales : un suffixe de recherche DNS (`htpc` → `htpc.exemple.com`) peut sinon
         *   désigner un hôte public. Échec ou absence de résolution : non local.
         *
         * Les formes numériques ambiguës (`134744072`, `0x08080808`, `8.8.2056`, zéros de tête
         * interprétés en octal) sont refusées : un résolveur les traduirait en adresse publique.
         * Le point final (`nom.local.`) et l'adresse joker `0.0.0.0` sont refusés.
         * [resolve] peut bloquer (DNS) : appeler hors du fil principal.
         */
        internal fun isLocalHost(host: String, resolve: (String) -> List<InetAddress> = { emptyList() }): Boolean {
            val h = host.removePrefix("[").removeSuffix("]").lowercase()
            if (h.isEmpty()) return false
            if (':' in h) return isLocalAddress(h)
            if (DOTTED_QUAD.matches(h)) return isLocalAddress(h)
            val labels = h.split('.')
            // Nombre seul, hexadécimal ou quadruplet non canonique : potentiellement une IPv4 déguisée.
            if (labels.all { it.isNotEmpty() && (it.all(Char::isDigit) || HEX.matches(it)) }) return false
            if (labels.any { !LABEL.matches(it) }) return false // vide (point final) ou caractère invalide
            if (labels.last().all(Char::isDigit)) return false
            if (h == "localhost" || h.endsWith(".local") || h.endsWith(".lan") || h.endsWith(".home.arpa")) return true
            if (labels.size != 1) return false
            val addresses = try {
                resolve(h)
            } catch (_: Exception) {
                return false
            }
            return addresses.isNotEmpty() && addresses.all(::isLocalAddress)
        }

        /** Adresse littérale (IPv4 canonique ou IPv6) du réseau local ; IPv4 mappée évaluée en IPv4. */
        private fun isLocalAddress(literal: String): Boolean {
            val address = runCatching { InetAddress.getByName(literal) }.getOrNull() ?: return false
            return isLocalAddress(address)
        }

        private fun isLocalAddress(address: InetAddress): Boolean {
            val bytes = address.address
            // ::ffff:a.b.c.d : certaines JVM la renvoient en Inet6Address, on évalue l'IPv4 incluse.
            if (address is Inet6Address && bytes.size == 16 &&
                (0 until 10).all { bytes[it].toInt() == 0 } && bytes[10].toInt() == -1 && bytes[11].toInt() == -1
            ) {
                return isLocalAddress(bytes.copyOfRange(12, 16).joinToString(".") { (it.toInt() and 0xff).toString() })
            }
            val uniqueLocalV6 = address is Inet6Address && (bytes[0].toInt() and 0xfe) == 0xfc
            return address.isSiteLocalAddress || address.isLinkLocalAddress || address.isLoopbackAddress || uniqueLocalV6
        }

        /** Analyse une réponse GDM (`HTTP/1.0 200 OK` + en-têtes `Clé: valeur`). */
        fun parseGdm(payload: String, address: String): Device? {
            val lines = payload.split("\r\n", "\n")
            if (lines.firstOrNull()?.contains("200 OK") != true) return null
            val headers = lines.drop(1).mapNotNull {
                val i = it.indexOf(':')
                if (i <= 0) null else it.substring(0, i).trim().lowercase() to it.substring(i + 1).trim()
            }.toMap()
            if (headers["content-type"]?.contains("player") != true) return null
            val id = headers["resource-identifier"]?.ifBlank { null } ?: return null
            return Device(
                id = "PLEX:$id",
                name = headers["name"]?.ifBlank { null } ?: "Plex",
                kind = DeviceKind.PLEX,
                host = address,
                port = headers["port"]?.toIntOrNull()?.takeIf { it in 1..65535 } ?: DeviceKind.PLEX.defaultPort,
                extras = mapOf(EXTRA_CLIENT_ID to id),
            )
        }

        /**
         * Découverte GDM des lecteurs Plex sur le réseau local (multicast et diffusion ;
         * un échec d'envoi vers l'une des destinations n'empêche pas l'autre).
         */
        suspend fun discover(
            timeoutMs: Int = 2_000,
            sockets: UdpSocketFactory = UdpSocketFactory.Default,
            timeSource: TimeSource = TimeSource.Monotonic,
        ): List<Device> = sockets.discover(
            options = UdpOptions(broadcast = true),
            destinations = listOf(UdpDestination(GDM_ADDRESS, GDM_PLAYER_PORT), UdpDestination("255.255.255.255", GDM_PLAYER_PORT)),
            payload = "M-SEARCH * HTTP/1.0\r\n\r\n".toByteArray(Charsets.US_ASCII),
            timeoutMs = timeoutMs.toLong(),
            timeSource = timeSource,
        ) { datagram ->
            val source = datagram.sourceAddress ?: return@discover null
            parseGdm(datagram.text(), source)?.let { it.id to it }
        }
    }
}
