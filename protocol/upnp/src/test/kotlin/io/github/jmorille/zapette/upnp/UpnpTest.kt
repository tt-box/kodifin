package io.github.jmorille.zapette.upnp

import io.github.jmorille.zapette.model.Device
import io.github.jmorille.zapette.model.DeviceKind
import io.github.jmorille.zapette.model.HttpStatusException
import io.github.jmorille.zapette.model.RemoteException
import io.github.jmorille.zapette.model.RemoteKey
import kotlinx.coroutines.test.runTest
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.xml.sax.SAXException

class SsdpTest {
    @Test
    fun `search message follows UDA format`() {
        val msg = Ssdp.searchMessage(Ssdp.ST_MEDIA_RENDERER, 3)
        assertTrue(msg.startsWith("M-SEARCH * HTTP/1.1\r\n"))
        assertTrue(msg.contains("MAN: \"ssdp:discover\"\r\n"))
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
        assertNull(Ssdp.parse("M-SEARCH * HTTP/1.1\r\n\r\n", "x"))
    }

    @Test
    fun `parses HTTP 1_0 status, folded header lines and stops at blank line`() {
        val r = Ssdp.parse(
            "HTTP/1.0 200 OK\r\nSERVER: Linux UPnP/1.0\r\n  Renderer/2\r\nST:roku:ecp\r\n\r\nST: ignored\r\n",
            "10.0.0.9",
        )!!
        assertEquals("Linux UPnP/1.0 Renderer/2", r.server)
        assertEquals("roku:ecp", r.searchTarget)
        assertNull(Ssdp.parse("HTTP/1.1 404 Not Found\r\n\r\n", "x"))
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
        assertTrue(resp("http://[fe80::1]:8060/", "fe80:0:0:0:0:0:0:1%wlan0").hasTrustedLocation)
    }
}

class UpnpXmlTest {
    @Test
    fun `doctype is refused even when parser features are unsupported`() {
        val xxe = """<?xml version="1.0"?><!DOCTYPE r [<!ENTITY x SYSTEM "file:///etc/hostname">]><root>&x;</root>"""
        try {
            UpnpXml.parseDocument(xxe)
            fail("DOCTYPE accepté")
        } catch (_: SAXException) {
        }
        val bomb = """<!doctype r [<!ENTITY a "aaaa"><!ENTITY b "&a;&a;&a;">]><root>&b;</root>"""
        try {
            UpnpXml.parseDocument(bomb)
            fail("DOCTYPE accepté")
        } catch (_: SAXException) {
        }
    }

    @Test
    fun `url base is honoured and invalid url base falls back to location`() {
        fun desc(urlBase: String) = """<root xmlns="urn:schemas-upnp-org:device-1-0">$urlBase<device>
            <serviceList><service><serviceType>urn:schemas-upnp-org:service:AVTransport:1</serviceType>
            <controlURL>ctl/avt</controlURL></service></serviceList></device></root>"""
        val withBase = UpnpXml.parseDescription(desc("<URLBase>http://10.0.0.5:2869/upnp/</URLBase>"), "http://10.0.0.5:1400/d.xml")
        assertEquals("http://10.0.0.5:2869/upnp/ctl/avt", withBase.service(UpnpXml.AV_TRANSPORT)!!.controlUrl)
        val badBase = UpnpXml.parseDescription(desc("<URLBase>http://</URLBase>"), "http://10.0.0.5:1400/d.xml")
        assertEquals("http://10.0.0.5:1400/ctl/avt", badBase.service(UpnpXml.AV_TRANSPORT)!!.controlUrl)
    }

    @Test
    fun `missing device element is a parse error`() {
        try {
            UpnpXml.parseDescription("<root/>", "http://10.0.0.5/d.xml")
            fail("exception attendue")
        } catch (_: SAXException) {
        }
    }

    @Test
    fun `upnp booleans and didl title`() {
        assertEquals(true, UpnpRendererController.parseUpnpBoolean("True"))
        assertEquals(false, UpnpRendererController.parseUpnpBoolean("0"))
        assertNull(UpnpRendererController.parseUpnpBoolean("peut-être"))
        val didl = UpnpRendererController.didlMetadata("http://nas/a.wav", "Titre & co")
        assertEquals("Titre & co", UpnpRendererController.didlTitle(didl))
        assertTrue(didl.contains("audio/wav"))
    }

