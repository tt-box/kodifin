package io.github.jmorille.zapette.upnp

import io.github.jmorille.zapette.model.Device
import io.github.jmorille.zapette.model.DeviceKind
import io.github.jmorille.zapette.model.HttpStatusException
import io.github.jmorille.zapette.model.RemoteException
import io.github.jmorille.zapette.model.RemoteKey
import io.github.jmorille.zapette.model.UdpDestination
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
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
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.xml.sax.SAXException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.time.TestTimeSource

class SsdpTest {
    @Test
    fun `search message follows UDA format`() {
        val msg = Ssdp.searchMessage(Ssdp.ST_MEDIA_RENDERER, 3)
        assertTrue(msg.startsWith("M-SEARCH * HTTP/1.1\r\n"))
        assertTrue(msg.contains("HOST: 239.255.255.250:1900\r\n"))
        assertTrue(msg.contains("MAN: \"ssdp:discover\"\r\n"))
        assertTrue(msg.contains("MX: 3\r\n"))
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
        assertEquals("10.0.0.9", r.sourceAddress)
        assertNull(Ssdp.parse("M-SEARCH * HTTP/1.1\r\n\r\n", "x"))
        assertNull(Ssdp.parse("", "x"))
    }

    @Test
    fun `parses HTTP 1_0 status, NOTIFY, folded header lines and stops at blank line`() {
        val r = Ssdp.parse(
            "HTTP/1.0 200 OK\r\nSERVER: Linux UPnP/1.0\r\n  Renderer/2\r\nST:roku:ecp\r\nsans-deux-points\r\n\r\nST: ignored\r\n",
            "10.0.0.9",
        )!!
        assertEquals("Linux UPnP/1.0 Renderer/2", r.server)
        assertEquals("roku:ecp", r.searchTarget)
        assertEquals(setOf("SERVER", "ST"), r.headers.keys)
        assertNull(Ssdp.parse("HTTP/1.1 404 Not Found\r\n\r\n", "x"))
        val notify = Ssdp.parse("NOTIFY * HTTP/1.1\nNT: upnp:rootdevice\nUSN: uuid:n\n\n", "10.0.0.9")!!
        assertEquals("uuid:n", notify.usn)
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
        assertFalse(resp("http://[fe80::2]:8060/", "fe80::1").hasTrustedLocation)
        assertFalse(resp("http://10.0.0.9/", "fe80::1").hasTrustedLocation)
        assertFalse(SsdpResponse(emptyMap(), "10.0.0.9").hasTrustedLocation)
        assertTrue(resp("http://[fe80::1]:8060/", "fe80:0:0:0:0:0:0:1%wlan0").hasTrustedLocation)
    }

    private fun reply(usn: String, location: String) =
        "HTTP/1.1 200 OK\r\nLOCATION: $location\r\nST: ${Ssdp.ST_MEDIA_RENDERER}\r\nUSN: $usn\r\n\r\n"

    @Test
    fun `search deduplicates by USN and ignores untrusted or unreadable replies`() = runTest {
        val time = TestTimeSource()
        val socket = FakeUdpSocket(
            time,
            listOf(
                datagram(reply("uuid:tv", "http://10.0.0.9:1400/a.xml"), "10.0.0.9"),
                datagram("\u0000\u0001 n'importe quoi", "10.0.0.7"),
                null,
                datagram(reply("uuid:pirate", "http://203.0.113.5/d.xml"), "10.0.0.7"),
                datagram(reply("uuid:sans-source", "http://10.0.0.8/d.xml"), null),
                datagram(reply("uuid:tv", "http://10.0.0.9:1400/b.xml"), "10.0.0.9"),
                datagram("HTTP/1.1 200 OK\r\nLOCATION: http://10.0.0.6/c.xml\r\n\r\n", "10.0.0.6"),
            ),
        )
        val found = Ssdp.search(Ssdp.ST_MEDIA_RENDERER, timeoutMs = 3_000, sockets = socket, timeSource = time)
        assertEquals(listOf("uuid:tv", null), found.map { it.usn })
        // Le doublon le plus récent l'emporte ; sans USN, la clé est LOCATION.
        assertEquals(listOf("http://10.0.0.9:1400/b.xml", "http://10.0.0.6/c.xml"), found.map { it.location })
        val group = UdpDestination(Ssdp.MULTICAST_ADDRESS, Ssdp.PORT)
        assertEquals(listOf(group, group), socket.sent.map { it.first })
        assertTrue(socket.sent.first().second.contains("MX: 2\r\n"))
        assertEquals(Ssdp.MULTICAST_TTL, socket.options.single().multicastTtl)
        assertTrue(socket.closed)
    }

