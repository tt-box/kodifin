package io.github.jmorille.zapette.plex

import io.github.jmorille.zapette.model.Capability
import io.github.jmorille.zapette.model.Device
import io.github.jmorille.zapette.model.DeviceKind
import io.github.jmorille.zapette.model.RemoteController
import io.github.jmorille.zapette.model.RemoteException
import io.github.jmorille.zapette.model.RemoteKey
import io.github.jmorille.zapette.model.fetchString
import io.github.jmorille.zapette.model.requireKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet6Address
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicInteger

/**
 * Contrôle direct d'un lecteur Plex (Plex HTPC, Plex pour TV…) via l'API
 * « Remote Control » exposée sur le port 32500 du lecteur.
 */
class PlexController(
    override val device: Device,
    private val http: OkHttpClient,
    private val identity: PlexIdentity,
) : RemoteController {

    override val capabilities = setOf(Capability.NAVIGATION, Capability.PLAYBACK, Capability.VOLUME, Capability.SEEK)
    override val supportedKeys = COMMANDS.keys + setOf(RemoteKey.VOLUME_UP, RemoteKey.VOLUME_DOWN)

    private val base: HttpUrl? = device.baseUrl.toHttpUrlOrNull()
    private val commandId = AtomicInteger(0)

    /** Volume connu (0–100) ; -1 tant qu'il n'a pas été lu sur le lecteur ni fixé. */
    private val volume = AtomicInteger(-1)

    /** Identifiant du lecteur ciblé ; résolu par [connect] s'il n'est pas dans les extras. */
    @Volatile
    private var targetClientId: String? = device.extras[EXTRA_CLIENT_ID]
        // Identifiant `PLEX:<id>` issu de GDM ; un identifiant manuel `PLEX:hôte:port` n'en est pas un.
        ?: device.id.takeIf { it.startsWith("PLEX:") }?.removePrefix("PLEX:")?.takeIf { it.isNotBlank() && ':' !in it }

    /**
     * Le jeton du compte n'est transmis qu'à un lecteur du réseau local : l'API du lecteur
     * est en HTTP clair, l'envoyer à un hôte public l'exposerait.
     */
    internal val token: String? = device.extras[EXTRA_TOKEN]?.takeIf { isLocalHost(device.host) }

    private fun request(url: HttpUrl): Request.Builder {
        val builder = identity.apply(Request.Builder().url(url))
        targetClientId?.let { builder.header("X-Plex-Target-Client-Identifier", it) }
        token?.let { builder.header("X-Plex-Token", it) }
        return builder.get()
    }

    private fun url(path: String, params: Map<String, String>): HttpUrl {
        val root = base ?: throw RemoteException("Adresse du lecteur Plex invalide")
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
            ?: throw RemoteException("Lecteur Plex non reconnu sur ${device.host}")
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
        const val GDM_ADDRESS = "239.0.0.250"
        const val GDM_PLAYER_PORT = 32412
        private const val MEDIA_TYPE = "video"
        private const val VOLUME_STEP = 10
        private const val DEFAULT_VOLUME = 50
        private const val RECEIVE_SLICE_MS = 250

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
         * Hôte du réseau local : adresse privée, de lien local, de boucle ou IPv6 ULA,
         * ou nom sans domaine / en `.local`, `.lan`, `.home.arpa`. Aucune résolution DNS.
         */
        internal fun isLocalHost(host: String): Boolean {
            val h = host.removePrefix("[").removeSuffix("]").lowercase()
            val isLiteral = h.contains(':') || h.matches(Regex("\\d{1,3}(\\.\\d{1,3}){3}"))
            if (!isLiteral) {
                return h == "localhost" || !h.contains('.') ||
                    h.endsWith(".local") || h.endsWith(".lan") || h.endsWith(".home.arpa")
            }
            val address = runCatching { InetAddress.getByName(h) }.getOrNull() ?: return false
            val uniqueLocalV6 = address is Inet6Address && (address.address[0].toInt() and 0xfe) == 0xfc
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

        /** Découverte GDM des lecteurs Plex sur le réseau local. */
        suspend fun discover(timeoutMs: Int = 2_000): List<Device> = withContext(Dispatchers.IO) {
            val found = linkedMapOf<String, Device>()
            DatagramSocket().use { socket ->
                socket.broadcast = true
                val data = "M-SEARCH * HTTP/1.0\r\n\r\n".toByteArray(Charsets.US_ASCII)
                // Un échec d'envoi sur une des deux destinations ne doit pas empêcher l'autre.
                runCatching { socket.send(DatagramPacket(data, data.size, InetAddress.getByName(GDM_ADDRESS), GDM_PLAYER_PORT)) }
                runCatching { socket.send(DatagramPacket(data, data.size, InetAddress.getByName("255.255.255.255"), GDM_PLAYER_PORT)) }
                val buffer = ByteArray(8192)
                // Échéance globale : des réponses continues ne prolongent pas l'écoute.
                val deadline = System.nanoTime() + timeoutMs * 1_000_000L
                while (true) {
                    ensureActive()
                    val remainingMs = (deadline - System.nanoTime()) / 1_000_000L
                    if (remainingMs <= 0) break
                    socket.soTimeout = remainingMs.coerceAtMost(RECEIVE_SLICE_MS.toLong()).toInt().coerceAtLeast(1)
                    val packet = DatagramPacket(buffer, buffer.size)
                    try {
                        socket.receive(packet)
                    } catch (_: SocketTimeoutException) {
                        continue
                    }
                    val source = packet.address?.hostAddress ?: continue
                    parseGdm(String(packet.data, packet.offset, packet.length, Charsets.UTF_8), source)?.let { found[it.id] = it }
                }
            }
            found.values.toList()
        }
    }
}
