package io.github.ttbox.kodifin.model

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TestTimeSource

class SsdpTest {
    @Test
    fun `search message follows UDA format`() {
        val msg = Ssdp.searchMessage(Ssdp.ST_MEDIA_RENDERER, 3)
        assertTrue(msg.startsWith("M-SEARCH * HTTP/1.1\r\n"))
        assertTrue(msg.contains("HOST: 239.255.255.250:1900\r\n"))
        assertTrue(msg.contains("MAN: \"ssdp:discover\"\r\n"))
        assertTrue(msg.contains("MX: 3\r\n"))
        assertTrue(msg.contains("ST: urn:schemas-upnp-org:device:MediaRenderer:1\r\n"))
        assertTrue(msg.endsWith("\r\n\r\n"))
    }

    @Test
    fun `parses response headers case-insensitively`() {
        val r = Ssdp.parse(
            "HTTP/1.1 200 OK\r\nCache-Control: max-age=1800\r\nlocation: http://10.0.0.9:49152/desc.xml\r\n" +
                "ST: roku:ecp\r\nUSN: uuid:roku:ecp:ABC\r\n\r\n",
            "10.0.0.9",
        )!!
        assertEquals("http://10.0.0.9:49152/desc.xml", r.location)
        assertEquals("roku:ecp", r.searchTarget)
        assertEquals("uuid:roku:ecp:ABC", r.usn)
        assertEquals("10.0.0.9", r.sourceAddress)
        assertNull(Ssdp.parse("M-SEARCH * HTTP/1.1\r\n\r\n", "x"))
        assertNull(Ssdp.parse("", "x"))
    }

    @Test
    fun `parses HTTP 1_0 status, NOTIFY, folded header lines and stops at blank line`() {
        val r = Ssdp.parse(
            "HTTP/1.0 200 OK\r\nSERVER: Linux UPnP/1.0\r\n  Renderer/2\r\nST:roku:ecp\r\nsans-deux-points\r\n\r\nST: ignored\r\n",
            "10.0.0.9",
        )!!
        assertEquals("Linux UPnP/1.0 Renderer/2", r.server)
        assertEquals("roku:ecp", r.searchTarget)
        assertEquals(setOf("SERVER", "ST"), r.headers.keys)
        assertNull(Ssdp.parse("HTTP/1.1 404 Not Found\r\n\r\n", "x"))
        val notify = Ssdp.parse("NOTIFY * HTTP/1.1\nNT: upnp:rootdevice\nUSN: uuid:n\n\n", "10.0.0.9")!!
        assertEquals("uuid:n", notify.usn)
    }

    @Test
    fun `mx is clamped to UDA range`() {
        assertTrue(Ssdp.searchMessage("ssdp:all", 0).contains("MX: 1\r\n"))
        assertTrue(Ssdp.searchMessage("ssdp:all", 120).contains("MX: 5\r\n"))
    }

    @Test
    fun `location must point to the responding host`() {
        fun resp(location: String, source: String) = SsdpResponse(mapOf("LOCATION" to location), source)
        assertTrue(resp("http://10.0.0.9:49152/desc.xml", "10.0.0.9").hasTrustedLocation)
        assertFalse(resp("http://10.0.0.66:49152/desc.xml", "10.0.0.9").hasTrustedLocation)
        assertFalse(resp("http://tracker.example.com/desc.xml", "10.0.0.9").hasTrustedLocation)
        assertFalse(resp("file:///etc/passwd", "10.0.0.9").hasTrustedLocation)
        assertFalse(resp("http://[fe80::2]:8060/", "fe80::1").hasTrustedLocation)
        assertFalse(resp("http://10.0.0.9/", "fe80::1").hasTrustedLocation)
        assertFalse(SsdpResponse(emptyMap(), "10.0.0.9").hasTrustedLocation)
        assertTrue(resp("http://[fe80::1]:8060/", "fe80:0:0:0:0:0:0:1%wlan0").hasTrustedLocation)
    }

    private fun reply(usn: String, location: String) =
        "HTTP/1.1 200 OK\r\nLOCATION: $location\r\nST: ${Ssdp.ST_MEDIA_RENDERER}\r\nUSN: $usn\r\n\r\n"

    @Test
    fun `search deduplicates by USN and ignores untrusted or unreadable replies`() = runTest {
        val time = TestTimeSource()
        val socket = SsdpFakeSocket(
            time,
            listOf(
                ssdpDatagram(reply("uuid:tv", "http://10.0.0.9:1400/a.xml"), "10.0.0.9"),
                ssdpDatagram("\u0000\u0001 n'importe quoi", "10.0.0.7"),
                null,
                ssdpDatagram(reply("uuid:pirate", "http://203.0.113.5/d.xml"), "10.0.0.7"),
                ssdpDatagram(reply("uuid:sans-source", "http://10.0.0.8/d.xml"), null),
                ssdpDatagram(reply("uuid:tv", "http://10.0.0.9:1400/b.xml"), "10.0.0.9"),
                ssdpDatagram("HTTP/1.1 200 OK\r\nLOCATION: http://10.0.0.6/c.xml\r\n\r\n", "10.0.0.6"),
            ),
        )
        val found = Ssdp.search(Ssdp.ST_MEDIA_RENDERER, timeoutMs = 3_000, sockets = socket, timeSource = time)
        assertEquals(listOf("uuid:tv", null), found.map { it.usn })
        // Le doublon le plus récent l'emporte ; sans USN, la clé est LOCATION.
        assertEquals(listOf("http://10.0.0.9:1400/b.xml", "http://10.0.0.6/c.xml"), found.map { it.location })
        val group = UdpDestination(Ssdp.MULTICAST_ADDRESS, Ssdp.PORT)
        assertEquals(listOf(group, group), socket.sent.map { it.first })
        assertTrue(socket.sent.first().second.contains("MX: 2\r\n"))
        assertEquals(Ssdp.MULTICAST_TTL, socket.options.single().multicastTtl)
        assertTrue(socket.closed)
    }

