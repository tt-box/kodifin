package io.github.jmorille.zapette.mediaserver

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

class MediaServerTest {
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() = server.close()

    private fun device(kind: DeviceKind, extras: Map<String, String> = emptyMap()) =
        Device("s", "Serveur", kind, server.hostName, server.port, extras)

    @Test
    fun `authenticates then targets first controllable session`() = runTest {
        server.enqueue(MockResponse.Builder().body("""{"AccessToken":"tok","User":{"Id":"u1","Name":"jo"}}""").build())
        server.enqueue(
            MockResponse.Builder().body(
                """[{"Id":"web","SupportsRemoteControl":false},{"Id":"tv","DeviceName":"TV","SupportsRemoteControl":true}]""",
            ).build(),
        )
        server.enqueue(MockResponse.Builder().code(204).build())

        val controller = MediaServerController.create(
            device(DeviceKind.JELLYFIN, mapOf("username" to "jo", "password" to "pw")),
            OkHttpClient(),
            "dev-1",
        )
        controller.connect()
        controller.send(RemoteKey.PLAY_PAUSE)

        val auth = server.next()
        assertEquals("/Users/AuthenticateByName", auth.url.encodedPath)
        assertTrue(auth.body!!.utf8().contains("\"Username\":\"jo\""))
        assertTrue(auth.headers["Authorization"]!!.startsWith("MediaBrowser Client=\"Zapette\""))

        val sessions = server.next()
        assertEquals("u1", sessions.url.queryParameter("ControllableByUserId"))
        assertTrue(sessions.headers["Authorization"]!!.contains("Token=\"tok\""))

        assertEquals("/Sessions/tv/Playing/PlayPause", server.next().url.encodedPath)

        val persisted = controller.persistableExtras()
        assertEquals("tok", persisted["token"])
        assertEquals("u1", persisted["userId"])
        assertNull("le mot de passe ne doit pas être conservé", persisted["password"])
    }

    @Test
    fun `emby uses X-Emby-Authorization header and general commands`() = runTest {
        server.enqueue(MockResponse.Builder().code(204).build())
        val controller = MediaServerController.create(
            device(DeviceKind.EMBY, mapOf("token" to "abc", "sessionId" to "s9")),
            OkHttpClient(),
            "dev-1",
        )
        controller.send(RemoteKey.UP)
        val request = server.next()
        assertEquals("/Sessions/s9/Command", request.url.encodedPath)
        assertTrue(request.headers["X-Emby-Authorization"]!!.contains("Token=\"abc\""))
        assertTrue(request.body!!.utf8().contains("\"Name\":\"MoveUp\""))
    }

    @Test
    fun `seek converts milliseconds to ticks`() = runTest {
        server.enqueue(MockResponse.Builder().code(204).build())
        val controller = MediaServerController.create(
            device(DeviceKind.JELLYFIN, mapOf("token" to "t", "sessionId" to "s1")),
            OkHttpClient(),
            "dev",
        )
        controller.seekTo(1_500)
        assertEquals("15000000", server.next().url.queryParameter("seekPositionTicks"))
    }

    @Test
    fun `discovery reply parsing`() {
        val device = MediaServerDiscovery.parseReply(
            """{"Address":"http://192.168.1.20:8096","Id":"abc","Name":"Jelly"}""",
            MediaServerFlavor.JELLYFIN,
        )!!
        assertEquals("192.168.1.20", device.host)
        assertEquals(8096, device.port)
        assertEquals(DeviceKind.JELLYFIN, device.kind)
        assertNull(MediaServerDiscovery.parseReply("garbage", MediaServerFlavor.EMBY))
        assertEquals("who is EmbyServer?", MediaServerDiscovery.probe(MediaServerFlavor.EMBY))
    }
}
