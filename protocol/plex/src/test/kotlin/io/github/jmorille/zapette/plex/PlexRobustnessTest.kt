package io.github.jmorille.zapette.plex

import io.github.jmorille.zapette.model.Device
import io.github.jmorille.zapette.model.DeviceKind
import io.github.jmorille.zapette.model.RemoteException
import io.github.jmorille.zapette.model.RemoteKey
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/** Cas limites Plex : paramètres requis, volume, jeton, identifiant du lecteur, réponses invalides. */
class PlexRobustnessTest {
    private lateinit var server: MockWebServer
    private val identity = PlexIdentity("zapette-test")

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
        assertEquals("video", server.takeRequest().url.queryParameter("type"))
        assertNull(server.takeRequest().url.queryParameter("type"))
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
        assertEquals("/player/timeline/poll", server.takeRequest().url.encodedPath)
        assertEquals("30", server.takeRequest().url.queryParameter("volume"))
        assertEquals("40", server.takeRequest().url.queryParameter("volume"))
    }

    @Test
    fun `unreadable timeline falls back to a neutral volume`() = runTest {
        server.enqueue(MockResponse.Builder().code(500).build())
        server.enqueue(MockResponse.Builder().build())
        val controller = player()
        controller.send(RemoteKey.VOLUME_DOWN)
        server.takeRequest()
        assertEquals("40", server.takeRequest().url.queryParameter("volume"))
    }

    @Test
    fun `negative seek is clamped`() = runTest {
        server.enqueue(MockResponse.Builder().build())
        player().seekTo(-1)
        val request = server.takeRequest()
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
        assertEquals("/resources", server.takeRequest().url.encodedPath)
        assertEquals("m-42", server.takeRequest().headers["X-Plex-Target-Client-Identifier"])
    }

    @Test
    fun `manual device id is never used as a client identifier`() = runTest {
        server.enqueue(MockResponse.Builder().build())
        player(extras = emptyMap(), id = "PLEX:10.0.0.8:32500").send(RemoteKey.SELECT)
        assertNull(server.takeRequest().headers["X-Plex-Target-Client-Identifier"])
    }

    @Test
    fun `token is only sent to local hosts`() {
        listOf("192.168.1.5", "10.0.0.8", "172.16.3.4", "127.0.0.1", "fe80::1", "fd12::5", "[fd12::5]", "htpc", "salon.local", "localhost")
            .forEach { assertTrue(it, PlexController.isLocalHost(it)) }
        listOf("8.8.8.8", "2001:db8::1", "plex.example.com", "100.1.2.3")
            .forEach { assertFalse(it, PlexController.isLocalHost(it)) }
    }

    @Test
    fun `token is not sent to a public player`() = runTest {
        // Aucune requête : on vérifie seulement que le jeton est écarté à la construction.
        val controller = PlexController(
            Device("PLEX:x", "Distant", DeviceKind.PLEX, "plex.example.com", 32500, mapOf("token" to "tk")),
            OkHttpClient(),
            identity,
        )

        assertNull(controller.token)
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
        try {
            PlexAccount(OkHttpClient(), identity, "http://plex.example.com")
            fail("HTTP clair attendu refusé")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test
    fun `unexpected pin json becomes a RemoteException`() = runTest {
        server.enqueue(MockResponse.Builder().body("""{"errors":[{"code":1020}]}""").build())
        try {
            PlexAccount(OkHttpClient(), identity, server.url("/").toString()).createPin()
            fail("une RemoteException était attendue")
        } catch (e: RemoteException) {
            assertTrue(e.message!!.contains("inattendue"))
        }
    }
}