    @Test
    fun `search stops at the global deadline even with a continuous stream`() = runTest {
        val time = TestTimeSource()
        val socket = FakeUdpSocket(time, endless = datagram(reply("uuid:tv", "http://10.0.0.9/a.xml"), "10.0.0.9"))
        val found = Ssdp.search(Ssdp.ST_ROKU, timeoutMs = 2_000, sockets = socket, timeSource = time)
        assertEquals(1, found.size)
        assertEquals(20, socket.receives)
    }
}

class UpnpXmlTest {
    private fun assertRefused(xml: String, reason: String) {
        val e = assertThrows(SAXException::class.java) { UpnpXml.parseDocument(xml) }
        assertTrue("motif « $reason » attendu : ${e.message}", e.message!!.contains(reason))
    }

    @Test
    fun `doctype is refused whatever its spelling or position`() {
        val xxe = """<?xml version="1.0"?><!DOCTYPE r [<!ENTITY x SYSTEM "file:///etc/hostname">]><root>&x;</root>"""
        assertRefused(xxe, "DOCTYPE")
        assertRefused("""<!doctype r [<!ENTITY a "aaaa"><!ENTITY b "&a;&a;&a;">]><root>&b;</root>""", "DOCTYPE")
        assertRefused("  \n\t<!DocType r SYSTEM \"http://evil/x.dtd\">  \n<root/>", "DOCTYPE")
        assertRefused("﻿<!DOCTYPE r [<!ENTITY x SYSTEM \"file:///etc/passwd\">]><root>&x;</root>", "DOCTYPE")
    }

    @Test
    fun `oversized or deeply nested documents are bounded`() {
        assertRefused("<root>" + "a".repeat(UpnpXml.MAX_DOCUMENT_CHARS) + "</root>", "volumineux")
        val deep = "<a>".repeat(UpnpXml.MAX_DEPTH + 1) + "</a>".repeat(UpnpXml.MAX_DEPTH + 1)
        assertRefused(deep, "imbriqué")
        // Éléments vides, commentaires, CDATA et instructions ne comptent pas dans la profondeur.
        val wide = "<?xml version=\"1.0\"?><root>" + "<a/><!-- <b> --><![CDATA[<c><d>]]>".repeat(200) + "</root>"
        assertEquals("root", UpnpXml.parseDocument(wide).nodeName)
        val ok = "<a>".repeat(UpnpXml.MAX_DEPTH) + "</a>".repeat(UpnpXml.MAX_DEPTH)
        assertEquals("a", UpnpXml.parseDocument(ok).nodeName)
    }

    private fun desc(urlBase: String = "", vararg controls: String) =
        """<root xmlns="urn:schemas-upnp-org:device-1-0">$urlBase<device><serviceList>""" +
            controls.mapIndexed { i, c ->
                "<service><serviceType>urn:schemas-upnp-org:service:S$i:1</serviceType><controlURL>$c</controlURL></service>"
            }.joinToString("") +
            "</serviceList></device></root>"

    @Test
    fun `url base is honoured and invalid url base falls back to location`() {
        val location = "http://10.0.0.5:1400/d.xml"
        val withBase = UpnpXml.parseDescription(desc("<URLBase>http://10.0.0.5:2869/upnp/</URLBase>", "ctl/avt"), location)
        assertEquals(listOf("http://10.0.0.5:2869/upnp/ctl/avt"), withBase.services.map { it.controlUrl })
        val badBase = UpnpXml.parseDescription(desc("<URLBase>http://</URLBase>", "ctl/avt"), location)
        assertEquals(listOf("http://10.0.0.5:1400/ctl/avt"), badBase.services.map { it.controlUrl })
    }

    @Test
    fun `url base pointing to another host falls back to location`() {
        val location = "http://10.0.0.5:1400/xml/d.xml"
        for (base in listOf("http://203.0.113.9/", "https://evil.example:443/", "//10.0.0.66/", "ftp://10.0.0.5/")) {
            val d = UpnpXml.parseDescription(desc("<URLBase>$base</URLBase>", "ctl"), location)
            assertEquals(base, listOf("http://10.0.0.5:1400/xml/ctl"), d.services.map { it.controlUrl })
        }
    }

