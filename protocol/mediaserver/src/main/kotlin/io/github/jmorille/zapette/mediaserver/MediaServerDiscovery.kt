package io.github.jmorille.zapette.mediaserver

import io.github.jmorille.zapette.model.Device
import io.github.jmorille.zapette.model.DeviceKind
import io.github.jmorille.zapette.model.UdpDestination
import io.github.jmorille.zapette.model.UdpOptions
import io.github.jmorille.zapette.model.UdpSocketFactory
import io.github.jmorille.zapette.model.discover
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.net.InetAddress
import kotlin.time.TimeSource

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

    /** Extra portant le schéma annoncé (`https`) : [Device.baseUrl] impose `http`. */
    const val EXTRA_SCHEME = "scheme"

    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }

    fun probe(flavor: MediaServerFlavor): String = when (flavor) {
        MediaServerFlavor.JELLYFIN -> "who is JellyfinServer?"
        MediaServerFlavor.EMBY -> "who is EmbyServer?"
    }

    /**
     * Convertit une réponse en [Device] ; `null` si elle est invalide.
     *
     * Quand [sourceAddress] (émetteur du paquet) est connu, il fait foi : une adresse annoncée
     * désignant un autre hôte est remplacée par l'émetteur (le port annoncé est conservé), sinon
     * un paquet forgé redirigerait identifiants et jeton vers un tiers. L'émetteur remplace aussi
     * une adresse inutilisable (absente, boucle locale ou joker), cas fréquent derrière Docker.
     * Un schéma `https` annoncé est conservé dans l'extra [EXTRA_SCHEME].
     */
    fun parseReply(payload: String, flavor: MediaServerFlavor, sourceAddress: String? = null): Device? {
        val reply = runCatching { json.decodeFromString(DiscoveryReply.serializer(), payload) }.getOrNull() ?: return null
        if (reply.id.isBlank()) return null
        val url = reply.address?.toHttpUrlOrNull()
        val announced = url?.host?.takeUnless(::isUnusableHost)
        val source = sourceAddress?.substringBefore('%')?.ifBlank { null }
        val host = when {
            source == null -> announced ?: return null
            announced != null && sameHost(announced, source) -> announced
            else -> source
        }
        val port = url?.port ?: DeviceKind.JELLYFIN.defaultPort
        val kind = if (flavor == MediaServerFlavor.EMBY) DeviceKind.EMBY else DeviceKind.JELLYFIN
        return Device(
            id = "${kind.name}:${reply.id}",
            name = reply.name?.takeIf { it.isNotBlank() } ?: kind.label,
            kind = kind,
            host = host,
            port = port,
            extras = if (url?.isHttps == true) mapOf(EXTRA_SCHEME to "https") else emptyMap(),
        )
    }

    /** Compare deux hôtes littéraux sans résolution DNS (IPv6 normalisée). */
    private fun sameHost(announced: String, source: String): Boolean {
        if (announced.equals(source, ignoreCase = true)) return true
        if (':' !in announced || ':' !in source) return false
        return runCatching { InetAddress.getByName(announced) == InetAddress.getByName(source) }.getOrDefault(false)
    }

    private fun isUnusableHost(host: String): Boolean =
        host == "localhost" || host == "0.0.0.0" || host == "::" || host == "::1" || host.startsWith("127.")

    /** Diffuse la sonde puis collecte les réponses jusqu'à l'échéance globale [timeoutMs]. */
    suspend fun discover(
        flavor: MediaServerFlavor,
        timeoutMs: Int = 2_000,
        sockets: UdpSocketFactory = UdpSocketFactory.Default,
        timeSource: TimeSource = TimeSource.Monotonic,
    ): List<Device> = sockets.discover(
        options = UdpOptions(broadcast = true),
        destinations = listOf(UdpDestination("255.255.255.255", PORT)),
        payload = probe(flavor).toByteArray(Charsets.UTF_8),
        timeoutMs = timeoutMs.toLong(),
        timeSource = timeSource,
    ) { datagram -> parseReply(datagram.text(), flavor, datagram.sourceAddress)?.let { it.id to it } }
}
