package io.github.jmorille.zapette.upnp

import io.github.jmorille.zapette.model.UdpDestination
import io.github.jmorille.zapette.model.UdpOptions
import io.github.jmorille.zapette.model.UdpSocketFactory
import io.github.jmorille.zapette.model.discover
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
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
}

/** Client SSDP (UPnP Device Architecture 1.1, multicast 239.255.255.250:1900). */
object Ssdp {
    const val MULTICAST_ADDRESS = "239.255.255.250"
    const val PORT = 1900

    /** TTL par défaut recommandé par UDA 1.1 pour les messages multicast. */
    const val MULTICAST_TTL = 2

    const val ST_MEDIA_RENDERER = "urn:schemas-upnp-org:device:MediaRenderer:1"
    const val ST_ROKU = "roku:ecp"

    /** UDA 1.1 : MX est borné entre 1 et 5 secondes. */
    fun searchMessage(searchTarget: String, mxSeconds: Int = 2): String =
        "M-SEARCH * HTTP/1.1\r\n" +
            "HOST: $MULTICAST_ADDRESS:$PORT\r\n" +
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
     */
    suspend fun search(
        searchTarget: String,
        timeoutMs: Int = 3_000,
        networkInterface: NetworkInterface? = null,
        sockets: UdpSocketFactory = UdpSocketFactory.Default,
        timeSource: TimeSource = TimeSource.Monotonic,
    ): List<SsdpResponse> {
        // MX inférieur à la fenêtre d'écoute pour laisser aux réponses le temps d'arriver.
        val mx = (timeoutMs / 1000 - 1).coerceIn(1, 5)
        val data = searchMessage(searchTarget, mx).toByteArray(Charsets.US_ASCII)
        val group = UdpDestination(MULTICAST_ADDRESS, PORT)
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