    @Test
    fun `control urls must stay on the described host and use http`() {
        val d = UpnpXml.parseDescription(
            desc(
                "",
                "http://203.0.113.9/ctl",
                "//evil.example/ctl",
                "file:///etc/passwd",
                "ftp://10.0.0.5/ctl",
                "javascript:alert(1)",
                "http://10.0.0.5:8080/same-host-other-port",
                "/abs/path",
            ),
            "http://10.0.0.5:1400/d.xml",
        )
        assertEquals(
            listOf("http://10.0.0.5:8080/same-host-other-port", "http://10.0.0.5:1400/abs/path"),
            d.services.map { it.controlUrl },
        )
        assertEquals(listOf("urn:schemas-upnp-org:service:S5:1", "urn:schemas-upnp-org:service:S6:1"), d.services.map { it.serviceType })
    }

    @Test
    fun `invalid location or missing device is a parse error`() {
        val noDevice = assertThrows(SAXException::class.java) { UpnpXml.parseDescription("<root/>", "http://10.0.0.5/d.xml") }
        assertTrue(noDevice.message!!.contains("<device>"))
        val badLocation = assertThrows(SAXException::class.java) { UpnpXml.parseDescription(desc("", "c"), "file:///d.xml") }
        assertTrue(badLocation.message!!.contains("URL de description invalide"))
    }

    @Test
    fun `upnp booleans and didl title`() {
        assertEquals(true, UpnpRendererController.parseUpnpBoolean("True"))
        assertEquals(true, UpnpRendererController.parseUpnpBoolean(" yes "))
        assertEquals(false, UpnpRendererController.parseUpnpBoolean("0"))
        assertEquals(false, UpnpRendererController.parseUpnpBoolean("no"))
        assertNull(UpnpRendererController.parseUpnpBoolean("peut-être"))
        assertNull(UpnpRendererController.parseUpnpBoolean(null))
        val didl = UpnpRendererController.didlMetadata("http://nas/a.wav", "Titre & co")
        assertEquals("Titre & co", UpnpRendererController.didlTitle(didl))
        assertTrue(didl.contains("http-get:*:audio/wav:*"))
        assertTrue(didl.contains("object.item.audioItem.musicTrack"))
        assertTrue(UpnpRendererController.didlMetadata("http://nas/p.jpg", null).contains("<dc:title>p.jpg</dc:title>"))
        assertNull(UpnpRendererController.didlTitle("NOT_IMPLEMENTED"))
        assertNull(UpnpRendererController.didlTitle(""))
        assertNull(UpnpRendererController.didlTitle("<pas fermé"))
    }

    @Test
    fun `device is built from location and udn, skipped without valid location`() {
        val d = UpnpDeviceDescription("TV", "", "uuid:1", null, null, emptyList())
        assertNull(UpnpRendererController.deviceFrom(SsdpResponse(emptyMap(), "10.0.0.9"), d))
        assertNull(UpnpRendererController.deviceFrom(SsdpResponse(mapOf("LOCATION" to "pas une url"), "10.0.0.9"), d))
        val device = UpnpRendererController.deviceFrom(SsdpResponse(mapOf("LOCATION" to "http://10.0.0.9:1400/d.xml"), "10.0.0.9"), d)!!
        assertEquals("UPNP:uuid:1", device.id)
        assertEquals("10.0.0.9", device.host)
        assertEquals(1400, device.port)
        assertEquals("http://10.0.0.9:1400/d.xml", device.extras[UpnpRendererController.EXTRA_LOCATION])
    }
}

class UpnpRendererTest {
    private lateinit var server: MockWebServer

    /** Écrite par les threads de MockWebServer, lue par le test : structure thread-safe. */
    private val soapRequests = CopyOnWriteArrayList<RecordedRequest>()

    @Volatile private var transportState = "PAUSED_PLAYBACK"

    @Volatile private var volumeBody = "<CurrentVolume>40</CurrentVolume>"

    @Volatile private var muteValue = "1"

    @Volatile private var renderingFails = false

    @Volatile private var transportFails = false

    @Volatile private var transportGate: CountDownLatch? = null

    @Volatile private var avtControl = "/AVT/control"

    /** Simule un renderer qui refuse toute métadonnée DIDL non vide (faute UPnP 714). */
    @Volatile private var rejectMetadata = false

