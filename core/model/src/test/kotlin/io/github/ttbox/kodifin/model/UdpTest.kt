package io.github.ttbox.kodifin.model

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TestTimeSource

/** Faux socket rejouant des datagrammes ; le temps avance sur [time] à chaque réception. */
private class FakeUdpSocket(
    private val time: TestTimeSource,
    script: List<UdpDatagram?> = emptyList(),
    private val failingHosts: Set<String> = emptySet(),
    private val endless: UdpDatagram? = null,
    private val blockWhenEmpty: Boolean = false,
) : UdpSocket {
    private val script = ArrayDeque(script)
    val sent = CopyOnWriteArrayList<UdpDestination>()
    val receives = AtomicInteger()
    private val closedLatch = CountDownLatch(1)

    @Volatile var closed = false

    override fun send(data: ByteArray, destination: UdpDestination) {
        if (destination.host in failingHosts) throw IOException("réseau injoignable : ${destination.host}")
        sent += destination
    }

    override fun receive(timeoutMs: Int): UdpDatagram? {
        if (closed) throw SocketException("Socket closed")
        receives.incrementAndGet()
        if (script.isNotEmpty()) {
            time += 10.milliseconds
            return script.removeFirst()
        }
        if (endless != null) {
            time += 100.milliseconds
            return endless
        }
        if (blockWhenEmpty) {
            closedLatch.await(10, TimeUnit.SECONDS)
            throw SocketException("Socket closed")
        }
        time += timeoutMs.milliseconds
        return null
    }

    override fun close() {
        closed = true
        closedLatch.countDown()
    }
}

class UdpTest {
    private val time = TestTimeSource()
    private val destinations = listOf(UdpDestination("239.0.0.1", 1), UdpDestination("255.255.255.255", 2))

    private fun datagram(text: String, source: String? = "10.0.0.2") = UdpDatagram(text.toByteArray(), source)

    private fun factory(socket: UdpSocket, seen: MutableList<UdpOptions> = mutableListOf()) =
        UdpSocketFactory { seen += it; socket }

    private suspend fun run(socket: FakeUdpSocket, timeoutMs: Long = 1_000): List<String> =
        factory(socket).discover(UdpOptions(broadcast = true), destinations, "?".toByteArray(), timeoutMs, time) { d ->
            val text = d.text()
            require(!text.startsWith("illisible")) { "paquet illisible" }
            text.substringBefore('=') to text
        }

    @Test
    fun `replies are deduplicated by key, latest wins, unreadable packets are skipped`() = runBlocking {
        val socket = FakeUdpSocket(time, listOf(datagram("a=1"), datagram("illisible"), null, datagram("b=1"), datagram("a=2")))
        assertEquals(listOf("a=2", "b=1"), run(socket))
        assertEquals(destinations, socket.sent)
        assertTrue("le socket doit être fermé", socket.closed)
    }

    @Test
    fun `global deadline is honoured despite a continuous stream of packets`() = runBlocking {
        val socket = FakeUdpSocket(time, endless = datagram("x=1"))
        assertEquals(listOf("x=1"), run(socket, timeoutMs = 1_000))
        // 100 ms simulées par paquet : l'écoute s'arrête à l'échéance (10 réceptions).
        assertEquals(10, socket.receives.get())
    }

    @Test
    fun `a failing destination does not prevent the other one`() = runBlocking {
        val socket = FakeUdpSocket(time, listOf(datagram("a=1")), failingHosts = setOf("239.0.0.1"))
        assertEquals(listOf("a=1"), run(socket))
        assertEquals(listOf(UdpDestination("255.255.255.255", 2)), socket.sent)
    }

    @Test
    fun `send failure on every destination is reported`() {
        val socket = FakeUdpSocket(time, failingHosts = setOf("239.0.0.1", "255.255.255.255"))
        val e = runCatching { runBlocking { run(socket) } }.exceptionOrNull()
        assertTrue("IOException attendue : $e", e is IOException)
        assertTrue(e!!.message!!.contains("255.255.255.255"))
        assertEquals(0, socket.receives.get())
        assertTrue(socket.closed)
    }

    @Test
    fun `cancellation closes the socket and returns promptly`() = runBlocking {
        val socket = FakeUdpSocket(time, blockWhenEmpty = true)
        val result = CompletableResult()
        val job = launch(Dispatchers.Default) {
            try {
                run(socket, timeoutMs = 60_000)
            } catch (e: CancellationException) {
                result.cancelled = true
                throw e
            }
        }
        val waitStart = System.nanoTime()
        while (socket.receives.get() == 0 && System.nanoTime() - waitStart < 5_000_000_000) Thread.sleep(5)
        assertEquals(1, socket.receives.get())
        val start = System.nanoTime()
        job.cancelAndJoin()
        val elapsedMs = (System.nanoTime() - start) / 1_000_000
        assertTrue("annulation trop lente : $elapsedMs ms", elapsedMs < 2_000)
        assertTrue(socket.closed)
        assertTrue(result.cancelled)
    }

    private class CompletableResult {
        @Volatile var cancelled = false
    }

    @Test
    fun `default factory exchanges datagrams over loopback`() {
        DatagramSocket(InetSocketAddress(InetAddress.getLoopbackAddress(), 0)).use { peer ->
            peer.soTimeout = 2_000
            UdpSocketFactory.Default.open(UdpOptions(broadcast = true)).use { socket ->
                assertNull("aucun datagramme : délai écoulé", socket.receive(20))
                socket.send("ping".toByteArray(), UdpDestination("127.0.0.1", peer.localPort))
                val packet = DatagramPacket(ByteArray(64), 64)
                peer.receive(packet)
                assertEquals("ping", String(packet.data, 0, packet.length))
                val reply = "pong".toByteArray()
                peer.send(DatagramPacket(reply, reply.size, packet.socketAddress))
                val received = socket.receive(2_000)
                assertNotNull(received)
                assertEquals("pong", received!!.text())
                assertEquals("127.0.0.1", received.sourceAddress)
            }
        }
    }

    @Test
    fun `default factory opens a multicast socket when a ttl is requested`() {
        UdpSocketFactory.Default.open(UdpOptions(multicastTtl = 2)).use { socket ->
            assertNull(socket.receive(10))
        }
    }
}