    @Test
    fun `device without location is skipped instead of crashing`() {
        val d = UpnpDeviceDescription("TV", "", "uuid:1", null, null, emptyList())
        assertNull(UpnpRendererController.deviceFrom(SsdpResponse(emptyMap(), "10.0.0.9"), d))
        assertNull(UpnpRendererController.deviceFrom(SsdpResponse(mapOf("LOCATION" to "pas une url"), "10.0.0.9"), d))
    }
}

class UpnpRendererTest {
    private lateinit var server: MockWebServer
    private val soapRequests = mutableListOf<RecordedRequest>()
    private var transportState = "PAUSED_PLAYBACK"
    private var volumeBody = "<CurrentVolume>40</CurrentVolume>"
    private var muteValue = "1"

    private fun description() = """<?xml version="1.0"?>
        <root xmlns="urn:schemas-upnp-org:device-1-0">
          <device>
            <deviceType>urn:schemas-upnp-org:device:MediaRenderer:1</deviceType>
            <friendlyName>TV &amp; Salon</friendlyName>
            <UDN>uuid:1234</UDN>
            <serviceList>
              <service><serviceType>urn:schemas-upnp-org:service:AVTransport:1</serviceType><controlURL>/AVT/control</controlURL></service>
              <service><serviceType>urn:schemas-upnp-org:service:RenderingControl:1</serviceType><controlURL>RC/control</controlURL></service>
            </serviceList>
          </device>
        </root>"""

