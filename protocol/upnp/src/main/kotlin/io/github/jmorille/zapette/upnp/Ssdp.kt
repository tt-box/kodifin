package io.github.jmorille.zapette.upnp

import io.github.jmorille.zapette.model.UdpDestination
import io.github.jmorille.zapette.model.UdpOptions
import io.github.jmorille.zapette.model.UdpSocketFactory
import io.github.jmorille.zapette.model.discover
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.IOException
import java.net.Inet6Address
import java.net.InetAddress
import java.net.NetworkInterface
import kotlin.time.TimeSource

/** Réponse SSDP (en-têtes normalisés en majuscules). */
data class SsdpResponse(val headers: Map<String, String>, val sourceAddress: String) {
    val location: String? get() = headers["LOCATION"]
    val searchTarget: String? get() = headers["ST"]
    val usn: String? get() = headers["USN"]
    val server: String? get() = headers["SERVER"]

    /**
     * Vrai si LOCATION est une URL HTTP(S) qui désigne l'hôte ayant émis la réponse.
     * Évite qu'une réponse forgée sur le réseau local fasse interroger un hôte tiers
     * (autre machine du LAN ou serveur Internet). Aucune résolution DNS n'est effectuée.
     */
    val hasTrustedLocation: Boolean
        get() {
            val host = location?.toHttpUrlOrNull()?.host ?: return false
            if (host == sourceAddress) return true
            if (':' !in host || ':' !in sourceAddress) return false
            // IPv6 : comparaison des adresses littérales (sans identifiant de portée).
            return runCatching {
                InetAddress.getByName(host) == InetAddress.getByName(sourceAddress.substringBefore('%'))
            }.getOrDefault(false)
        }

    /** Vrai si LOCATION désigne une IPv6 lien local (`fe80::/10`), injoignable sans zone. */
    val hasLinkLocalLocation: Boolean
        get() {
            val host = location?.toHttpUrlOrNull()?.host ?: return false
            if (':' !in host) return false
            return runCatching { InetAddress.getByName(host).let { it is Inet6Address && it.isLinkLocalAddress } }
                .getOrDefault(false)
        }
}

/** Client SSDP (UPnP Device Architecture 1.1, multicast 239.255.255.250:1900). */
object Ssdp {
    const val MULTICAST_ADDRESS = "239.255.255.250"
    const val PORT = 1900

    /** TTL par défaut recommandé par UDA 1.1 pour les messages multicast. */
    const val MULTICAST_TTL = 2

    const val ST_MEDIA_RENDERER = "urn:schemas-upnp-org:device:MediaRenderer:1"
    const val ST_ROKU = "roku:ecp"

    /** Groupe SSDP IPv6 de portée lien local (UDA 1.1 §1.1.2). */
    const val MULTICAST_ADDRESS_V6 = "FF02::C"

    /** UDA 1.1 : MX est borné entre 1 et 5 secondes. [host] : `[FF02::C]:1900` en IPv6. */
    fun searchMessage(searchTarget: String, mxSeconds: Int = 2, host: String = "$MULTICAST_ADDRESS:$PORT"): String =
        "M-SEARCH * HTTP/1.1\r\n" +
            "HOST: $host\r\n" +
            "MAN: \"ssdp:discover\"\r\n" +
            "MX: ${mxSeconds.coerceIn(1, 5)}\r\n" +
            "ST: $searchTarget\r\n" +
            "USER-AGENT: Android/16 UPnP/1.1 Zapette/1.0\r\n\r\n"

    private val STATUS_OK = Regex("""^HTTP/1\.[01]\s+200\b""", RegexOption.IGNORE_CASE)

