package io.github.jmorille.zapette.plex

import io.github.jmorille.zapette.model.Capability
import io.github.jmorille.zapette.model.Device
import io.github.jmorille.zapette.model.DeviceKind
import io.github.jmorille.zapette.model.RemoteController
import io.github.jmorille.zapette.model.RemoteKey
import io.github.jmorille.zapette.model.fetchString
import io.github.jmorille.zapette.model.requireKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.DatagramPacket
import java.net.DatagramSocket
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

    private val base: HttpUrl = device.baseUrl.toHttpUrl()
    private val commandId = AtomicInteger(0)
    private var volume = 50

    internal suspend fun command(path: String, params: Map<String, String> = emptyMap()) {
        val url = base.newBuilder().encodedPath(path).apply {
            params.forEach { (k, v) -> addQueryParameter(k, v) }
            addQueryParameter("commandID", commandId.incrementAndGet().toString())
        }.build()
        val builder = identity.apply(Request.Builder().url(url))
            .header("X-Plex-Target-Client-Identifier", device.extras[EXTRA_CLIENT_ID] ?: device.id.removePrefix("PLEX:"))
        device.extras[EXTRA_TOKEN]?.let { builder.header("X-Plex-Token", it) }
        http.fetchString(builder.get().build())
    }

    override suspend fun send(key: RemoteKey) {
        requireKey(key)
        when (key) {
            RemoteKey.VOLUME_UP -> setVolume(volume + 10)
            RemoteKey.VOLUME_DOWN -> setVolume(volume - 10)
            else -> command(COMMANDS.getValue(key))
        }
    }

    override suspend fun setVolume(percent: Int) {
        volume = percent.coerceIn(0, 100)
        command("/player/playback/setParameters", mapOf("volume" to volume.toString(), "type" to "video"))
    }

    override suspend fun seekTo(positionMs: Long) {
        command("/player/playback/seekTo", mapOf("offset" to positionMs.toString(), "type" to "video"))
    }

    companion object {
        const val EXTRA_TOKEN = "token"
        const val EXTRA_CLIENT_ID = "clientIdentifier"
        const val GDM_ADDRESS = "239.0.0.250"
        const val GDM_PLAYER_PORT = 32412

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

        /** Analyse une réponse GDM (`HTTP/1.0 200 OK` + en-têtes `Clé: valeur`). */
        fun parseGdm(payload: String, address: String): Device? {
            val lines = payload.split("\r\n", "\n")
            if (lines.firstOrNull()?.contains("200 OK") != true) return null
            val headers = lines.drop(1).mapNotNull {
                val i = it.indexOf(':')
                if (i <= 0) null else it.substring(0, i).trim().lowercase() to it.substring(i + 1).trim()
            }.toMap()
            if (headers["content-type"]?.contains("player") != true) return null
            val id = headers["resource-identifier"] ?: return null
            return Device(
                id = "PLEX:$id",
                name = headers["name"] ?: "Plex",
                kind = DeviceKind.PLEX,
                host = address,
                port = headers["port"]?.toIntOrNull() ?: DeviceKind.PLEX.defaultPort,
                extras = mapOf(EXTRA_CLIENT_ID to id),
            )
        }

        /** Découverte GDM des lecteurs Plex sur le réseau local. */
        suspend fun discover(timeoutMs: Int = 2_000): List<Device> = withContext(Dispatchers.IO) {
            val found = linkedMapOf<String, Device>()
            DatagramSocket().use { socket ->
                socket.broadcast = true
                socket.soTimeout = timeoutMs
                val data = "M-SEARCH * HTTP/1.0\r\n\r\n".toByteArray()
                socket.send(DatagramPacket(data, data.size, InetAddress.getByName(GDM_ADDRESS), GDM_PLAYER_PORT))
                socket.send(DatagramPacket(data, data.size, InetAddress.getByName("255.255.255.255"), GDM_PLAYER_PORT))
                val buffer = ByteArray(4096)
                try {
                    while (true) {
                        val packet = DatagramPacket(buffer, buffer.size)
                        socket.receive(packet)
                        parseGdm(String(packet.data, 0, packet.length), packet.address.hostAddress)?.let { found[it.id] = it }
                    }
                } catch (_: SocketTimeoutException) {
                    // Fenêtre d'écoute écoulée.
                }
            }
            found.values.toList()
        }
    }
}