    private fun soapResponse(action: String, body: String) =
        """<?xml version="1.0"?><s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"><s:Body>""" +
            """<u:${action}Response xmlns:u="urn:schemas-upnp-org:service:AVTransport:1">$body</u:${action}Response>""" +
            "</s:Body></s:Envelope>"

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.url.encodedPath == "/desc.xml") return MockResponse.Builder().body(description()).build()
                if (request.url.encodedPath == "/bad.xml") return MockResponse.Builder().body("<root><!-- tronqué").build()
                soapRequests += request
                val action = request.headers["SOAPACTION"]!!.substringAfter('#').trim('"')
                return when (action) {
                    "GetTransportInfo" -> MockResponse.Builder()
                        .body(soapResponse(action, "<CurrentTransportState>$transportState</CurrentTransportState>")).build()
                    "GetVolume" -> MockResponse.Builder().body(soapResponse(action, volumeBody)).build()
                    "GetMute" -> MockResponse.Builder().body(soapResponse(action, "<CurrentMute>$muteValue</CurrentMute>")).build()
                    "Stop" -> MockResponse.Builder().build()
                    "Next" -> MockResponse.Builder().code(500).body("Internal error").build()
                    "Previous" -> MockResponse.Builder().code(500).body(soapResponse(action, "")).build()
                    "Seek" -> MockResponse.Builder().code(500).body(
                        """<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"><s:Body><s:Fault>""" +
                            """<detail><UPnPError xmlns="urn:schemas-upnp-org:control-1-0"><errorCode>710</errorCode>""" +
                            "<errorDescription>Seek mode not supported</errorDescription></UPnPError></detail>" +
                            "</s:Fault></s:Body></s:Envelope>",
                    ).build()
                    else -> MockResponse.Builder().body(soapResponse(action, "")).build()
                }
            }
        }
        server.start()
    }

    @After
    fun tearDown() = server.close()

    private fun controller() = UpnpRendererController(
        Device("u", "TV", DeviceKind.UPNP_RENDERER, server.hostName, server.port, mapOf("location" to server.url("/desc.xml").toString())),
        OkHttpClient(),
    )

    private fun actions() = soapRequests.map { it.headers["SOAPACTION"]!!.substringAfter('#').trim('"') }

    @Test
    fun `description parsing resolves relative control urls and unescapes names`() {
        val d = UpnpXml.parseDescription(description(), "http://10.0.0.5:1400/xml/desc.xml")
        assertEquals("TV & Salon", d.friendlyName)
        assertEquals("http://10.0.0.5:1400/AVT/control", d.service(UpnpXml.AV_TRANSPORT)!!.controlUrl)
        assertEquals("http://10.0.0.5:1400/xml/RC/control", d.service(UpnpXml.RENDERING_CONTROL)!!.controlUrl)
    }

    @Test
    fun `play pause resumes when paused`() = runTest {
        controller().send(RemoteKey.PLAY_PAUSE)
        assertEquals(listOf("GetTransportInfo", "Play"), actions())
        assertTrue(soapRequests.last().body!!.utf8().contains("<Speed>1</Speed>"))
    }

    @Test
    fun `play pause pauses when playing`() = runTest {
        transportState = "PLAYING"
        controller().send(RemoteKey.PLAY_PAUSE)
        assertEquals(listOf("GetTransportInfo", "Pause"), actions())
    }

    @Test
    fun `volume up adds a step to current volume`() = runTest {
        controller().send(RemoteKey.VOLUME_UP)
        assertEquals(listOf("GetVolume", "SetVolume"), actions())
        assertTrue(soapRequests.last().body!!.utf8().contains("<DesiredVolume>45</DesiredVolume>"))
    }

    @Test
    fun `playUrl escapes metadata inside the soap envelope`() = runTest {
        controller().playUrl("http://nas/film.mkv?a=1&b=2", "Film <1>")
        assertEquals(listOf("SetAVTransportURI", "Play"), actions())
        val body = soapRequests.first().body!!.utf8()
        assertTrue(body.contains("<CurrentURI>http://nas/film.mkv?a=1&amp;b=2</CurrentURI>"))
        assertTrue(body.contains("&lt;dc:title&gt;Film &amp;lt;1&amp;gt;&lt;/dc:title&gt;"))
        assertTrue(body.contains("video/x-matroska"))
    }

    @Test
    fun `soap faults are surfaced`() = runTest {
        try {
            controller().seekTo(90_000)
            fail("faute attendue")
        } catch (e: UpnpSoapFault) {
            assertEquals("710", e.errorCode)
        }
        assertTrue(soapRequests.single().body!!.utf8().contains("<Target>00:01:30</Target>"))
    }

    @Test
    fun `unreadable volume does not reset volume to zero`() = runTest {
        volumeBody = ""
        try {
            controller().send(RemoteKey.VOLUME_UP)
            fail("exception attendue")
        } catch (_: RemoteException) {
        }
        assertEquals(listOf("GetVolume"), actions())
    }

    @Test
    fun `mute accepts textual upnp booleans`() = runTest {
        muteValue = "true"
        controller().send(RemoteKey.MUTE)
        assertEquals(listOf("GetMute", "SetMute"), actions())
        assertTrue(soapRequests.last().body!!.utf8().contains("<DesiredMute>0</DesiredMute>"))
    }

    @Test
    fun `empty 200 response is accepted`() = runTest {
        controller().send(RemoteKey.STOP)
        assertEquals(listOf("Stop"), actions())
    }

    @Test
    fun `http 500 without soap fault is an error`() = runTest {
        for (key in listOf(RemoteKey.NEXT, RemoteKey.PREVIOUS)) {
            try {
                controller().send(key)
                fail("exception attendue pour $key")
            } catch (e: HttpStatusException) {
                assertEquals(500, e.code)
            }
        }
    }

    @Test
    fun `invalid description is reported as remote exception`() = runTest {
        val c = UpnpRendererController(
            Device("u", "TV", DeviceKind.UPNP_RENDERER, server.hostName, server.port, mapOf("location" to server.url("/bad.xml").toString())),
            OkHttpClient(),
        )
        try {
            c.connect()
            fail("exception attendue")
        } catch (_: RemoteException) {
        }
    }

    @Test
    fun `navigation keys are rejected`() = runTest {
        try {
            controller().send(RemoteKey.UP)
            fail("exception attendue")
        } catch (_: UnsupportedOperationException) {
        }
    }
}
