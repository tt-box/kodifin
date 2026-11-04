package io.github.jmorille.zapette.mediaserver

import io.github.jmorille.zapette.model.Device
import io.github.jmorille.zapette.model.DeviceKind
import io.github.jmorille.zapette.model.HttpStatusException
import io.github.jmorille.zapette.model.RemoteException
import io.github.jmorille.zapette.model.RemoteKey
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Cas limites : sessions disparues, jeton expiré, injection dans les URL, JSON inattendu. */
class MediaServerRobustnessTest {
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() = server.close()

    private fun device(kind: DeviceKind, extras: Map<String, String>) =
        Device("s", "Serveur", kind, server.hostName, server.port, extras)

    private fun ok(body: String = "") = MockResponse.Builder().body(body).build()
    private fun status(code: Int) = MockResponse.Builder().code(code).build()

    @Test
    fun `session id is encoded as a single path segment`() = runTest {
        server.enqueue(status(204))
        val controller = MediaServerController.create(
            device(DeviceKind.JELLYFIN, mapOf("token" to "t", "sessionId" to "../Users/x?y=1")),
            OkHttpClient(),
            "dev",
        )
        controller.send(RemoteKey.UP)
        val request = server.next()
        assertEquals("/Sessions/..%2FUsers%2Fx%3Fy=1/Command", request.url.encodedPath)
        assertNull(request.url.queryParameter("y"))
    }

    @Test
    fun `stale session is forgotten, a new one is selected and the command retried`() = runTest {
        server.enqueue(status(404))
        server.enqueue(ok("""[{"Id":"tv2","SupportsRemoteControl":true}]"""))
        server.enqueue(status(204))
        val controller = MediaServerController.create(
            device(DeviceKind.JELLYFIN, mapOf("token" to "t", "sessionId" to "old")),
            OkHttpClient(),
            "dev",
        )
        controller.send(RemoteKey.PLAY_PAUSE)
        assertEquals("/Sessions/old/Playing/PlayPause", server.next().url.encodedPath)
        assertEquals("/Sessions", server.next().url.encodedPath)
        assertEquals("/Sessions/tv2/Playing/PlayPause", server.next().url.encodedPath)
        assertNull("la session disparue ne doit pas être reconduite", controller.persistableExtras()["sessionId"])
    }

    @Test
    fun `expired token triggers a new authentication then a retry`() = runTest {
        server.enqueue(status(401))
        server.enqueue(ok("""{"AccessToken":"neuf","User":{"Id":"u1"}}"""))
        server.enqueue(status(204))
        val controller = MediaServerController.create(
            device(DeviceKind.JELLYFIN, mapOf("token" to "vieux", "sessionId" to "tv", "username" to "jo", "password" to "pw")),
            OkHttpClient(),
            "dev",
        )
        controller.send(RemoteKey.STOP)
        assertTrue(server.next().headers["Authorization"]!!.contains("Token=\"vieux\""))
        assertEquals("/Users/AuthenticateByName", server.next().url.encodedPath)
        assertTrue(server.next().headers["Authorization"]!!.contains("Token=\"neuf\""))
        val persisted = controller.persistableExtras()
        assertEquals("neuf", persisted["token"])
        assertNull(persisted["password"])
    }