    /** Analyse une réponse `HTTP/1.x 200` ou une annonce `NOTIFY` ; `null` sinon. */
    fun parse(payload: String, sourceAddress: String): SsdpResponse? {
        val lines = payload.split("\r\n", "\n")
        val status = lines.firstOrNull()?.trim() ?: return null
        if (!STATUS_OK.containsMatchIn(status) && !status.startsWith("NOTIFY ", ignoreCase = true)) return null
        val headers = linkedMapOf<String, String>()
        var last: String? = null
        for (line in lines.drop(1)) {
            if (line.isEmpty()) break // fin des en-têtes
            if ((line[0] == ' ' || line[0] == '\t') && last != null) {
                // Ligne repliée (RFC 2616 §2.2) : rattachée à l'en-tête précédent.
                headers[last] = (headers[last].orEmpty() + " " + line.trim()).trim()
                continue
            }
            val idx = line.indexOf(':')
            if (idx <= 0) continue
            val name = line.substring(0, idx).trim().uppercase()
            headers[name] = line.substring(idx + 1).trim()
            last = name
        }
        return SsdpResponse(headers, sourceAddress)
    }

    /**
     * Envoie un M-SEARCH et collecte les réponses pendant [timeoutMs] (durée totale bornée).
     * [networkInterface] force l'interface d'émission (Wi-Fi/Ethernet) quand l'appareil en a plusieurs.
     * Les réponses dont LOCATION ne désigne pas l'émetteur sont ignorées ; les doublons sont
     * fusionnés par USN (à défaut par LOCATION). [sockets] et [timeSource] sont injectables pour les tests.
     *
     * [ipv6] ajoute, en parallèle et dans la même fenêtre, un M-SEARCH vers `[FF02::C]:1900`
     * (groupe lien local, émis sur l'interface par défaut ou [networkInterface]). Un échec IPv6
     * (pas d'IPv6 sur le réseau) est ignoré. Une réponse dont LOCATION est une IPv6 lien local est
     * écartée : sans identifiant de zone (`%wlan0`), qu'une URL HTTP ne peut pas porter, l'appareil
     * serait injoignable. Un appareil répondant dans les deux familles est gardé en IPv4.
     */
    suspend fun search(
        searchTarget: String,
        timeoutMs: Int = 3_000,
        networkInterface: NetworkInterface? = null,
        sockets: UdpSocketFactory = UdpSocketFactory.Default,
        timeSource: TimeSource = TimeSource.Monotonic,
        ipv6: Boolean = false,
    ): List<SsdpResponse> = coroutineScope {
        val v6 = if (ipv6) {
            async {
                try {
                    searchGroup(searchTarget, timeoutMs, networkInterface, sockets, timeSource, MULTICAST_ADDRESS_V6, "[$MULTICAST_ADDRESS_V6]:$PORT")
                        .filterNot { it.hasLinkLocalLocation }
                } catch (_: IOException) {
                    emptyList()
                }
            }
        } else {
            null
        }
        val v4 = searchGroup(searchTarget, timeoutMs, networkInterface, sockets, timeSource, MULTICAST_ADDRESS, "$MULTICAST_ADDRESS:$PORT")
        val merged = LinkedHashMap<String, SsdpResponse>()
        v4.forEach { merged[it.dedupKey()] = it }
        v6?.await()?.forEach { merged.putIfAbsent(it.dedupKey(), it) }
        merged.values.toList()
    }

    private fun SsdpResponse.dedupKey(): String = usn ?: location.orEmpty()

    private suspend fun searchGroup(
        searchTarget: String,
        timeoutMs: Int,
        networkInterface: NetworkInterface?,
        sockets: UdpSocketFactory,
        timeSource: TimeSource,
        address: String,
        hostHeader: String,
    ): List<SsdpResponse> {
        // MX inférieur à la fenêtre d'écoute pour laisser aux réponses le temps d'arriver.
        val mx = (timeoutMs / 1000 - 1).coerceIn(1, 5)
        val data = searchMessage(searchTarget, mx, hostHeader).toByteArray(Charsets.US_ASCII)
        val group = UdpDestination(address, PORT)
        return sockets.discover(
            options = UdpOptions(multicastTtl = MULTICAST_TTL, networkInterface = networkInterface),
            // UDP n'étant pas fiable, le M-SEARCH est émis deux fois.
            destinations = listOf(group, group),
            payload = data,
            timeoutMs = timeoutMs.toLong(),
            timeSource = timeSource,
        ) { datagram ->
            val source = datagram.sourceAddress ?: return@discover null
            val response = parse(datagram.text(), source) ?: return@discover null
            if (!response.hasTrustedLocation) return@discover null
            val key = response.usn ?: response.location ?: return@discover null
            key to response
        }
    }
}
