package io.github.jmorille.zapette.plex

import io.github.jmorille.zapette.model.Device
import io.github.jmorille.zapette.model.DeviceKind
import io.github.jmorille.zapette.model.RemoteKey
import io.github.jmorille.zapette.model.UdpDestination
import kotlinx.coroutines.test.runTest
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.TestTimeSource

/** Jeton Plex : vérifié sur l'en-tête réellement émis, pour l'hôte effectif de l'URL appelée. */
class PlexTokenTest {
    private val identity = PlexIdentity("zapette-test")

    /** Intercepteur d'application : capture la requête finale sans toucher au réseau ni au DNS. */
    private class Capture : Interceptor {
        val requests = CopyOnWriteArrayList<Request>()

        override fun intercept(chain: Interceptor.Chain): Response {
            requests += chain.request()
            return Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body("".toResponseBody())
                .build()
        }
    }

    private suspend fun sentToken(host: String): Pair<String, String?> {
        val capture = Capture()
        val http = OkHttpClient.Builder().addInterceptor(capture).build()
        val device = Device("PLEX:abc", "Lecteur", DeviceKind.PLEX, host, 32500, mapOf("token" to "secret", "clientIdentifier" to "abc"))
        PlexController(device, http, identity).send(RemoteKey.SELECT)
        val request = capture.requests.single()
        return request.url.host to request.header("X-Plex-Token")
    }

    @Test
    fun `token is never sent to a public or ambiguous host`() = runTest {
        val refused = listOf(
            "plex.example.com", "8.8.8.8", "2001:db8::1", "0.0.0.0", "::", "::ffff:8.8.8.8",
            "134744072", "0x08080808", "8.8.2056", "010.0.0.1", "evil.local.", "100.64.1.2", "salon.example",
            "192.168.1.5.evil.com", "10.0.0.8.nip.io",
        )
        for (host in refused) {
            val (effective, token) = sentToken(host)
            assertNull("jeton envoyé à $host (hôte effectif $effective)", token)
        }
    }

    @Test
    fun `token is sent to private, link-local, unique-local and local names`() = runTest {
        val allowed = listOf(
            "192.168.1.5", "10.0.0.8", "172.16.3.4", "172.31.255.1", "127.0.0.1", "fe80::1", "fd00::5", "fd12:3456::1",
            "::ffff:192.168.1.5", "htpc", "salon.local", "box.lan", "nas.home.arpa", "localhost",
        )
        for (host in allowed) {
            val (effective, token) = sentToken(host)
            assertEquals("jeton attendu pour $host (hôte effectif $effective)", "secret", token)
        }
    }

    @Test
    fun `boundary addresses outside private ranges are refused`() {
        listOf("172.15.0.1", "172.32.0.1", "192.169.0.1", "11.0.0.1", "2a00::1", "", "[]", "a..b", "-x")
            .forEach { assertFalse("« $it » ne doit pas être local", PlexController.isLocalHost(it)) }
        assertTrue(PlexController.isLocalHost("[fd12::5]"))
        assertTrue(PlexController.isLocalHost("HTPC"))
    }
}

class PlexGdmTest {
    private val reply = "HTTP/1.0 200 OK\r\nContent-Type: plex/media-player\r\nResource-Identifier: %s\r\nName: %s\r\nPort: 32500\r\n"

    @Test
    fun `discover survives a failing destination, deduplicates and skips non players`() = runTest {
        val time = TestTimeSource()
        val socket = FakeUdpSocket(
            time,
            listOf(
                datagram(reply.format("p1", "Salon"), "10.0.0.8"),
                datagram(reply.format("srv", "NAS").replace("media-player", "media-server"), "10.0.0.9"),
                datagram("\u0000illisible", "10.0.0.10"),
                null,
                datagram(reply.format("p2", "Sans source"), null),
                datagram(reply.format("p1", "Salon renommé"), "10.0.0.8"),
            ),
            failingHosts = setOf(PlexController.GDM_ADDRESS),
        )
        val found = PlexController.discover(timeoutMs = 1_000, sockets = socket, timeSource = time)
        assertEquals(listOf("PLEX:p1"), found.map { it.id })
        assertEquals("Salon renommé", found.single().name)
        assertEquals("10.0.0.8", found.single().host)
        assertEquals(
            listOf(UdpDestination("255.255.255.255", PlexController.GDM_PLAYER_PORT) to "M-SEARCH * HTTP/1.0\r\n\r\n"),
            socket.sent,
        )
        assertTrue(socket.options.single().broadcast)
        assertTrue(socket.closed)
    }

    @Test
    fun `discover honours the global deadline with a continuous stream`() = runTest {
        val time = TestTimeSource()
        val socket = FakeUdpSocket(time, endless = datagram(reply.format("p1", "Salon"), "10.0.0.8"))
        assertEquals(1, PlexController.discover(timeoutMs = 300, sockets = socket, timeSource = time).size)
        assertEquals(3, socket.receives)
        assertEquals(2, socket.sent.size)
    }
}
