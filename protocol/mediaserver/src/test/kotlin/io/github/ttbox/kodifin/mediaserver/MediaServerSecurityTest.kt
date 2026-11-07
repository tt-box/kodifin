package io.github.ttbox.kodifin.mediaserver

import io.github.ttbox.kodifin.model.Device
import io.github.ttbox.kodifin.model.DeviceKind
import io.github.ttbox.kodifin.model.HttpStatusException
import io.github.ttbox.kodifin.model.RemoteKey
import io.github.ttbox.kodifin.model.UdpDestination
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.TestTimeSource

/** Concurrence des renouvellements de jeton et confiance accordée aux réponses de découverte. */
class MediaServerSecurityTest {
    private lateinit var server: MockWebServer
    private val authCount = AtomicInteger()
    private val requests = CopyOnWriteArrayList<RecordedRequest>()

    @Before
    fun setUp() {
        server = MockWebServer()
    }

    @After
    fun tearDown() = server.close()

    private fun start(handler: (RecordedRequest) -> MockResponse) {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                if (request.url.encodedPath == "/Users/AuthenticateByName") authCount.incrementAndGet()
                return handler(request)
            }
        }
        server.start()
    }

    private fun status(code: Int) = MockResponse.Builder().code(code).build()
    private fun ok(body: String) = MockResponse.Builder().body(body).build()
    private fun RecordedRequest.token() = headers["Authorization"]?.substringAfter("Token=\"", "")?.substringBefore('"')

    private fun controller(extras: Map<String, String>) = MediaServerController.create(
        Device("s", "Serveur", DeviceKind.JELLYFIN, server.hostName, server.port, extras),
        OkHttpClient(),
        "dev",
    )

    private val credentials = mapOf("token" to "vieux", "sessionId" to "tv", "username" to "jo", "password" to "pw")

    @Test
    fun `parallel 401 trigger a single authentication`() = runTest {
        // Les deux requêtes refusées partent avec l'ancien jeton avant toute réauthentification.
        val bothRefused = CountDownLatch(2)
        start { r ->
            when {
                r.url.encodedPath == "/Users/AuthenticateByName" -> ok("""{"AccessToken":"neuf","User":{"Id":"u1"}}""")
                r.token() == "vieux" -> {
                    bothRefused.countDown()
                    bothRefused.await(5, TimeUnit.SECONDS)
                    status(401)
                }
                else -> status(204)
            }
        }
        val c = controller(credentials)
        listOf(async { c.send(RemoteKey.UP) }, async { c.send(RemoteKey.DOWN) }).awaitAll()
        assertEquals(1, authCount.get())
        val retried = requests.filter { it.url.encodedPath == "/Sessions/tv/Command" && it.token() == "neuf" }
        assertEquals(2, retried.size)
        assertEquals("neuf", c.persistableExtras()["token"])
    }

    @Test
    fun `401 after a fresh authentication is an error, not a loop`() = runTest {
        start { r ->
            if (r.url.encodedPath == "/Users/AuthenticateByName") ok("""{"AccessToken":"neuf","User":{"Id":"u1"}}""") else status(401)
        }
        val e = expectFailure<HttpStatusException> { controller(credentials).send(RemoteKey.STOP) }
        assertEquals(401, e.code)
        assertEquals(1, authCount.get())
        assertEquals(3, requests.size) // commande, authentification, unique nouvelle tentative
    }

    @Test
    fun `session vanishing during a re-authentication is reselected`() = runTest {
        start { r ->
            when {
                r.url.encodedPath == "/Users/AuthenticateByName" -> ok("""{"AccessToken":"neuf","User":{"Id":"u1"}}""")
                r.token() == "vieux" -> status(401)
                r.url.encodedPath == "/Sessions" -> ok("""[{"Id":"tv2","SupportsRemoteControl":true}]""")
                r.url.encodedPath.startsWith("/Sessions/tv/") -> status(404)
                else -> status(204)
            }
        }
        val c = controller(credentials)
        c.send(RemoteKey.PLAY_PAUSE)
        assertEquals(
            listOf("/Sessions/tv/Playing/PlayPause", "/Users/AuthenticateByName", "/Sessions/tv/Playing/PlayPause", "/Sessions", "/Sessions/tv2/Playing/PlayPause"),
            requests.map { it.url.encodedPath },
        )
        assertEquals("u1", requests[3].url.queryParameter("ControllableByUserId"))
        assertNull(c.persistableExtras()["sessionId"])
    }

    @Test
    fun `second 404 after a reselection is surfaced`() = runTest {
        start { r -> if (r.url.encodedPath == "/Sessions") ok("""[{"Id":"tv","SupportsRemoteControl":true}]""") else status(404) }
        val e = expectFailure<HttpStatusException> { controller(mapOf("token" to "t", "sessionId" to "tv")).send(RemoteKey.UP) }
        assertEquals(404, e.code)
        assertEquals(3, requests.size)
    }

    @Test
    fun `https scheme announced at discovery is honoured`() {
        val device = Device("s", "S", DeviceKind.JELLYFIN, "10.0.0.2", 8920, mapOf(MediaServerDiscovery.EXTRA_SCHEME to "https"))
        assertEquals("https://10.0.0.2:8920", MediaServerController.baseUrl(device))
        assertEquals("http://10.0.0.2:8920", MediaServerController.baseUrl(device.copy(extras = emptyMap())))
        val v6 = Device("s", "S", DeviceKind.EMBY, "fd00::2", 8920, mapOf(MediaServerDiscovery.EXTRA_SCHEME to "https"))
        assertEquals("https://[fd00::2]:8920", MediaServerController.baseUrl(v6))
    }
}

