package io.github.jmorille.zapette.model

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.SocketTimeoutException
import kotlin.coroutines.CoroutineContext
import kotlin.time.TimeSource

/** Datagramme reçu : contenu et adresse littérale de l'émetteur (`null` si inconnue). */
class UdpDatagram(val payload: ByteArray, val sourceAddress: String?) {
    fun text(): String = String(payload, Charsets.UTF_8)
}

/** Destination d'un datagramme. */
data class UdpDestination(val host: String, val port: Int)

/**
 * Réglages du socket. [multicastTtl] ou [networkInterface] imposent un socket multicast
 * (lié sur un port éphémère avec `SO_REUSEADDR`), sinon un simple socket datagramme suffit.
 */
data class UdpOptions(
    val broadcast: Boolean = false,
    val multicastTtl: Int? = null,
    val networkInterface: NetworkInterface? = null,
)

/** Socket UDP minimal, remplaçable en test par un faux rejouant des datagrammes. */
interface UdpSocket : Closeable {
    /** Envoie [data] ; lève [IOException] en cas d'échec. */
    fun send(data: ByteArray, destination: UdpDestination)

    /** Attend un datagramme au plus [timeoutMs] ms ; `null` si le délai est écoulé. */
    fun receive(timeoutMs: Int): UdpDatagram?
}

/** Fabrique de sockets UDP injectable. */
fun interface UdpSocketFactory {
    fun open(options: UdpOptions): UdpSocket

    companion object {
        /** Fabrique réelle basée sur [DatagramSocket] / [MulticastSocket]. */
        val Default: UdpSocketFactory = UdpSocketFactory { JavaUdpSocket(it) }
    }
}

private class JavaUdpSocket(options: UdpOptions) : UdpSocket {
    private val socket: DatagramSocket =
        if (options.multicastTtl != null || options.networkInterface != null) {
            MulticastSocket(null).apply {
                reuseAddress = true
                bind(InetSocketAddress(0))
                options.multicastTtl?.let { timeToLive = it }
                options.networkInterface?.let { networkInterface = it }
            }
        } else {
            DatagramSocket()
        }.apply { broadcast = options.broadcast }

    private val buffer = ByteArray(MAX_DATAGRAM)

    override fun send(data: ByteArray, destination: UdpDestination) {
        socket.send(DatagramPacket(data, data.size, InetAddress.getByName(destination.host), destination.port))
    }

    override fun receive(timeoutMs: Int): UdpDatagram? {
        socket.soTimeout = timeoutMs.coerceAtLeast(1)
        val packet = DatagramPacket(buffer, buffer.size)
        try {
            socket.receive(packet)
        } catch (_: SocketTimeoutException) {
            return null
        }
        return UdpDatagram(packet.data.copyOfRange(packet.offset, packet.offset + packet.length), packet.address?.hostAddress)
    }

    override fun close() = socket.close()

    companion object {
        const val MAX_DATAGRAM = 8192
    }
}

/** Fenêtre d'attente par réception : courte pour respecter l'échéance globale et l'annulation. */
const val UDP_RECEIVE_SLICE_MS = 250

/**
 * Découverte UDP générique : envoie [payload] à chaque destination de [destinations]
 * (un échec d'envoi n'empêche pas les suivants ; si tous échouent, la dernière erreur est levée),
 * puis collecte les réponses jusqu'à l'échéance globale [timeoutMs], mesurée sur [timeSource].
 * [parse] renvoie une clé de dédoublonnage et une valeur (la plus récente l'emporte), ou `null`
 * pour ignorer le datagramme. L'annulation ferme le socket, ce qui débloque la réception en cours.
 */
suspend fun <T> UdpSocketFactory.discover(
    options: UdpOptions,
    destinations: List<UdpDestination>,
    payload: ByteArray,
    timeoutMs: Long,
    timeSource: TimeSource = TimeSource.Monotonic,
    context: CoroutineContext = Dispatchers.IO,
    parse: (UdpDatagram) -> Pair<String, T>?,
): List<T> = withContext(context) {
    val found = linkedMapOf<String, T>()
    open(options).use { socket ->
        coroutineScope {
            // Surveille l'annulation : fermer le socket interrompt un receive() bloquant.
            val watcher = launch {
                try {
                    awaitCancellation()
                } finally {
                    runCatching { socket.close() }
                }
            }
            try {
                var lastError: IOException? = null
                var sent = 0
                for (destination in destinations) {
                    try {
                        socket.send(payload, destination)
                        sent++
                    } catch (e: IOException) {
                        lastError = e
                    }
                }
                if (sent == 0 && lastError != null) throw lastError
                val start = timeSource.markNow()
                while (true) {
                    ensureActive()
                    val remaining = timeoutMs - start.elapsedNow().inWholeMilliseconds
                    if (remaining <= 0) break
                    val datagram = try {
                        socket.receive(remaining.coerceAtMost(UDP_RECEIVE_SLICE_MS.toLong()).toInt())
                    } catch (e: IOException) {
                        ensureActive() // socket fermé par l'annulation
                        throw e
                    } ?: continue
                    val (key, value) = runCatching { parse(datagram) }.getOrNull() ?: continue
                    found[key] = value
                }
            } finally {
                watcher.cancel()
            }
        }
    }
    found.values.toList()
}
