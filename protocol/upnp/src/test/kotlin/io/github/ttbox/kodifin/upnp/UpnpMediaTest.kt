package io.github.ttbox.kodifin.upnp

import io.github.ttbox.kodifin.model.Device
import io.github.ttbox.kodifin.model.DeviceKind
import io.github.ttbox.kodifin.model.UnsupportedAction
import io.github.ttbox.kodifin.model.UnsupportedActionException
import io.github.ttbox.kodifin.model.RepeatMode
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/** Métadonnées, pochette, modes de lecture et sauts relatifs d'un renderer DLNA. */
class UpnpMediaTest {
    private lateinit var server: MockWebServer
    private val calls = CopyOnWriteArrayList<String>()

    @Volatile private var playMode: String? = "REPEAT_ALL"
    @Volatile private var rejectPlayMode = false
    @Volatile private var trackMeta: String = ""
    @Volatile private var currentUri: String = "http://nas/film.mkv"

    private fun soap(action: String, body: String) =
        """<?xml version="1.0"?><s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"><s:Body>""" +
            """<u:${action}Response xmlns:u="urn:schemas-upnp-org:service:AVTransport:1">$body</u:${action}Response>""" +
            "</s:Body></s:Envelope>"

    private val fault = """<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"><s:Body><s:Fault>""" +
        """<detail><UPnPError xmlns="urn:schemas-upnp-org:control-1-0"><errorCode>712</errorCode>""" +
        "<errorDescription>Play mode not supported</errorDescription></UPnPError></detail></s:Fault></s:Body></s:Envelope>"

