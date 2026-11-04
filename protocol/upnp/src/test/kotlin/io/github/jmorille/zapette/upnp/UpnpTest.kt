package io.github.jmorille.zapette.upnp

import io.github.jmorille.zapette.model.Device
import io.github.jmorille.zapette.model.DeviceKind
import io.github.jmorille.zapette.model.RemoteKey
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
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

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
}

class UpnpRendererTest {
    private lateinit var server: MockWebServer
    private val soapRequests = mutableListOf<RecordedRequest>()
    private var transportState = "PAUSED_PLAYBACK"

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
                soapRequests += request
                val action = request.headers["SOAPACTION"]!!.substringAfter('#').trim('"')
                return when (action) {
                    "GetTransportInfo" -> MockResponse.Builder()
                        .body(soapResponse(action, "<CurrentTransportState>$transportState</CurrentTransportState>")).build()
                    "GetVolume" -> MockResponse.Builder().body(soapResponse(action, "<CurrentVolume>40</CurrentVolume>")).build()
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
    fun `navigation keys are rejected`() = runTest {
        try {
            controller().send(RemoteKey.UP)
            fail("exception attendue")
        } catch (_: UnsupportedOperationException) {
        }
    }
}
