package io.github.ttbox.kodifin.plex

import io.github.ttbox.kodifin.model.Capability
import io.github.ttbox.kodifin.model.Device
import io.github.ttbox.kodifin.model.DeviceKind
import io.github.ttbox.kodifin.model.RemoteController
import io.github.ttbox.kodifin.model.RemoteError
import io.github.ttbox.kodifin.model.RemoteException
import io.github.ttbox.kodifin.model.RemoteKey
import io.github.ttbox.kodifin.model.LocalNetwork
import io.github.ttbox.kodifin.model.PinnedDns
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
     * Résolution des noms d'hôte (hors suffixes locaux) pour décider de l'envoi du jeton ; par défaut
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
     * Appel GET de [url]. Le jeton n'est joint que si l'hôte EFFECTIF de l'URL appelée est local,
     * en HTTP comme en HTTPS : un certificat auto-signé accepté par l'appelant n'authentifie pas
     * l'hôte, et l'envoyer à un hôte public l'exposerait. Tout nom DNS (suffixe `.local` compris)
     * n'est jugé local qu'après résolution (bloquante, donc hors du fil principal) vers des
     * adresses toutes privées, et la connexion est alors ÉPINGLÉE sur ces adresses ([PinnedDns]) :
     * OkHttp ne résout pas le nom une seconde fois (pas de DNS rebinding vers un hôte public).
     */
    private suspend fun get(url: HttpUrl, accept: String? = null): String {
        val builder = identity.apply(Request.Builder().url(url))
        targetClientId?.let { builder.header("X-Plex-Target-Client-Identifier", it) }
        accept?.let { builder.header("Accept", it) }
        var client = http
        if (token != null) {
            val addresses = withContext(Dispatchers.IO) { tokenAddresses(url.host, dns::lookup) }
            if (addresses != null) {
                builder.header("X-Plex-Token", token)
                client = http.newBuilder().dns(PinnedDns(url.host, addresses)).build()
            }
        }
        return client.fetchString(builder.get().build())
    }

    private fun url(path: String, params: Map<String, String>): HttpUrl {
        val root = base ?: throw RemoteException(RemoteError.InvalidAddress("${device.host}:${device.port}"))
        return root.newBuilder().encodedPath(path).apply {
            params.forEach { (k, v) -> addQueryParameter(k, v) }
            addQueryParameter("commandID", commandId.incrementAndGet().toString())
        }.build()
    }

    internal suspend fun command(path: String, params: Map<String, String> = emptyMap()) {
        get(url(path, params))
    }

    /** Résout l'identifiant du lecteur via `/resources` s'il manque (appareil saisi à la main). */
    override suspend fun connect() {
        if (targetClientId != null) return
        val xml = get(url("/resources", emptyMap()), accept = "application/xml")
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
            val xml = get(url("/player/timeline/poll", mapOf("wait" to "0")), accept = "application/xml")
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

        /**
         * Adresses vérifiées auxquelles le jeton peut être envoyé pour [host] : toutes locales
         * (boucle comprise), nom résolu via [resolve] sans croire aucun suffixe sur parole ;
         * `null` : jeton refusé. Voir [LocalNetwork.lanAddresses].
         */
        internal fun tokenAddresses(host: String, resolve: (String) -> List<InetAddress>): List<InetAddress>? =
            LocalNetwork.lanAddresses(host, resolve, allowLoopback = true)

        /** Hôte du réseau local, boucle locale comprise : voir [LocalNetwork.isLocalHost]. */
        internal fun isLocalHost(host: String, resolve: (String) -> List<InetAddress> = { emptyList() }): Boolean =
            LocalNetwork.isLocalHost(host, resolve)

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
