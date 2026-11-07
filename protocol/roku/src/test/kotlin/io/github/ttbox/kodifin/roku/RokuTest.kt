package io.github.ttbox.kodifin.roku

import io.github.ttbox.kodifin.model.Capability
import io.github.ttbox.kodifin.model.Device
import io.github.ttbox.kodifin.model.DeviceKind
import io.github.ttbox.kodifin.model.RemoteError
import io.github.ttbox.kodifin.model.RemoteException
import io.github.ttbox.kodifin.model.Setup
import io.github.ttbox.kodifin.model.RemoteKey
import io.github.ttbox.kodifin.model.UnsupportedActionException
import io.github.ttbox.kodifin.model.SsdpResponse
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Assert.fail
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
    fun `text reserved characters and non BMP code points are fully encoded`() = runTest {
        controller().sendText("+/?#é\uD83D\uDE00")
        assertEquals("/keypress/Lit_%2B", server.takeRequest().url.encodedPath)
        assertEquals("/keypress/Lit_%2F", server.takeRequest().url.encodedPath)
        assertEquals("/keypress/Lit_%3F", server.takeRequest().url.encodedPath)
        assertEquals("/keypress/Lit_%23", server.takeRequest().url.encodedPath)
        assertEquals("/keypress/Lit_%C3%A9", server.takeRequest().url.encodedPath)
        // Un émoji (paire de substitution) = une seule touche, encodée en UTF-8 sur 4 octets.
        assertEquals("/keypress/Lit_%F0%9F%98%80", server.takeRequest().url.encodedPath)
        assertEquals(6, server.requestCount)
    }

    @Test
    fun `launch rejects path traversal in app id`() = runTest {
        controller().launch("12")
        assertEquals("/launch/12", server.takeRequest().url.encodedPath)
        for (bad in listOf("..", "../query/apps", "12/x", "")) {
            try {
                controller().launch(bad)
                fail("identifiant accepté : $bad")
            } catch (_: IllegalArgumentException) {
            }
        }
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `playUrl derives video format from the url path only`() = runTest {
        controller().playUrl("http://192.168.1.2/stream")
        val noExt = server.takeRequest().url
        assertEquals("v", noExt.queryParameter("t"))
        assertNull(noExt.queryParameter("videoFormat"))
        controller().playUrl("http://nas/live/index.m3u8?token=a.b")
        assertEquals("hls", server.takeRequest().url.queryParameter("videoFormat"))
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
        assertNull(RokuController.deviceFrom(ok.copy(headers = ok.headers + ("LOCATION" to "http//cassé"))))
    }

    @Test
    fun `http 403 explains the mobile apps control setting`() = runTest {
        val denied = MockWebServer()
        repeat(2) { denied.enqueue(MockResponse.Builder().code(403).build()) }
        denied.start()
        try {
            val c = RokuController(Device("r", "Roku", DeviceKind.ROKU, denied.hostName, denied.port), OkHttpClient())
            for (action in listOf<suspend () -> Unit>({ c.send(RemoteKey.HOME) }, { c.connect() })) {
                try {
                    action()
                    fail("403 non signalé")
                } catch (e: RokuController.RokuAccessDeniedException) {
                    assertEquals(RemoteError.SetupRequired(Setup.ROKU_NETWORK_ACCESS), e.reason)
                }
            }
        } finally {
            denied.close()
        }
    }

    @Test
    fun `other http errors are not reported as access denied`() = runTest {
        val failing = MockWebServer()
        failing.enqueue(MockResponse.Builder().code(500).build())
        failing.start()
        try {
            RokuController(Device("r", "Roku", DeviceKind.ROKU, failing.hostName, failing.port), OkHttpClient()).send(RemoteKey.HOME)
            fail("erreur 500 non signalée")
        } catch (e: RemoteException) {
            assertFalse(e is RokuController.RokuAccessDeniedException)
            assertEquals(RemoteError.Http(500), e.reason)
        } finally {
            failing.close()
        }
    }

    @Test
    fun `volume uses keys only and no absolute volume capability`() = runTest {
        val c = controller()
        assertFalse(Capability.VOLUME in c.capabilities)
        c.send(RemoteKey.VOLUME_UP)
        c.send(RemoteKey.VOLUME_DOWN)
        c.send(RemoteKey.MUTE)
        assertEquals("/keypress/VolumeUp", server.takeRequest().url.encodedPath)
        assertEquals("/keypress/VolumeDown", server.takeRequest().url.encodedPath)
        assertEquals("/keypress/VolumeMute", server.takeRequest().url.encodedPath)
        try {
            c.setVolume(50)
            fail("volume absolu accepté")
        } catch (_: UnsupportedActionException) {
        }
    }

    @Test
    fun `menu is the options key and info is not supported`() = runTest {
        val c = controller()
        c.send(RemoteKey.MENU)
        assertEquals("/keypress/Info", server.takeRequest().url.encodedPath)
        assertFalse(RemoteKey.INFO in c.supportedKeys)
        try {
            c.send(RemoteKey.INFO)
            fail("INFO acceptée")
        } catch (_: UnsupportedActionException) {
        }
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `invalid address is reported as remote exception without crashing construction`() = runTest {
        val c = RokuController(Device("r", "Roku", DeviceKind.ROKU, "hôte invalide", 8060), OkHttpClient())
        for (action in listOf<suspend () -> Unit>({ c.send(RemoteKey.HOME) }, { c.connect() }, { c.sendText("a") })) {
            try {
                action()
                fail("adresse invalide acceptée")
            } catch (e: RemoteException) {
                assertEquals(RemoteError.InvalidAddress("hôte invalide:8060"), e.reason)
            }
        }
    }

    @Test
    fun `literal keypress keeps percent encoding of reserved and percent characters`() = runTest {
        controller().sendText("%&=")
        assertEquals("/keypress/Lit_%25", server.takeRequest().url.encodedPath)
        assertEquals("/keypress/Lit_%26", server.takeRequest().url.encodedPath)
        assertEquals("/keypress/Lit_%3D", server.takeRequest().url.encodedPath)
    }
}
