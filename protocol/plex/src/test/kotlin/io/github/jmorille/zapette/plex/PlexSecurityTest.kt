package io.github.jmorille.zapette.plex

import io.github.jmorille.zapette.model.Device
import io.github.jmorille.zapette.model.DeviceKind
import io.github.jmorille.zapette.model.RemoteKey
import io.github.jmorille.zapette.model.UdpDestination
import kotlinx.coroutines.test.runTest
import okhttp3.Dns
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
import java.net.InetAddress
import java.net.UnknownHostException
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

    /** Résolution simulée des noms courts (aucun accès DNS réel). */
    private val fakeDns = Dns { name ->
        when (name) {
            "htpc" -> listOf(InetAddress.getByName("192.168.1.20"))
            "dual" -> listOf(InetAddress.getByName("fd00::7"), InetAddress.getByName("10.1.2.3"))
            "public" -> listOf(InetAddress.getByName("8.8.8.8"))
            "mixed" -> listOf(InetAddress.getByName("192.168.1.20"), InetAddress.getByName("8.8.4.4"))
            "empty" -> emptyList()
            else -> throw UnknownHostException(name)
        }
    }

    private suspend fun sentToken(host: String): Pair<String, String?> {
        val capture = Capture()
        val http = OkHttpClient.Builder().addInterceptor(capture).build()
        val device = Device("PLEX:abc", "Lecteur", DeviceKind.PLEX, host, 32500, mapOf("token" to "secret", "clientIdentifier" to "abc"))
        PlexController(device, http, identity, fakeDns).send(RemoteKey.SELECT)
        val request = capture.requests.single()
        return request.url.host to request.header("X-Plex-Token")
    }

    @Test
    fun `token is never sent to a public or ambiguous host`() = runTest {
        val refused = listOf(
            "plex.example.com", "8.8.8.8", "2001:db8::1", "0.0.0.0", "::", "::ffff:8.8.8.8",
            "134744072", "0x08080808", "8.8.2056", "010.0.0.1", "evil.local.", "100.64.1.2", "salon.example",
            "192.168.1.5.evil.com", "10.0.0.8.nip.io",
            // Noms courts : non résolus, résolus vers une IP publique, partiellement publics ou sans adresse.
            "inconnu", "public", "mixed", "empty",
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
            "::ffff:192.168.1.5", "htpc", "dual", "salon.local", "box.lan", "nas.home.arpa", "localhost",
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
        // Nom court : local seulement s'il résout vers une adresse privée.
        assertFalse(PlexController.isLocalHost("HTPC"))
        assertTrue(PlexController.isLocalHost("HTPC") { listOf(InetAddress.getByName("10.0.0.2")) })
        assertFalse(PlexController.isLocalHost("HTPC") { listOf(InetAddress.getByName("1.1.1.1")) })
        assertFalse(PlexController.isLocalHost("HTPC") { throw UnknownHostException(it) })
        // Les noms qualifiés ne sont jamais résolus (seuls les suffixes locaux sont admis).
        assertFalse(PlexController.isLocalHost("nas.example.org") { listOf(InetAddress.getByName("10.0.0.2")) })
    }

    @Test
    fun `identity headers include device name and standard X-Plex headers`() {
        val request = PlexIdentity("id-1", deviceName = "Téléphone de Jérôme", device = "Pixel 9", platformVersion = "16")
            .apply(Request.Builder().url("http://10.0.0.1:32500/"))
            .build()
        assertEquals("Telephone de Jerome", request.header("X-Plex-Device-Name"))
        assertEquals("Pixel 9", request.header("X-Plex-Device"))
        assertEquals("Pixel 9", request.header("X-Plex-Model"))
        assertEquals("16", request.header("X-Plex-Platform-Version"))
        assertEquals("Android", request.header("X-Plex-Platform"))
        assertEquals("controller", request.header("X-Plex-Provides"))
        assertEquals("id-1", request.header("X-Plex-Client-Identifier"))
        assertEquals("Zapette", request.header("X-Plex-Product"))
        // Valeurs absentes ou vides : en-têtes omis ; caractères non ASCII remplacés.
        val bare = PlexIdentity("id-2", deviceName = " ").apply(Request.Builder().url("http://10.0.0.1/")).build()
        assertNull(bare.header("X-Plex-Device-Name"))
        assertNull(bare.header("X-Plex-Device"))
        assertEquals("Salon _ TV", PlexIdentity.headerValue("Salon 📺 TV").replace("__", "_"))
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
