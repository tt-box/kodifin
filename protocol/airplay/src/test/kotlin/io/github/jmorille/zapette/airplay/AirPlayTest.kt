package io.github.jmorille.zapette.airplay

import io.github.jmorille.zapette.model.Device
import io.github.jmorille.zapette.model.DeviceKind
import io.github.jmorille.zapette.model.HttpStatusException
import io.github.jmorille.zapette.model.PlaybackState
import io.github.jmorille.zapette.model.RemoteException
import io.github.jmorille.zapette.model.RemoteKey
import io.github.jmorille.zapette.model.UnsupportedActionException
import io.github.jmorille.zapette.testing.assertFailsWithMessage
import io.github.jmorille.zapette.testing.awaitRequest
import io.github.jmorille.zapette.testing.release
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.SocketEffect
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AirPlayTest {
    private lateinit var server: MockWebServer

    // Pas de nouvel essai automatique : chaque erreur simulée consomme exactement une réponse.
    private val http = OkHttpClient.Builder().retryOnConnectionFailure(false).build()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.close()
        http.release()
    }

    private fun ok(times: Int = 1) = repeat(times) { server.enqueue(MockResponse.Builder().build()) }

    private fun controller(pollIntervalMs: Long = 0) =
        AirPlayController(Device("a", "Apple TV", DeviceKind.AIRPLAY, server.hostName, server.port), http, pollIntervalMs)

    @Test
    fun `playUrl posts text parameters with session id`() = runTest {
        ok()
        controller().playUrl("http://nas/film.mp4")
        val request = server.awaitRequest()
        assertEquals("POST", request.method)
        assertEquals("/play", request.url.encodedPath)
        assertTrue(request.headers["Content-Type"]!!.startsWith("text/parameters"))
        assertEquals("MediaControl/1.0", request.headers["User-Agent"])
        assertEquals("Content-Location: http://nas/film.mp4\nStart-Position: 0.000000\n", request.body!!.utf8())
        assertTrue(request.headers["X-Apple-Session-ID"]!!.isNotBlank())
    }

    @Test
    fun `session id is stable across requests and distinct per controller`() = runTest {
        ok(4)
        val c = controller()
        c.playUrl("http://nas/film.mp4")
        c.seekTo(1_000)
        c.send(RemoteKey.STOP)
        controller().send(RemoteKey.STOP)
        val ids = List(4) { server.awaitRequest().headers["X-Apple-Session-ID"] }
        assertEquals(1, ids.take(3).toSet().size, "identifiant de session variable : $ids")
        assertNotEquals(ids[0], ids[3])
    }

    @Test
    fun `play pause toggles rate`() = runTest {
        ok(5)
        val c = controller()
        c.playUrl("http://nas/film.mp4")
        c.send(RemoteKey.PLAY_PAUSE)
        c.send(RemoteKey.PLAY_PAUSE)
        assertEquals("/play", server.awaitRequest().url.encodedPath)
        // /playback-info vide : repli sur l'état local.
        assertEquals("/playback-info", server.awaitRequest().url.encodedPath)
        assertEquals("value=0.000000", server.awaitRequest().url.encodedQuery)
        assertEquals("/playback-info", server.awaitRequest().url.encodedPath)
        assertEquals("value=1.000000", server.awaitRequest().url.encodedQuery)
    }

    @Test
    fun `play pause falls back to local state when playback-info fails`() = runTest {
        server.enqueue(MockResponse.Builder().code(500).build())
        ok()
        controller().send(RemoteKey.PLAY_PAUSE)
        assertEquals("/playback-info", server.awaitRequest().url.encodedPath)
        // État local « arrêté » : la touche relance la lecture.
        assertEquals("value=1.000000", server.awaitRequest().url.encodedQuery)
    }

    @Test
    fun `play pause follows the receiver rate when available`() = runTest {
        server.enqueue(MockResponse.Builder().body(PLAYING_PLIST).build())
        ok()
        // Lecture lancée depuis un autre appareil : l'état local est « arrêté » mais le récepteur lit.
        controller().send(RemoteKey.PLAY_PAUSE)
        assertEquals("/playback-info", server.awaitRequest().url.encodedPath)
        assertEquals("value=0.000000", server.awaitRequest().url.encodedQuery)
    }

    @Test
    fun `stop posts to stop and unsupported keys are rejected`() = runTest {
        ok()
        val c = controller()
        c.send(RemoteKey.STOP)
        val request = server.awaitRequest()
        assertEquals("POST", request.method)
        assertEquals("/stop", request.url.encodedPath)
        assertFailsWithMessage<UnsupportedActionException>("HOME") { c.send(RemoteKey.HOME) }
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `playUrl rejects line breaks and control characters that would inject parameters`() = runTest {
        val forbidden = listOf('\r', '\n', '\u0085', ' ', ' ', '\u0000')
        forbidden.forEach { c ->
            assertFailsWithMessage<IllegalArgumentException>("URL invalide") {
                controller().playUrl("http://nas/a.mp4${c}Start-Position: 0.9")
            }
        }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `negative seek is clamped`() = runTest {
        ok()
        controller().seekTo(-5_000)
        assertEquals("position=0.000", server.awaitRequest().url.encodedQuery)
    }

    @Test
    fun `seek uses seconds with dot decimal`() = runTest {
        ok()
        controller().seekTo(90_500)
        val request = server.awaitRequest()
        assertEquals("/scrub", request.url.encodedPath)
        assertEquals("position=90.500", request.url.encodedQuery)
    }

    @Test
    fun `connect probes server-info and explains failures`() = runTest {
        ok()
        server.enqueue(MockResponse.Builder().code(403).build())
        controller().connect()
        assertEquals("/server-info", server.awaitRequest().url.encodedPath)
        val e = assertFailsWithMessage<RemoteException>("AirPlay 2") { controller().connect() }
        assertIs<HttpStatusException>(e.cause)
    }

    @Test
    fun `playback state survives transport errors`() = runBlocking {
        // Coupure de connexion, puis erreur HTTP, puis réponse valide : la boucle continue.
        server.enqueue(MockResponse.Builder().onResponseStart(SocketEffect.ShutdownConnection).build())
        server.enqueue(MockResponse.Builder().code(500).build())
        server.enqueue(MockResponse.Builder().body("duration: 120.0\nposition: 30.5\n").build())
        val state = withTimeout(5_000) { controller(pollIntervalMs = 0).playbackState().first() }
        assertEquals(PlaybackState(isPlaying = false, positionMs = 30_500, durationMs = 120_000), state)
        assertEquals(3, server.requestCount)
        repeat(3) { assertEquals("/scrub", server.awaitRequest().url.encodedPath) }
    }

    @Test
    fun `playback state reflects the last command`() = runBlocking {
        ok()
        server.enqueue(MockResponse.Builder().body("duration: 10\nposition: 1\n").build())
        val c = controller()
        c.playUrl("http://nas/film.mp4")
        assertTrue(withTimeout(5_000) { c.playbackState().first() }.isPlaying)
    }

    @Test
    fun `cancelling the playback flow cancels the pending request`() = runBlocking {
        val first = CompletableDeferred<PlaybackState>()
        server.enqueue(MockResponse.Builder().body("duration: 10\nposition: 1\n").build())
        // Deuxième sondage retenu par le serveur : la collecte est suspendue dans l'appel HTTP.
        server.enqueue(MockResponse.Builder().headersDelay(30, java.util.concurrent.TimeUnit.SECONDS).build())
        val job = launch(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
            controller(pollIntervalMs = 0).playbackState().collect { first.complete(it) }
        }
        withTimeout(5_000) { first.await() }
        server.awaitRequest()
        server.awaitRequest() // le second sondage est bien en cours
        job.cancel()
        withTimeout(2_000) { job.join() }
        assertTrue(job.isCancelled)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `playback rate parsing`() {
        assertEquals(1.0, AirPlayController.parsePlaybackRate(PLAYING_PLIST)!!, 0.0)
        assertEquals(0.0, AirPlayController.parsePlaybackRate("<dict><key>rate</key><integer>0</integer></dict>")!!, 0.0)
        assertNull(AirPlayController.parsePlaybackRate(""))
        assertNull(AirPlayController.parsePlaybackRate("<dict><key>rate</key><string>x</string></dict>"))
    }

    @Test
    fun `scrub response parsing`() {
        assertEquals(12_000L to 123_400L, AirPlayController.parseScrub("duration: 123.4\nposition: 12.0\n"))
        assertEquals(0L to 0L, AirPlayController.parseScrub("Duration: NaN\nposition: -3\n"))
        assertEquals(0L to 0L, AirPlayController.parseScrub("garbage\nduration: inf\n"))
    }

    private companion object {
        const val PLAYING_PLIST = """<?xml version="1.0" encoding="UTF-8"?><plist version="1.0"><dict>
            <key>duration</key><real>120.0</real><key>rate</key><real>1.000000</real></dict></plist>"""
    }
}
