package io.github.jmorille.zapette.airplay

import io.github.jmorille.zapette.model.Device
import io.github.jmorille.zapette.model.DeviceKind
import io.github.jmorille.zapette.model.RemoteKey
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class AirPlayTest {
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        repeat(5) { server.enqueue(MockResponse.Builder().build()) }
        server.start()
    }

    @After
    fun tearDown() = server.close()

    private fun controller() =
        AirPlayController(Device("a", "Apple TV", DeviceKind.AIRPLAY, server.hostName, server.port), OkHttpClient())

    @Test
    fun `playUrl posts text parameters with session id`() = runTest {
        controller().playUrl("http://nas/film.mp4")
        val request = server.takeRequest()
        assertEquals("/play", request.url.encodedPath)
        assertTrue(request.headers["Content-Type"]!!.startsWith("text/parameters"))
        assertTrue(request.body!!.utf8().contains("Content-Location: http://nas/film.mp4\n"))
        assertTrue(request.headers["X-Apple-Session-ID"]!!.isNotBlank())
    }

    @Test
    fun `play pause toggles rate`() = runTest {
        val c = controller()
        c.playUrl("http://nas/film.mp4")
        c.send(RemoteKey.PLAY_PAUSE)
        c.send(RemoteKey.PLAY_PAUSE)
        assertEquals("/play", server.takeRequest().url.encodedPath)
        // /playback-info vide : repli sur l'état local.
        assertEquals("/playback-info", server.takeRequest().url.encodedPath)
        assertEquals("value=0.000000", server.takeRequest().url.encodedQuery)
        assertEquals("/playback-info", server.takeRequest().url.encodedPath)
        assertEquals("value=1.000000", server.takeRequest().url.encodedQuery)
    }

    @Test
    fun `play pause follows the receiver rate when available`() = runTest {
        server.close()
        server = MockWebServer()
        server.enqueue(MockResponse.Builder().body(PLAYING_PLIST).build())
        server.enqueue(MockResponse.Builder().build())
        server.start()
        // Lecture lancée depuis un autre appareil : l'état local est « arrêté » mais le récepteur lit.
        controller().send(RemoteKey.PLAY_PAUSE)
        server.takeRequest()
        assertEquals("value=0.000000", server.takeRequest().url.encodedQuery)
    }

    @Test
    fun `playUrl rejects line breaks that would inject parameters`() = runTest {
        try {
            controller().playUrl("http://nas/a.mp4\nStart-Position: 0.9")
            fail("URL acceptée")
        } catch (_: IllegalArgumentException) {
        }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `negative seek is clamped`() = runTest {
        controller().seekTo(-5_000)
        assertEquals("position=0.000", server.takeRequest().url.encodedQuery)
    }

    @Test
    fun `playback rate parsing`() {
        assertEquals(1.0, AirPlayController.parsePlaybackRate(PLAYING_PLIST)!!, 0.0)
        assertEquals(0.0, AirPlayController.parsePlaybackRate("<dict><key>rate</key><integer>0</integer></dict>")!!, 0.0)
        assertNull(AirPlayController.parsePlaybackRate(""))
    }

    @Test
    fun `seek uses seconds with dot decimal`() = runTest {
        controller().seekTo(90_500)
        assertEquals("position=90.500", server.takeRequest().url.encodedQuery)
    }

    @Test
    fun `scrub response parsing`() {
        assertEquals(12_000L to 123_400L, AirPlayController.parseScrub("duration: 123.4\nposition: 12.0\n"))
        assertEquals(0L to 0L, AirPlayController.parseScrub("Duration: NaN\nposition: -3\n"))
    }

    private companion object {
        const val PLAYING_PLIST = """<?xml version="1.0" encoding="UTF-8"?><plist version="1.0"><dict>
            <key>duration</key><real>120.0</real><key>rate</key><real>1.000000</real></dict></plist>"""
    }
}
