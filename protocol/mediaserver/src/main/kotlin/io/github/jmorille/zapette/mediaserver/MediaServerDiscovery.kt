package io.github.jmorille.zapette.mediaserver

import io.github.jmorille.zapette.model.Device
import io.github.jmorille.zapette.model.DeviceKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
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
    @SerialName("Address") val address: String? = null,
    @SerialName("Id") val id: String,
    @SerialName("Name") val name: String? = null,
)

/**
 * Découverte Jellyfin / Emby : diffusion UDP sur le port 7359 du message
 * `who is JellyfinServer?` (ou `EmbyServer`) ; chaque serveur répond en JSON.
 */
object MediaServerDiscovery {
    const val PORT = 7359

    /** Fenêtre d'écoute par réception : courte pour respecter l'échéance globale et l'annulation. */
    private const val RECEIVE_SLICE_MS = 250
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }

    fun probe(flavor: MediaServerFlavor): String = when (flavor) {
        MediaServerFlavor.JELLYFIN -> "who is JellyfinServer?"
        MediaServerFlavor.EMBY -> "who is EmbyServer?"
    }

    /**
     * Convertit une réponse en [Device] ; `null` si elle est invalide.
     * [sourceAddress] (émetteur du paquet) remplace une adresse annoncée inutilisable
     * (absente, boucle locale ou joker), cas fréquent derrière Docker.
     */
    fun parseReply(payload: String, flavor: MediaServerFlavor, sourceAddress: String? = null): Device? {
        val reply = runCatching { json.decodeFromString(DiscoveryReply.serializer(), payload) }.getOrNull() ?: return null
        if (reply.id.isBlank()) return null
        val url = reply.address?.toHttpUrlOrNull()
        val host = url?.host?.takeUnless(::isUnusableHost) ?: sourceAddress ?: return null
        val port = url?.port ?: DeviceKind.JELLYFIN.defaultPort
        val kind = if (flavor == MediaServerFlavor.EMBY) DeviceKind.EMBY else DeviceKind.JELLYFIN
        return Device(
            id = "${kind.name}:${reply.id}",
            name = reply.name?.takeIf { it.isNotBlank() } ?: kind.label,
            kind = kind,
            host = host,
            port = port,
        )
    }

    private fun isUnusableHost(host: String): Boolean =
        host == "localhost" || host == "0.0.0.0" || host == "::" || host == "::1" || host.startsWith("127.")

    suspend fun discover(flavor: MediaServerFlavor, timeoutMs: Int = 2_000): List<Device> = withContext(Dispatchers.IO) {
        val found = linkedMapOf<String, Device>()
        DatagramSocket().use { socket ->
            socket.broadcast = true
            val data = probe(flavor).toByteArray(Charsets.UTF_8)
            socket.send(DatagramPacket(data, data.size, InetAddress.getByName("255.255.255.255"), PORT))
            val buffer = ByteArray(8192)
            // Échéance globale : un flot continu de réponses ne prolonge pas l'écoute indéfiniment.
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
                val payload = String(packet.data, packet.offset, packet.length, Charsets.UTF_8)
                parseReply(payload, flavor, packet.address?.hostAddress)?.let { found[it.id] = it }
            }
        }
        found.values.toList()
    }
}
