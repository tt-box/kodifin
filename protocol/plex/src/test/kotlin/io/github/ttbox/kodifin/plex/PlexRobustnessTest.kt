package io.github.ttbox.kodifin.plex

import io.github.ttbox.kodifin.model.Device
import io.github.ttbox.kodifin.model.DeviceKind
import io.github.ttbox.kodifin.model.RemoteError
import io.github.ttbox.kodifin.model.RemoteException
import io.github.ttbox.kodifin.model.RemoteKey
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test

/** Cas limites Plex : paramètres requis, volume, jeton, identifiant du lecteur, réponses invalides. */
class PlexRobustnessTest {
    private lateinit var server: MockWebServer
    private val identity = PlexIdentity("kodifin-test")

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() = server.close()

    private fun player(extras: Map<String, String> = mapOf("clientIdentifier" to "abc"), id: String = "PLEX:abc") =
        PlexController(Device(id, "HTPC", DeviceKind.PLEX, server.hostName, server.port, extras), OkHttpClient(), identity)

    @Test
    fun `playback commands carry the media type, navigation commands do not`() = runTest {
        repeat(2) { server.enqueue(MockResponse.Builder().build()) }
        val controller = player()
        controller.send(RemoteKey.STOP)
        controller.send(RemoteKey.LEFT)
        assertEquals("video", server.next().url.queryParameter("type"))
        assertNull(server.next().url.queryParameter("type"))
    }

    @Test
    fun `relative volume starts from the volume read on the player timeline`() = runTest {
        server.enqueue(
            MockResponse.Builder().body(
                """<MediaContainer commandID="1"><Timeline type="music" state="stopped" volume="80"/>""" +
                    """<Timeline type="video" state="playing" volume="20" time="0"/></MediaContainer>""",
            ).build(),
        )
        repeat(2) { server.enqueue(MockResponse.Builder().build()) }
        val controller = player()
        controller.send(RemoteKey.VOLUME_UP)
        controller.send(RemoteKey.VOLUME_UP)
        assertEquals("/player/timeline/poll", server.next().url.encodedPath)
        assertEquals("30", server.next().url.queryParameter("volume"))
        assertEquals("40", server.next().url.queryParameter("volume"))
    }

    @Test
    fun `unreadable timeline falls back to a neutral volume`() = runTest {
        server.enqueue(MockResponse.Builder().code(500).build())
        server.enqueue(MockResponse.Builder().build())
        val controller = player()
        controller.send(RemoteKey.VOLUME_DOWN)
        server.next()
        assertEquals("40", server.next().url.queryParameter("volume"))
    }

    @Test
    fun `negative seek is clamped`() = runTest {
        server.enqueue(MockResponse.Builder().build())
        player().seekTo(-1)
        val request = server.next()
        assertEquals("0", request.url.queryParameter("offset"))
        assertEquals("video", request.url.queryParameter("type"))
    }

    @Test
    fun `manual device resolves the player identifier before sending commands`() = runTest {
        server.enqueue(
            MockResponse.Builder().body(
                """<MediaContainer><Player title="Salon" machineIdentifier="m-42" protocolCapabilities="navigation,playback"/></MediaContainer>""",
            ).build(),
        )
        server.enqueue(MockResponse.Builder().build())
        val controller = player(extras = emptyMap(), id = "PLEX:10.0.0.8:32500")
        controller.connect()
        controller.send(RemoteKey.SELECT)
        assertEquals("/resources", server.next().url.encodedPath)
        assertEquals("m-42", server.next().headers["X-Plex-Target-Client-Identifier"])
    }

    @Test
    fun `manual device id is never used as a client identifier`() = runTest {
        server.enqueue(MockResponse.Builder().build())
        player(extras = emptyMap(), id = "PLEX:10.0.0.8:32500").send(RemoteKey.SELECT)
        assertNull(server.next().headers["X-Plex-Target-Client-Identifier"])
    }

    @Test
    fun `gdm parsing rejects blank identifiers and invalid ports`() {
        val base = "HTTP/1.0 200 OK\r\nContent-Type: plex/media-player\r\nName: TV\r\n"
        assertNull(PlexController.parseGdm(base + "Resource-Identifier: \r\n", "10.0.0.8"))
        val device = PlexController.parseGdm(base + "Resource-Identifier: id1\r\nPort: 99999\r\n", "10.0.0.8")!!
        assertEquals(32500, device.port)
    }

    @Test
    fun `timeline volume parsing`() {
        assertEquals(65, PlexController.parseTimelineVolume("""<MediaContainer><Timeline state="paused" volume="65" type="video"/></MediaContainer>"""))
        assertNull(PlexController.parseTimelineVolume("<MediaContainer/>"))
    }

    @Test
    fun `plex account refuses clear http outside loopback`() {
        val e = assertThrows(IllegalArgumentException::class.java) {
            PlexAccount(OkHttpClient(), identity, "http://plex.example.com")
        }
        assertEquals("L'API plex.tv doit être jointe en HTTPS", e.message)
        // HTTPS et boucle locale sont acceptés.
        PlexAccount(OkHttpClient(), identity, "https://plex.tv")
        PlexAccount(OkHttpClient(), identity, "http://127.0.0.1:1234")
    }

    @Test
    fun `unexpected pin json becomes a RemoteException`() = runTest {
        server.enqueue(MockResponse.Builder().body("""{"errors":[{"code":1020}]}""").build())
        val e = expectFailure<RemoteException> { PlexAccount(OkHttpClient(), identity, server.url("/").toString()).createPin() }
        assertEquals(RemoteError.UnexpectedResponse("plex.tv"), e.reason)
        assertTrue("cause : ${e.cause}", e.cause is kotlinx.serialization.SerializationException)
    }
}