class MediaServerDiscoveryTest {
    private fun parse(payload: String, source: String?) = MediaServerDiscovery.parseReply(payload, MediaServerFlavor.JELLYFIN, source)

    @Test
    fun `announced host differing from the sender is replaced by the sender, port kept`() {
        val d = parse("""{"Address":"http://203.0.113.7:8097","Id":"abc","Name":"Jelly"}""", "192.168.1.30")!!
        assertEquals("192.168.1.30", d.host)
        assertEquals(8097, d.port)
        assertEquals("JELLYFIN:abc", d.id)
        assertEquals("Jelly", d.name)
        assertEquals(emptyMap<String, String>(), d.extras)
        val byName = parse("""{"Address":"http://jellyfin.example.com","Id":"abc"}""", "192.168.1.30")!!
        assertEquals("192.168.1.30", byName.host)
        assertEquals(80, byName.port)
    }

    @Test
    fun `announced host equal to the sender is kept, including ipv6 spellings`() {
        assertEquals("192.168.1.30", parse("""{"Address":"http://192.168.1.30:8096","Id":"a"}""", "192.168.1.30")!!.host)
        val v6 = parse("""{"Address":"http://[fd00::1]:8096","Id":"a"}""", "fd00:0:0:0:0:0:0:1%wlan0")!!
        assertEquals("fd00::1", v6.host)
        assertEquals("http://[fd00::1]:8096", v6.baseUrl)
    }

    @Test
    fun `https announcement keeps its scheme and port`() {
        val d = parse("""{"Address":"https://192.168.1.30:8920","Id":"a"}""", "192.168.1.30")!!
        assertEquals(8920, d.port)
        assertEquals("https", d.extras[MediaServerDiscovery.EXTRA_SCHEME])
        assertEquals("https://192.168.1.30:8920", MediaServerController.baseUrl(d))
        val other = parse("""{"Address":"https://203.0.113.7:8920","Id":"a"}""", "192.168.1.30")!!
        assertEquals("192.168.1.30", other.host)
        assertEquals("https", other.extras[MediaServerDiscovery.EXTRA_SCHEME])
    }

    @Test
    fun `invalid replies are rejected`() {
        assertNull(parse("""{"Address":"http://192.168.1.30:8096","Id":""}""", "192.168.1.30"))
        assertNull(parse("""{"Address":"http://192.168.1.30:8096","Id":"   "}""", "192.168.1.30"))
        assertNull(parse("""{"Address":"http://192.168.1.30:8096","Id":"ab""", "192.168.1.30"))
        assertNull(parse("""{"Address":"http://192.168.1.30:8096"}""", "192.168.1.30"))
        assertNull(parse("", "192.168.1.30"))
        assertNull(parse("""{"Address":"http://127.0.0.1:8096","Id":"a"}""", null))
    }

    @Test
    fun `discover broadcasts the probe, deduplicates and ignores garbage`() = runTest {
        val time = TestTimeSource()
        val socket = FakeUdpSocket(
            time,
            listOf(
                datagram("""{"Address":"http://10.0.0.2:8096","Id":"a","Name":"Un"}""", "10.0.0.2"),
                datagram("pas du json", "10.0.0.3"),
                null,
                datagram("""{"Address":"http://10.0.0.2:8096","Id":"a","Name":"Un bis"}""", "10.0.0.2"),
                datagram("""{"Id":"b"}""", "10.0.0.4"),
            ),
        )
        val found = MediaServerDiscovery.discover(MediaServerFlavor.EMBY, timeoutMs = 1_000, sockets = socket, timeSource = time)
        assertEquals(listOf("EMBY:a" to "Un bis", "EMBY:b" to "Emby"), found.map { it.id to it.name })
        assertEquals("10.0.0.4", found[1].host)
        assertEquals(listOf(UdpDestination("255.255.255.255", MediaServerDiscovery.PORT) to "who is EmbyServer?"), socket.sent)
        assertTrue(socket.options.single().broadcast)
        assertTrue(socket.closed)
    }

    @Test
    fun `discover honours the global deadline with a continuous stream`() = runTest {
        val time = TestTimeSource()
        val socket = FakeUdpSocket(time, endless = datagram("""{"Id":"a"}""", "10.0.0.2"))
        val found = MediaServerDiscovery.discover(MediaServerFlavor.JELLYFIN, timeoutMs = 500, sockets = socket, timeSource = time)
        assertEquals(1, found.size)
        assertEquals(5, socket.receives)
    }
}