    @Test
    fun `search stops at the global deadline even with a continuous stream`() = runTest {
        val time = TestTimeSource()
        val socket = SsdpFakeSocket(time, endless = ssdpDatagram(reply("uuid:tv", "http://10.0.0.9/a.xml"), "10.0.0.9"))
        val found = Ssdp.search(Ssdp.ST_ROKU, timeoutMs = 2_000, sockets = socket, timeSource = time)
        assertEquals(1, found.size)
        assertEquals(20, socket.receives)
    }

    /** Fabrique rendant un socket par famille d'adresses, scénarisé selon sa première destination. */
    private class FamilySockets(
        private val time: TestTimeSource,
        private val v4: List<UdpDatagram?>,
        private val v6: List<UdpDatagram?>,
        private val v6Fails: Boolean = false,
    ) : UdpSocketFactory {
        val sent = CopyOnWriteArrayList<Pair<UdpDestination, String>>()

        override fun open(options: UdpOptions): UdpSocket = object : UdpSocket {
            private var script: ArrayDeque<UdpDatagram?>? = null

            override fun send(data: ByteArray, destination: UdpDestination) {
                val v6Destination = ':' in destination.host
                if (v6Destination && v6Fails) throw IOException("réseau IPv6 injoignable")
                sent += destination to String(data, Charsets.UTF_8)
                if (script == null) script = ArrayDeque(if (v6Destination) v6 else v4)
            }

            override fun receive(timeoutMs: Int): UdpDatagram? {
                val next = script?.removeFirstOrNull()
                synchronized(time) { time += if (next != null) 10.milliseconds else timeoutMs.milliseconds }
                return next
            }

            override fun close() = Unit
        }
    }

    @Test
    fun `ipv6 search uses the link-local group, prefers ipv4 and skips link-local locations`() = runTest {
        val time = TestTimeSource()
        val sockets = FamilySockets(
            time,
            v4 = listOf(ssdpDatagram(reply("uuid:tv", "http://10.0.0.9:1400/a.xml"), "10.0.0.9")),
            v6 = listOf(
                ssdpDatagram(reply("uuid:tv", "http://[fd00::9]:1400/a.xml"), "fd00::9"),
                ssdpDatagram(reply("uuid:v6", "http://[fd00::5]:1400/d.xml"), "fd00:0:0:0:0:0:0:5"),
                ssdpDatagram(reply("uuid:ll", "http://[fe80::7]:1400/d.xml"), "fe80::7%wlan0"),
            ),
        )
        val found = Ssdp.search(Ssdp.ST_MEDIA_RENDERER, timeoutMs = 2_000, sockets = sockets, timeSource = time, ipv6 = true)
        assertEquals(listOf("http://10.0.0.9:1400/a.xml", "http://[fd00::5]:1400/d.xml"), found.map { it.location })
        val v6Messages = sockets.sent.filter { it.first.host == Ssdp.MULTICAST_ADDRESS_V6 }
        assertEquals(2, v6Messages.size)
        assertTrue(v6Messages.all { it.second.contains("HOST: [FF02::C]:1900\r\n") })
        assertTrue(sockets.sent.filter { it.first.host == Ssdp.MULTICAST_ADDRESS }.all { it.second.contains("HOST: 239.255.255.250:1900\r\n") })
    }

    @Test
    fun `ipv6 failure does not hide ipv4 results`() = runTest {
        val time = TestTimeSource()
        val sockets = FamilySockets(
            time,
            v4 = listOf(ssdpDatagram(reply("uuid:tv", "http://10.0.0.9:1400/a.xml"), "10.0.0.9")),
            v6 = emptyList(),
            v6Fails = true,
        )
        val found = Ssdp.search(Ssdp.ST_MEDIA_RENDERER, timeoutMs = 2_000, sockets = sockets, timeSource = time, ipv6 = true)
        assertEquals(listOf("uuid:tv"), found.map { it.usn })
        assertTrue(sockets.sent.none { ':' in it.first.host })
    }
}

/** Faux socket UDP rejouant des datagrammes ; chaque réception fait avancer [time]. */
private class SsdpFakeSocket(
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

private fun ssdpDatagram(text: String, source: String?) = UdpDatagram(text.toByteArray(Charsets.UTF_8), source)

/** Requête suivante, attendue au plus une seconde. */