    private val description = """<?xml version="1.0"?>
        <root xmlns="urn:schemas-upnp-org:device-1-0"><device>
          <deviceType>urn:schemas-upnp-org:device:MediaRenderer:1</deviceType><friendlyName>Enceinte</friendlyName><UDN>uuid:9</UDN>
          <serviceList>
            <service><serviceType>urn:schemas-upnp-org:service:AVTransport:1</serviceType><controlURL>/AVT</controlURL></service>
            <service><serviceType>urn:schemas-upnp-org:service:RenderingControl:1</serviceType><controlURL>/RC</controlURL></service>
          </serviceList>
        </device></root>"""

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                when (request.url.encodedPath) {
                    "/desc.xml" -> return MockResponse.Builder().body(description).build()
                    "/art.jpg" -> return MockResponse.Builder().addHeader("Content-Type", "image/jpeg")
                        .body(Buffer().write(byteArrayOf(9, 8, 7))).build()
                    "/page.html" -> return MockResponse.Builder().addHeader("Content-Type", "text/html").body("<html/>").build()
                    "/huge.jpg" -> return MockResponse.Builder().addHeader("Content-Type", "image/jpeg")
                        .body(Buffer().write(ByteArray(5 * 1024 * 1024))).build()
                }
                val action = request.headers["SOAPACTION"]!!.substringAfter('#').trim('"')
                calls += action + (request.body?.utf8()?.let { Regex("<(NewPlayMode|Target)>([^<]*)<").find(it)?.groupValues?.get(2) }?.let { ":$it" } ?: "")
                return when (action) {
                    "GetTransportInfo" -> MockResponse.Builder().body(soap(action, "<CurrentTransportState>PLAYING</CurrentTransportState>")).build()
                    "GetTransportSettings" -> if (playMode == null) {
                        MockResponse.Builder().code(500).body("non").build()
                    } else {
                        MockResponse.Builder().body(soap(action, "<PlayMode>$playMode</PlayMode>")).build()
                    }
                    "SetPlayMode" -> if (rejectPlayMode) MockResponse.Builder().code(500).body(fault).build()
                    else MockResponse.Builder().body(soap(action, "")).build()
                    "GetPositionInfo" -> MockResponse.Builder().body(
                        soap(action, "<RelTime>00:00:50</RelTime><TrackDuration>00:01:00</TrackDuration><TrackMetaData>${xmlEscape(trackMeta)}</TrackMetaData>"),
                    ).build()
                    "GetMediaInfo" -> MockResponse.Builder().body(soap(action, "<CurrentURI>${xmlEscape(currentUri)}</CurrentURI>")).build()
                    "GetVolume" ->MockResponse.Builder().body(soap(action, "<CurrentVolume>20</CurrentVolume>")).build()
                    "GetMute" -> MockResponse.Builder().body(soap(action, "<CurrentMute>0</CurrentMute>")).build()
                    else -> MockResponse.Builder().body(soap(action, "")).build()
                }
            }
        }
        server.start()
    }

    @After
    fun tearDown() = server.close()

    private fun controller() = UpnpRendererController(
        Device("u", "Enceinte", DeviceKind.UPNP_RENDERER, server.hostName, server.port, mapOf("location" to server.url("/desc.xml").toString())),
        OkHttpClient(),
        pollIntervalMs = 0,
    )

    private fun didl(art: String) =
        """<DIDL-Lite xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/" xmlns:dc="http://purl.org/dc/elements/1.1/" """ +
            """xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/"><item id="1" parentID="0" restricted="1">""" +
            "<dc:title>Chanson</dc:title><dc:creator>Créateur</dc:creator><upnp:artist>Groupe</upnp:artist>" +
            "<upnp:album>Album</upnp:album><upnp:albumArtURI>$art</upnp:albumArtURI></item></DIDL-Lite>"

    @Test
    fun `didl metadata gives title, artist, album and http artwork only`() {
        val info = UpnpRendererController.didlInfo(didl("http://nas/cover.jpg"))!!
        assertEquals(DidlInfo("Chanson", "Groupe", "Album", "http://nas/cover.jpg"), info)
        assertNull(UpnpRendererController.didlInfo(didl("file:///etc/passwd"))!!.artUrl)
        assertNull(UpnpRendererController.didlInfo("NOT_IMPLEMENTED"))
        assertNull(UpnpRendererController.didlInfo("<pas du xml"))
        val creatorOnly = didl("").replace("<upnp:artist>Groupe</upnp:artist>", "")
        assertEquals("Créateur", UpnpRendererController.didlInfo(creatorOnly)!!.artist)
    }

    @Test
    fun `playback state exposes music metadata and artwork`() = runTest {
        trackMeta = didl(server.url("/art.jpg").toString())
        val state = controller().playbackState().first()
        assertEquals("Chanson", state.title)
        assertEquals("Groupe", state.artist)
        assertEquals("Album", state.album)
        assertEquals(server.url("/art.jpg").toString(), state.artworkKey)
    }

    @Test
    fun `artwork is fetched only when it is a bounded image`() = runTest {
        val c = controller()
        assertArrayEquals(byteArrayOf(9, 8, 7), c.image(server.url("/art.jpg").toString(), 300))
        assertNull(c.image(server.url("/page.html").toString(), 300))
        assertNull(c.image(server.url("/huge.jpg").toString(), 300))
        assertNull(c.image(server.url("/absent.jpg").toString(), 300))
        assertNull(c.image("ftp://nas/a.jpg", 300))
    }

    @Test
    fun `play mode is read from transport settings`() = runTest {
        val c = controller()
        var d = c.mediaDetails()
        assertEquals(RepeatMode.ALL, d.repeat)
        assertEquals(false, d.shuffle)
        assertEquals(1, d.speed)
        playMode = "SHUFFLE"
        d = c.mediaDetails()
        assertEquals(RepeatMode.OFF, d.repeat)
        assertEquals(true, d.shuffle)
        playMode = "REPEAT_ONE"
        assertEquals(RepeatMode.ONE, c.mediaDetails().repeat)
        playMode = null
        d = c.mediaDetails()
        assertNull(d.repeat)
        assertNull(d.shuffle)
    }

    @Test
    fun `repeat and shuffle send set play mode, refusal is explained`() = runTest {
        val c = controller()
        c.setRepeat(RepeatMode.ONE)
        c.setRepeat(RepeatMode.ALL)
        c.setRepeat(RepeatMode.OFF)
        c.setShuffle(true)
        c.setShuffle(false)
        assertEquals(
            listOf("REPEAT_ONE", "REPEAT_ALL", "NORMAL", "SHUFFLE", "NORMAL"),
            calls.filter { it.startsWith("SetPlayMode") }.map { it.substringAfter(':') },
        )
        rejectPlayMode = true
        val error = expectFailure<UnsupportedActionException> { c.setShuffle(true) }
        assertEquals(UnsupportedAction.PlayMode, error.action)
        assertEquals(DeviceKind.UPNP_RENDERER, error.kind)
        assertTrue("cause : ${error.cause}", error.cause is UpnpSoapFault)
    }

    @Test
    fun `current media is the transport uri with title and position`() = runTest {
        trackMeta = didl("")
        val media = controller().currentMedia()!!
        assertEquals("http://nas/film.mkv", media.url)
        assertEquals("Chanson", media.title)
        assertEquals(50_000L, media.positionMs)
        currentUri = "x-rincon-stream:123"
        assertNull(controller().currentMedia())
    }

    @Test
    fun `bubbleupnp renderers are recognised by manufacturer, model or name`() {
        fun dev(name: String, extras: Map<String, String> = emptyMap(), kind: DeviceKind = DeviceKind.UPNP_RENDERER) =
            Device("u", name, kind, "h", extras = extras)
        assertTrue(UpnpRendererController.isBubbleUpnpServer(dev("Salon", mapOf(UpnpRendererController.EXTRA_MANUFACTURER to "Bubblesoft BubbleUPnP"))))
        assertTrue(UpnpRendererController.isBubbleUpnpServer(dev("Salon", mapOf(UpnpRendererController.EXTRA_MODEL to "BubbleUPnP Media Renderer"))))
        assertTrue(UpnpRendererController.isBubbleUpnpServer(dev("Chromecast (BubbleUPnP)")))
        assertFalse(UpnpRendererController.isBubbleUpnpServer(dev("TV Samsung")))
        assertFalse(UpnpRendererController.isBubbleUpnpServer(dev("BubbleUPnP", kind = DeviceKind.KODI)))
    }

    @Test
    fun `seek by is relative and stays inside the track`() = runTest {
        val c = controller()
        c.seekBy(30_000)
        c.seekBy(-120_000)
        assertEquals(listOf("Seek:00:00:59", "Seek:00:00:00"), calls.filter { it.startsWith("Seek") })
    }
}
