package io.github.jmorille.zapette.plex

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
import org.junit.Before
import org.junit.Test

class PlexTest {
    private lateinit var server: MockWebServer
    private val identity = PlexIdentity("zapette-test")

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() = server.close()

    @Test
    fun `player commands carry target and incrementing command id`() = runTest {
        repeat(2) { server.enqueue(MockResponse.Builder().build()) }
        val device = Device("PLEX:abc", "HTPC", DeviceKind.PLEX, server.hostName, server.port, mapOf("clientIdentifier" to "abc", "token" to "tk"))
        val controller = PlexController(device, OkHttpClient(), identity)
        controller.send(RemoteKey.UP)
        controller.send(RemoteKey.PLAY_PAUSE)

        val first = server.takeRequest()
        assertEquals("/player/navigation/moveUp", first.url.encodedPath)
        assertEquals("1", first.url.queryParameter("commandID"))
        assertEquals("abc", first.headers["X-Plex-Target-Client-Identifier"])
        assertEquals("tk", first.headers["X-Plex-Token"])
        assertEquals("zapette-test", first.headers["X-Plex-Client-Identifier"])
        val second = server.takeRequest()
        assertEquals("/player/playback/playPause", second.url.encodedPath)
        assertEquals("2", second.url.queryParameter("commandID"))
    }

    @Test
    fun `pin flow returns token once linked`() = runTest {
        server.enqueue(MockResponse.Builder().body("""{"id":42,"code":"ABCD","authToken":null}""").build())
        server.enqueue(MockResponse.Builder().body("""{"id":42,"code":"ABCD","authToken":null}""").build())
        server.enqueue(MockResponse.Builder().body("""{"id":42,"code":"ABCD","authToken":"secret"}""").build())
        val account = PlexAccount(OkHttpClient(), identity, server.url("/").toString().trimEnd('/'))
        val pin = account.createPin()
        assertEquals("ABCD", pin.code)
        assertNull(account.checkPin(pin))
        assertEquals("secret", account.checkPin(pin))
        assertEquals("POST", server.takeRequest().method)
        assertEquals("/api/v2/pins/42", server.takeRequest().url.encodedPath)
    }

    @Test
    fun `resources expose players`() = runTest {
        server.enqueue(
            MockResponse.Builder().body(
                """[{"name":"Salon","clientIdentifier":"p1","provides":"client,player","connections":[{"uri":"http://10.0.0.8:32500","local":true}]},
                   {"name":"NAS","clientIdentifier":"s1","provides":"server"}]""",
            ).build(),
        )
        val resources = PlexAccount(OkHttpClient(), identity, server.url("/").toString().trimEnd('/')).resources("tok")
        assertEquals(listOf("Salon"), resources.filter { it.isPlayer }.map { it.name })
        assertEquals("tok", server.takeRequest().headers["X-Plex-Token"])
    }

    @Test
    fun `gdm reply parsing keeps players only`() {
        val reply = "HTTP/1.0 200 OK\r\nContent-Type: plex/media-player\r\nResource-Identifier: xyz\r\n" +
            "Name: Plex HTPC\r\nPort: 32500\r\nProduct: Plex HTPC\r\n"
        val device = PlexController.parseGdm(reply, "10.0.0.8")!!
        assertEquals("PLEX:xyz", device.id)
        assertEquals("Plex HTPC", device.name)
        assertTrue(device.extras["clientIdentifier"] == "xyz")
        assertNull(PlexController.parseGdm(reply.replace("media-player", "media-server"), "10.0.0.8"))
    }
}