    private fun description() = """<?xml version="1.0"?>
        <root xmlns="urn:schemas-upnp-org:device-1-0">
          <device>
            <deviceType>urn:schemas-upnp-org:device:MediaRenderer:1</deviceType>
            <friendlyName>TV &amp; Salon</friendlyName>
            <UDN>uuid:1234</UDN>
            <serviceList>
              <service><serviceType>urn:schemas-upnp-org:service:AVTransport:1</serviceType><controlURL>$avtControl</controlURL></service>
              <service><serviceType>urn:schemas-upnp-org:service:RenderingControl:1</serviceType><controlURL>RC/control</controlURL></service>
            </serviceList>
          </device>
        </root>"""

    private fun soapResponse(action: String, body: String) =
        """<?xml version="1.0"?><s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"><s:Body>""" +
            """<u:${action}Response xmlns:u="urn:schemas-upnp-org:service:AVTransport:1">$body</u:${action}Response>""" +
            "</s:Body></s:Envelope>"

    private fun error500() = MockResponse.Builder().code(500).body("Internal error").build()

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
                    "SetAVTransportURI" -> if (rejectMetadata && "<CurrentURIMetaData></CurrentURIMetaData>" !in request.body!!.utf8()) {
                        MockResponse.Builder().code(500).body(
                            """<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"><s:Body><s:Fault>""" +
                                """<detail><UPnPError xmlns="urn:schemas-upnp-org:control-1-0"><errorCode>714</errorCode>""" +
                                "<errorDescription>Illegal MIME-type</errorDescription></UPnPError></detail>" +
                                "</s:Fault></s:Body></s:Envelope>",
                        ).build()
                    } else {
                        MockResponse.Builder().body(soapResponse(action, "")).build()
                    }
                    "GetTransportInfo" -> {
                        transportGate?.await(5, TimeUnit.SECONDS)
                        if (transportFails) return error500()
                        MockResponse.Builder()
                            .body(soapResponse(action, "<CurrentTransportState>$transportState</CurrentTransportState>")).build()
                    }
                    "GetPositionInfo" -> MockResponse.Builder().body(
                        soapResponse(
                            action,
                            "<RelTime>00:01:05</RelTime><TrackDuration>01:00:00</TrackDuration><TrackMetaData>" +
                                xmlEscape(UpnpRendererController.didlMetadata("http://nas/f.mkv", "Film")) + "</TrackMetaData>",
                        ),
                    ).build()
                    "GetVolume" -> if (renderingFails) error500() else MockResponse.Builder().body(soapResponse(action, volumeBody)).build()
                    "GetMute" -> if (renderingFails) {
                        error500()
                    } else {
                        MockResponse.Builder().body(soapResponse(action, "<CurrentMute>$muteValue</CurrentMute>")).build()
                    }
                    "Stop" -> MockResponse.Builder().build()
                    "Next" -> error500()
                    "Previous" -> MockResponse.Builder().code(500).body(soapResponse(action, "")).build()
                    "Seek" -> MockResponse.Builder().code(500).body(
                        """<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"><s:Body><s:Fault>""" +
                            """<detail><UPnPError xmlns="urn:schemas-upnp-org:control-1-0"><errorCode>710</errorCode>""" +
                            "<errorDescription>Seek mode not supported</errorDescription></UPnPError></detail>" +
                            "</s:Fault></s:Body></s:Envelope>",
                    ).build()
                    "Pause" -> MockResponse.Builder().code(403).build()
                    else -> MockResponse.Builder().body(soapResponse(action, "")).build()
                }
            }
        }
        server.start()
    }

    @After
    fun tearDown() {
        transportGate?.countDown()
        server.close()
    }

    private fun controller(
        location: String = "/desc.xml",
        pollIntervalMs: Long = 0,
        extras: Map<String, String> = emptyMap(),
    ) = UpnpRendererController(
        Device(
            "u", "TV", DeviceKind.UPNP_RENDERER, server.hostName, server.port,
            mapOf("location" to server.url(location).toString()) + extras,
        ),
        OkHttpClient(),
        pollIntervalMs = pollIntervalMs,
    )

    private fun actions() = soapRequests.map { it.headers["SOAPACTION"]!!.substringAfter('#').trim('"') }

    @Test
    fun `description parsing resolves relative control urls and unescapes names`() {
        val d = UpnpXml.parseDescription(description(), "http://10.0.0.5:1400/xml/desc.xml")
        assertEquals("TV & Salon", d.friendlyName)
        assertEquals("urn:schemas-upnp-org:device:MediaRenderer:1", d.deviceType)
        assertEquals("uuid:1234", d.udn)
        assertNull(d.manufacturer)
        assertEquals("http://10.0.0.5:1400/AVT/control", d.service(UpnpXml.AV_TRANSPORT)!!.controlUrl)
        assertEquals("http://10.0.0.5:1400/xml/RC/control", d.service(UpnpXml.RENDERING_CONTROL)!!.controlUrl)
    }

    @Test
    fun `play pause resumes when paused`() = runTest {
        controller().send(RemoteKey.PLAY_PAUSE)
        assertEquals(listOf("GetTransportInfo", "Play"), actions())
        val body = soapRequests.last().body!!.utf8()
        assertTrue(body.contains("<Speed>1</Speed>"))
        assertTrue(body.contains("<InstanceID>0</InstanceID>"))
        assertEquals("\"urn:schemas-upnp-org:service:AVTransport:1#Play\"", soapRequests.last().headers["SOAPACTION"])
        assertEquals("/AVT/control", soapRequests.last().url.encodedPath)
    }

    @Test
    fun `play pause pauses when playing and surfaces http errors`() = runTest {
        transportState = "PLAYING"
        val e = expectFailure<HttpStatusException> { controller().send(RemoteKey.PLAY_PAUSE) }
        assertEquals(403, e.code)
        assertEquals(listOf("GetTransportInfo", "Pause"), actions())
    }

    @Test
    fun `volume up and down add a step to current volume`() = runTest {
        val c = controller()
        c.send(RemoteKey.VOLUME_UP)
        assertTrue(soapRequests.last().body!!.utf8().contains("<DesiredVolume>45</DesiredVolume>"))
        c.send(RemoteKey.VOLUME_DOWN)
        assertTrue(soapRequests.last().body!!.utf8().contains("<DesiredVolume>35</DesiredVolume>"))
        c.setVolume(150)
        assertTrue(soapRequests.last().body!!.utf8().contains("<DesiredVolume>100</DesiredVolume>"))
        assertEquals(listOf("GetVolume", "SetVolume", "GetVolume", "SetVolume", "SetVolume"), actions())
        assertTrue(soapRequests.last().body!!.utf8().contains("<Channel>Master</Channel>"))
        assertEquals("/RC/control", soapRequests.last().url.encodedPath)
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
    fun `alternative didl uses a generic protocolInfo without dlna flags`() = runTest {
        controller(extras = mapOf(UpnpRendererController.EXTRA_ALT_DIDL to "true")).playUrl("http://nas/film.mkv", "Film")
        assertEquals(listOf("SetAVTransportURI", "Play"), actions())
        val body = soapRequests.first().body!!.utf8()
        assertTrue(body.contains("<CurrentURI>http://nas/film.mkv</CurrentURI>"))
        assertTrue(body, body.contains("protocolInfo=&quot;http-get:*:*:*&quot;"))
        assertFalse(body.contains("video/x-matroska"))
        assertFalse(body.contains("DLNA.ORG"))
        assertTrue(body.contains("&lt;upnp:class&gt;object.item.videoItem&lt;/upnp:class&gt;"))
        assertTrue(body.contains("&lt;dc:title&gt;Film&lt;/dc:title&gt;"))
    }

    @Test
    fun `alternative didl retries with empty metadata when the renderer refuses it`() = runTest {
        rejectMetadata = true
        controller(extras = mapOf(UpnpRendererController.EXTRA_ALT_DIDL to "true")).playUrl("http://nas/a.mp3", null)
        assertEquals(listOf("SetAVTransportURI", "SetAVTransportURI", "Play"), actions())
        assertTrue(soapRequests[0].body!!.utf8().contains("object.item.audioItem"))
        assertTrue(soapRequests[1].body!!.utf8().contains("<CurrentURIMetaData></CurrentURIMetaData>"))
    }

    @Test
    fun `standard mode keeps the typed metadata and surfaces the fault`() = runTest {
        rejectMetadata = true
        val fault = expectFailure<UpnpSoapFault> {
            controller(extras = mapOf(UpnpRendererController.EXTRA_ALT_DIDL to "false")).playUrl("http://nas/film.mkv", "Film")
        }
        assertEquals("714", fault.errorCode)
        assertEquals(listOf("SetAVTransportURI"), actions())
        assertTrue(soapRequests.single().body!!.utf8().contains("http-get:*:video/x-matroska:*"))
    }

    @Test
    fun `soap faults are surfaced`() = runTest {
        val fault = expectFailure<UpnpSoapFault> { controller().seekTo(90_000) }
        assertEquals("710", fault.errorCode)
        assertTrue(fault.message!!.contains("Seek mode not supported"))
        assertTrue(soapRequests.single().body!!.utf8().contains("<Target>00:01:30</Target>"))
    }

    @Test
    fun `unreadable volume does not reset volume to zero`() = runTest {
        volumeBody = ""
        val e = expectFailure<RemoteException> { controller().send(RemoteKey.VOLUME_UP) }
        assertEquals("Volume courant illisible", e.message)
        assertEquals(listOf("GetVolume"), actions())
    }

    @Test
    fun `mute accepts textual upnp booleans and refuses unreadable ones`() = runTest {
        muteValue = "true"
        controller().send(RemoteKey.MUTE)
        assertEquals(listOf("GetMute", "SetMute"), actions())
        assertTrue(soapRequests.last().body!!.utf8().contains("<DesiredMute>0</DesiredMute>"))
        muteValue = "?"
        val e = expectFailure<RemoteException> { controller().send(RemoteKey.MUTE) }
        assertEquals("État de sourdine illisible", e.message)
    }

    @Test
    fun `empty 200 response is accepted`() = runTest {
        controller().send(RemoteKey.STOP)
        assertEquals(listOf("Stop"), actions())
    }

    @Test
    fun `http 500 without soap fault is an error`() = runTest {
        for (key in listOf(RemoteKey.NEXT, RemoteKey.PREVIOUS)) {
            val e = expectFailure<HttpStatusException> { controller().send(key) }
            assertEquals(500, e.code)
        }
    }

    @Test
    fun `invalid description is reported as remote exception caused by the parser`() = runTest {
        val e = expectFailure<RemoteException> { controller("/bad.xml").connect() }
        assertEquals("Description UPnP invalide", e.message)
        assertTrue("cause : ${e.cause}", e.cause is SAXException)
    }

    @Test
    fun `missing location is reported`() = runTest {
        val c = UpnpRendererController(Device("u", "TV", DeviceKind.UPNP_RENDERER, "10.0.0.9", 1400), OkHttpClient())
        val e = expectFailure<RemoteException> { c.connect() }
        assertEquals("URL de description UPnP absente", e.message)
    }

    @Test
    fun `control url on another host is never called`() = runTest {
        avtControl = "http://203.0.113.9:${server.port}/AVT/control"
        val e = expectFailure<RemoteException> { controller().send(RemoteKey.STOP) }
        assertEquals("AVTransport absent", e.message)
        assertEquals(1, server.requestCount) // la description seulement
        assertTrue(soapRequests.isEmpty())
    }

    @Test
    fun `navigation keys are rejected`() = runTest {
        expectFailure<UnsupportedOperationException> { controller().send(RemoteKey.UP) }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `playback state is polled without delay when the interval is zero`() = runTest {
        val states = controller().playbackState().take(3).toList()
        assertEquals(3, states.size)
        val s = states.first()
        assertEquals("Film", s.title)
        assertFalse(s.isPlaying)
        assertEquals(65_000L, s.positionMs)
        assertEquals(3_600_000L, s.durationMs)
        assertEquals(40, s.volumePercent)
        assertTrue(s.muted)
        assertEquals(12, actions().size)
    }

    @Test
    fun `playback state is still emitted when volume and mute fail`() = runTest {
        renderingFails = true
        transportState = "PLAYING"
        val s = controller().playbackState().first()
        assertTrue(s.isPlaying)
        assertNull(s.volumePercent)
        assertFalse(s.muted)
        assertEquals(listOf("GetTransportInfo", "GetPositionInfo", "GetVolume", "GetMute"), actions())
    }

    @Test
    fun `transport error terminates the playback flow with the http error`() = runTest {
        transportFails = true
        val e = expectFailure<HttpStatusException> { controller().playbackState().first() }
        assertEquals(500, e.code)
        assertEquals(listOf("GetTransportInfo"), actions())
    }

    @Test
    fun `cancelling the playback flow aborts the pending soap call`() = runBlocking {
        val gate = CountDownLatch(1).also { transportGate = it }
        val job = launch(Dispatchers.IO) { controller().playbackState().collect {} }
        val waitStart = System.nanoTime()
        while (soapRequests.isEmpty() && System.nanoTime() - waitStart < 5_000_000_000) Thread.sleep(5)
        assertEquals(listOf("GetTransportInfo"), actions())
        val start = System.nanoTime()
        job.cancelAndJoin()
        assertTrue("annulation trop lente", System.nanoTime() - start < 2_000_000_000)
        assertTrue(job.isCancelled)
        gate.countDown()
    }
}
