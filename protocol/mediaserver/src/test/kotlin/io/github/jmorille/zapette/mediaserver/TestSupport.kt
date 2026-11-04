package io.github.jmorille.zapette.mediaserver

import io.github.jmorille.zapette.model.UdpDatagram
import io.github.jmorille.zapette.model.UdpDestination
import io.github.jmorille.zapette.model.UdpOptions
import io.github.jmorille.zapette.model.UdpSocket
import io.github.jmorille.zapette.model.UdpSocketFactory
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.Assert.assertTrue
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TestTimeSource

/** Faux socket UDP rejouant des datagrammes ; chaque réception fait avancer [time]. */
internal class FakeUdpSocket(
    private val time: TestTimeSource,
    script: List<UdpDatagram?> = emptyList(),
    private val failingHosts: Set<String> = emptySet(),
    private val endless: UdpDatagram? = null,
) : UdpSocket, UdpSocketFactory {
    private val script = ArrayDeque(script)
    val sent = CopyOnWriteArrayList<Pair<UdpDestination, String>>()
    val options = CopyOnWriteArrayList<UdpOptions>()
    var receives = 0
        private set

    @Volatile var closed = false
        private set

    override fun open(options: UdpOptions): UdpSocket = also { this.options += options }

    override fun send(data: ByteArray, destination: UdpDestination) {
        if (destination.host in failingHosts) throw IOException("réseau injoignable : ${destination.host}")
        sent += destination to String(data, Charsets.UTF_8)
    }

    override fun receive(timeoutMs: Int): UdpDatagram? {
        receives++
        if (script.isNotEmpty()) {
            time += 10.milliseconds
            return script.removeFirst()
        }
        if (endless != null) {
            time += 100.milliseconds
            return endless
        }
        time += timeoutMs.milliseconds
        return null
    }

    override fun close() {
        closed = true
    }
}

internal fun datagram(text: String, source: String?) = UdpDatagram(text.toByteArray(Charsets.UTF_8), source)

/** Requête suivante, attendue au plus une seconde. */
internal fun MockWebServer.next(): RecordedRequest =
    takeRequest(1, TimeUnit.SECONDS) ?: throw AssertionError("requête attendue sous une seconde")

/** Vérifie que [block] échoue avec une exception de type [T] et la renvoie. */
internal suspend inline fun <reified T : Throwable> expectFailure(block: suspend () -> Unit): T {
    val error = try {
        block()
        null
    } catch (t: Throwable) {
        t
    }
    assertTrue("${T::class.simpleName} attendue, obtenu : $error", error is T)
    return error as T
}
