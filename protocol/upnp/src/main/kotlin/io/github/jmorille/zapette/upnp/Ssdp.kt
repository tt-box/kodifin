package io.github.jmorille.zapette.upnp

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.SocketTimeoutException

/** Réponse SSDP (en-têtes normalisés en majuscules). */
data class SsdpResponse(val headers: Map<String, String>, val sourceAddress: String) {
    val location: String? get() = headers["LOCATION"]
    val searchTarget: String? get() = headers["ST"]
    val usn: String? get() = headers["USN"]
    val server: String? get() = headers["SERVER"]
}

/** Client SSDP (UPnP Device Architecture 1.1, multicast 239.255.255.250:1900). */
object Ssdp {
    const val MULTICAST_ADDRESS = "239.255.255.250"
    const val PORT = 1900

    const val ST_MEDIA_RENDERER = "urn:schemas-upnp-org:device:MediaRenderer:1"
    const val ST_ROKU = "roku:ecp"

    fun searchMessage(searchTarget: String, mxSeconds: Int = 2): String =
        "M-SEARCH * HTTP/1.1\r\n" +
            "HOST: $MULTICAST_ADDRESS:$PORT\r\n" +
            "MAN: \"ssdp:discover\"\r\n" +
            "MX: $mxSeconds\r\n" +
            "ST: $searchTarget\r\n" +
            "USER-AGENT: Android/16 UPnP/1.1 Zapette/1.0\r\n\r\n"

    /** Analyse une réponse `HTTP/1.1 200 OK` ou une annonce `NOTIFY` ; `null` sinon. */
    fun parse(payload: String, sourceAddress: String): SsdpResponse? {
        val lines = payload.split("\r\n", "\n")
        val status = lines.firstOrNull()?.trim() ?: return null
        if (!status.startsWith("HTTP/1.1 200") && !status.startsWith("NOTIFY")) return null
        val headers = lines.drop(1).mapNotNull { line ->
            val idx = line.indexOf(':')
            if (idx <= 0) null else line.substring(0, idx).trim().uppercase() to line.substring(idx + 1).trim()
        }.toMap()
        return SsdpResponse(headers, sourceAddress)
    }

    /** Envoie un M-SEARCH et collecte les réponses pendant [timeoutMs]. */
    suspend fun search(searchTarget: String, timeoutMs: Int = 3_000): List<SsdpResponse> = withContext(Dispatchers.IO) {
        val results = linkedMapOf<String, SsdpResponse>()
        MulticastSocket(null).use { socket ->
            socket.reuseAddress = true
            socket.bind(InetSocketAddress(0))
            socket.soTimeout = timeoutMs
            val data = searchMessage(searchTarget).toByteArray()
            val group = InetAddress.getByName(MULTICAST_ADDRESS)
            repeat(2) { socket.send(DatagramPacket(data, data.size, group, PORT)) }
            val buffer = ByteArray(8192)
            val deadline = System.currentTimeMillis() + timeoutMs
            try {
                while (System.currentTimeMillis() < deadline) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    socket.receive(packet)
                    val response = parse(String(packet.data, 0, packet.length), packet.address.hostAddress) ?: continue
                    val key = response.usn ?: response.location ?: continue
                    results[key] = response
                }
            } catch (_: SocketTimeoutException) {
                // Fenêtre d'écoute écoulée.
            }
        }
        results.values.toList()
    }
}
