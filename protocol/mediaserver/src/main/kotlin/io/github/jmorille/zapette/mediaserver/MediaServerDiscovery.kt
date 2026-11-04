package io.github.jmorille.zapette.mediaserver

import io.github.jmorille.zapette.model.Device
import io.github.jmorille.zapette.model.DeviceKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException

@Serializable
internal data class DiscoveryReply(
    @SerialName("Address") val address: String,
    @SerialName("Id") val id: String,
    @SerialName("Name") val name: String,
)

/**
 * Découverte Jellyfin / Emby : diffusion UDP sur le port 7359 du message
 * `who is JellyfinServer?` (ou `EmbyServer`) ; chaque serveur répond en JSON.
 */
object MediaServerDiscovery {
    const val PORT = 7359
    private val json = Json { ignoreUnknownKeys = true }

    fun probe(flavor: MediaServerFlavor): String = when (flavor) {
        MediaServerFlavor.JELLYFIN -> "who is JellyfinServer?"
        MediaServerFlavor.EMBY -> "who is EmbyServer?"
    }

    /** Convertit une réponse en [Device] ; `null` si elle est invalide. */
    fun parseReply(payload: String, flavor: MediaServerFlavor): Device? {
        val reply = runCatching { json.decodeFromString(DiscoveryReply.serializer(), payload) }.getOrNull() ?: return null
        val url = reply.address.toHttpUrlOrNull() ?: return null
        val kind = if (flavor == MediaServerFlavor.EMBY) DeviceKind.EMBY else DeviceKind.JELLYFIN
        return Device(
            id = "${kind.name}:${reply.id}",
            name = reply.name,
            kind = kind,
            host = url.host,
            port = url.port,
        )
    }

    suspend fun discover(flavor: MediaServerFlavor, timeoutMs: Int = 2_000): List<Device> = withContext(Dispatchers.IO) {
        val found = linkedMapOf<String, Device>()
        DatagramSocket().use { socket ->
            socket.broadcast = true
            socket.soTimeout = timeoutMs
            val data = probe(flavor).toByteArray()
            socket.send(DatagramPacket(data, data.size, InetAddress.getByName("255.255.255.255"), PORT))
            val buffer = ByteArray(4096)
            try {
                while (true) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    socket.receive(packet)
                    parseReply(String(packet.data, 0, packet.length), flavor)?.let { found[it.id] = it }
                }
            } catch (_: SocketTimeoutException) {
                // Fin de la fenêtre d'écoute.
            }
        }
        found.values.toList()
    }
}
