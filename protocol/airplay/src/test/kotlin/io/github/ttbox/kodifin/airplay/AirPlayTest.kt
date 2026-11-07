package io.github.ttbox.kodifin.airplay

import io.github.ttbox.kodifin.model.Device
import io.github.ttbox.kodifin.model.DeviceKind
import io.github.ttbox.kodifin.model.HttpStatusException
import io.github.ttbox.kodifin.model.PlaybackState
import io.github.ttbox.kodifin.model.RemoteError
import io.github.ttbox.kodifin.model.RemoteKey
import io.github.ttbox.kodifin.model.Setup
import io.github.ttbox.kodifin.model.UnsupportedAction
import io.github.ttbox.kodifin.testing.assertFailsWithMessage
import io.github.ttbox.kodifin.testing.assertFailsWithReason
import io.github.ttbox.kodifin.testing.assertUnsupported
import io.github.ttbox.kodifin.testing.awaitRequest
import io.github.ttbox.kodifin.testing.release
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
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
        val unsupported = assertUnsupported(UnsupportedAction.Key(RemoteKey.HOME)) { c.send(RemoteKey.HOME) }
        assertEquals(DeviceKind.AIRPLAY, unsupported.kind)
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
        val e = assertFailsWithReason(RemoteError.SetupRequired(Setup.AIRPLAY_1_REQUIRED)) { controller().connect() }
        assertIs<HttpStatusException>(e.cause)
    }

    @Test
    fun `playback state survives transport errors`() = runBlocking {
        // Coupure de connexion puis erreur HTTP (repli /scrub en échec), puis plist valide : la boucle continue.
        server.enqueue(MockResponse.Builder().onResponseStart(SocketEffect.ShutdownConnection).build())
        server.enqueue(MockResponse.Builder().code(500).build())
        server.enqueue(MockResponse.Builder().body(PLAYING_PLIST).build())
        val state = withTimeout(5_000) { controller(pollIntervalMs = 0).playbackState().first() }
        assertEquals(PlaybackState(isPlaying = true, positionMs = 30_500, durationMs = 120_000), state)
        assertEquals(3, server.requestCount)
        assertEquals("/playback-info", server.awaitRequest().url.encodedPath)
        assertEquals("/scrub", server.awaitRequest().url.encodedPath)
        assertEquals("/playback-info", server.awaitRequest().url.encodedPath)
    }

    @Test
    fun `playback state reads the real rate rather than the last command`() = runBlocking {
        ok()
        // Lecture lancée puis mise en pause depuis un autre appareil : le récepteur fait foi.
        server.enqueue(MockResponse.Builder().body(PLAYING_PLIST.replace("<real>1.000000</real>", "<real>0.000000</real>")).build())
        val c = controller()
        c.playUrl("http://nas/film.mp4")
        val state = withTimeout(5_000) { c.playbackState().first() }
        assertEquals(PlaybackState(isPlaying = false, positionMs = 30_500, durationMs = 120_000), state)
    }

    @Test
    fun `playback state falls back to scrub when playback-info is not implemented`() = runBlocking {
        ok()
        server.enqueue(MockResponse.Builder().code(404).build())
        server.enqueue(MockResponse.Builder().body("duration: 10\nposition: 1\n").build())
        server.enqueue(MockResponse.Builder().body("duration: 10\nposition: 2\n").build())
        val c = controller()
        c.playUrl("http://nas/film.mp4")
        val states = withTimeout(5_000) { c.playbackState().take(2).toList() }
        assertEquals(PlaybackState(isPlaying = true, positionMs = 1_000, durationMs = 10_000), states[0])
        assertEquals(2_000, states[1].positionMs)
        server.awaitRequest()
        assertEquals("/playback-info", server.awaitRequest().url.encodedPath)
        // 404 mémorisé : /playback-info n'est plus interrogé.
        assertEquals("/scrub", server.awaitRequest().url.encodedPath)
        assertEquals("/scrub", server.awaitRequest().url.encodedPath)
    }

    @Test
    fun `nothing loaded is reported as stopped without scrub`() = runBlocking {
        server.enqueue(MockResponse.Builder().body("<plist><dict><key>readyToPlay</key><false/></dict></plist>").build())
        val state = withTimeout(5_000) { controller(pollIntervalMs = 0).playbackState().first() }
        assertEquals(PlaybackState(), state)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `cancelling the playback flow cancels the pending request`() = runBlocking {
        val first = CompletableDeferred<PlaybackState>()
        server.enqueue(MockResponse.Builder().body(PLAYING_PLIST).build())
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

    @Test
    fun `plist parsing ignores nested time ranges`() {
        val values = AirPlayController.parsePlist(PLAYING_PLIST)
        assertEquals("120.0", values["duration"])
        assertEquals("30.5", values["position"])
        assertEquals("true", values["readyToPlay"])
        assertNull(values["start"])
    }

    @Test
    fun `device and session headers are sent`() = runTest {
        ok()
        AirPlayController(Device("a", "Apple TV", DeviceKind.AIRPLAY, server.hostName, server.port), http, 0, "0x0123456789ab")
            .send(RemoteKey.STOP)
        val request = server.awaitRequest()
        assertEquals("0x0123456789ab", request.headers["X-Apple-Device-ID"])
        assertTrue(Regex("[0-9a-f-]{36}").matches(request.headers["X-Apple-Session-ID"]!!))
        assertTrue(Regex("0x[0-9a-f]{12}").matches(AirPlayController.PROCESS_DEVICE_ID))
    }

    @Test
    fun `invalid address is a remote exception`() = runTest {
        val c = AirPlayController(Device("a", "Apple TV", DeviceKind.AIRPLAY, "hôte invalide", 7000), http)
        assertFailsWithReason(RemoteError.InvalidAddress("hôte invalide:7000")) { c.send(RemoteKey.STOP) }
    }

    private companion object {
        const val PLAYING_PLIST = """<?xml version="1.0" encoding="UTF-8"?>
            <!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
            <plist version="1.0"><dict>
            <key>duration</key><real>120.0</real>
            <key>loadedTimeRanges</key><array><dict><key>duration</key><real>5.0</real><key>start</key><real>1.0</real></dict></array>
            <key>playbackBufferEmpty</key><true/>
            <key>position</key><real>30.5</real>
            <key>rate</key><real>1.000000</real>
            <key>readyToPlay</key><true/>
            <key>seekableTimeRanges</key><array><dict><key>duration</key><real>120.0</real><key>start</key><real>0.0</real></dict></array>
            </dict></plist>"""
    }
}
