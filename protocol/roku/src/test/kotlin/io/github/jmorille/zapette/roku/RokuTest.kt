package io.github.jmorille.zapette.roku

import io.github.jmorille.zapette.model.Device
import io.github.jmorille.zapette.model.DeviceKind
import io.github.jmorille.zapette.model.RemoteKey
import io.github.jmorille.zapette.upnp.SsdpResponse
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class RokuTest {
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        repeat(10) { server.enqueue(MockResponse.Builder().build()) }
        server.start()
    }

    @After
    fun tearDown() = server.close()

    private fun controller() =
        RokuController(Device("r", "Roku", DeviceKind.ROKU, server.hostName, server.port), OkHttpClient())

    @Test
    fun `keys are posted to keypress endpoint`() = runTest {
        controller().send(RemoteKey.HOME)
        controller().send(RemoteKey.FAST_FORWARD)
        val first = server.takeRequest()
        assertEquals("POST", first.method)
        assertEquals("/keypress/Home", first.url.encodedPath)
        assertEquals("/keypress/Fwd", server.takeRequest().url.encodedPath)
    }

    @Test
    fun `text is sent as literal keypresses and url-encoded`() = runTest {
        controller().sendText("a b")
        assertEquals("/keypress/Lit_a", server.takeRequest().url.encodedPath)
        assertEquals("/keypress/Lit_%20", server.takeRequest().url.encodedPath)
        assertEquals("/keypress/Lit_b", server.takeRequest().url.encodedPath)
    }

    @Test
    fun `playUrl uses Play on Roku channel`() = runTest {
        controller().playUrl("http://nas/song.mp3", "Chanson")
        val request = server.takeRequest()
        assertEquals("/input/15985", request.url.encodedPath)
        assertEquals("a", request.url.queryParameter("t"))
        assertEquals("http://nas/song.mp3", request.url.queryParameter("u"))
        assertEquals("Chanson", request.url.queryParameter("videoName"))
    }

    @Test
    fun `device is built from ssdp roku response`() {
        val ok = SsdpResponse(mapOf("ST" to "roku:ecp", "LOCATION" to "http://192.168.1.30:8060/", "USN" to "uuid:roku:ecp:X00123"), "192.168.1.30")
        val device = RokuController.deviceFrom(ok)!!
        assertEquals("ROKU:X00123", device.id)
        assertEquals(8060, device.port)
        assertNull(RokuController.deviceFrom(ok.copy(headers = ok.headers + ("ST" to "upnp:rootdevice"))))
    }
}