    @Test
    fun `expired token without credentials asks to reconnect and drops the token`() = runTest {
        server.enqueue(status(401))
        val controller = MediaServerController.create(
            device(DeviceKind.JELLYFIN, mapOf("token" to "vieux", "sessionId" to "tv")),
            OkHttpClient(),
            "dev",
        )
        val e = expectFailure<RemoteException> { controller.send(RemoteKey.STOP) }
        assertTrue(e.message!!.contains("reconnectez-vous"))
        assertFalse("pas d'erreur HTTP brute", e is HttpStatusException)
        assertNull(controller.persistableExtras()["token"])
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `refused credentials never leak the password`() = runTest {
        server.enqueue(status(401))
        val controller = MediaServerController.create(
            device(DeviceKind.EMBY, mapOf("username" to "jo", "password" to "S3cret!")),
            OkHttpClient(),
            "dev",
        )
        val e = expectFailure<RemoteException> { controller.connect() }
        assertTrue(e.message!!.contains("Identifiants refusés"))
        assertEquals(401, (e.cause as HttpStatusException).code)
        var t: Throwable? = e
        while (t != null) {
            assertFalse(t.message.orEmpty().contains("S3cret!"))
            t = t.cause
        }
        assertFalse(server.next().url.toString().contains("S3cret"))
    }

    @Test
    fun `own session is skipped and a playing session is preferred`() = runTest {
        server.enqueue(
            ok(
                """[{"Id":"moi","DeviceId":"dev","SupportsRemoteControl":true},
                   {"Id":"idle","SupportsRemoteControl":true},
                   {"Id":"film","SupportsRemoteControl":true,"NowPlayingItem":{"Name":"Film"}}]""",
            ),
        )
        server.enqueue(status(204))
        val controller = MediaServerController.create(device(DeviceKind.JELLYFIN, mapOf("token" to "t")), OkHttpClient(), "dev")
        controller.send(RemoteKey.SELECT)
        server.next()
        assertEquals("/Sessions/film/Command", server.next().url.encodedPath)
    }

    @Test
    fun `unexpected json becomes a RemoteException`() = runTest {
        server.enqueue(ok("""{"pas":"une liste"}"""))
        val controller = MediaServerController.create(device(DeviceKind.JELLYFIN, mapOf("token" to "t")), OkHttpClient(), "dev")
        val e = expectFailure<RemoteException> { controller.send(RemoteKey.SELECT) }
        assertEquals("Réponse inattendue du serveur", e.message)
        assertTrue("cause : ${e.cause}", e.cause is kotlinx.serialization.SerializationException)
    }

    @Test
    fun `null fields in sessions fall back to defaults`() = runTest {
        val body = """[{"Id":"tv","DeviceName":null,"SupportsRemoteControl":true,"PlayState":{"PositionTicks":null}}]"""
        server.enqueue(ok(body))
        server.enqueue(ok(body))
        val controller = MediaServerController.create(device(DeviceKind.JELLYFIN, mapOf("token" to "t")), OkHttpClient(), "dev")
        assertEquals(0L, controller.playbackState().first().positionMs)
    }

    @Test
    fun `vanished session in playback polling triggers a new selection`() = runTest {
        server.enqueue(ok("""[{"Id":"autre","SupportsRemoteControl":true}]"""))
        server.enqueue(ok("""[{"Id":"autre","SupportsRemoteControl":true}]"""))
        server.enqueue(status(204))
        val controller = MediaServerController.create(
            device(DeviceKind.JELLYFIN, mapOf("token" to "t", "sessionId" to "partie")),
            OkHttpClient(),
            "dev",
        )
        assertNull(controller.playbackState().first().title)
        controller.send(RemoteKey.UP)
        server.next()
        server.next()
        assertEquals("/Sessions/autre/Command", server.next().url.encodedPath)
    }

    @Test
    fun `emby sends X-Emby-Token and header values are sanitized`() = runTest {
        server.enqueue(status(204))
        val controller = MediaServerController.create(
            device(DeviceKind.EMBY, mapOf("token" to "abc\", Token=\"pirate", "sessionId" to "s")),
            OkHttpClient(),
            "dev\"x",
        )
        controller.send(RemoteKey.UP)
        val request = server.next()
        val auth = request.headers["X-Emby-Authorization"]!!
        // Le jeton reste confiné dans sa propre valeur entre guillemets.
        assertTrue(auth.endsWith(", Token=\"abc Token=pirate\""))
        assertTrue(auth.contains("DeviceId=\"devx\""))
        assertEquals("abc Token=pirate", request.headers["X-Emby-Token"])
    }

    @Test
    fun `negative seek is clamped to zero`() = runTest {
        server.enqueue(status(204))
        val controller = MediaServerController.create(
            device(DeviceKind.JELLYFIN, mapOf("token" to "t", "sessionId" to "s")),
            OkHttpClient(),
            "dev",
        )
        controller.seekTo(-5_000)
        assertEquals("0", server.next().url.queryParameter("seekPositionTicks"))
    }

    @Test
    fun `discovery falls back to the packet source for unusable addresses`() {
        val docker = MediaServerDiscovery.parseReply(
            """{"Address":"http://127.0.0.1:8096","Id":"abc","Name":null}""",
            MediaServerFlavor.JELLYFIN,
            "192.168.1.30",
        )!!
        assertEquals("192.168.1.30", docker.host)
        assertEquals(8096, docker.port)
        assertEquals("Jellyfin", docker.name)
        val noAddress = MediaServerDiscovery.parseReply("""{"Id":"e1","Name":"Emby"}""", MediaServerFlavor.EMBY, "10.0.0.2")!!
        assertEquals("10.0.0.2", noAddress.host)
        assertNull(MediaServerDiscovery.parseReply("""{"Id":"e1"}""", MediaServerFlavor.EMBY))
        assertNull(MediaServerDiscovery.parseReply("""{"Id":"","Address":"http://10.0.0.2:8096"}""", MediaServerFlavor.EMBY))
    }
}
