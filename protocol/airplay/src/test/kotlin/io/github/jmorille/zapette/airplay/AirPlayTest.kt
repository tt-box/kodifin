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
import org.junit.Assert.assertTrue
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
        server.takeRequest()
        assertEquals("value=0.000000", server.takeRequest().url.encodedQuery)
        assertEquals("value=1.000000", server.takeRequest().url.encodedQuery)
    }

    @Test
    fun `seek uses seconds with dot decimal`() = runTest {
        controller().seekTo(90_500)
        assertEquals("position=90.500", server.takeRequest().url.encodedQuery)
    }

    @Test
    fun `scrub response parsing`() {
        assertEquals(12_000L to 123_400L, AirPlayController.parseScrub("duration: 123.4\nposition: 12.0\n"))
    }
}
