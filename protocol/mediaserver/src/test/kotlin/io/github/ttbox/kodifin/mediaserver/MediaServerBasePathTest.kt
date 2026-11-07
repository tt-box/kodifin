package io.github.ttbox.kodifin.mediaserver

import io.github.ttbox.kodifin.model.Device
import io.github.ttbox.kodifin.model.DeviceKind
import io.github.ttbox.kodifin.model.RemoteError
import io.github.ttbox.kodifin.model.RemoteException
import io.github.ttbox.kodifin.model.Setup
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
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/** Serveur publié sous un chemin de base (« URL de base » de Jellyfin), cas réel : `/jellyfin`. */
class MediaServerBasePathTest {
    private lateinit var server: MockWebServer
    private val paths = CopyOnWriteArrayList<String>()
    private val http = OkHttpClient()

    private val html = MockResponse.Builder().addHeader("Content-Type", "text/html").body("<html>web</html>").build()
    private fun json(body: String) = MockResponse.Builder().addHeader("Content-Type", "application/json").body(body).build()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.url.encodedPath
                paths += path
                return when (path) {
                    "/jellyfin/System/Info/Public" -> json("""{"Id":"742461e2","ServerName":"thor"}""")
                    "/jellyfin/Users/AuthenticateByName" -> json("""{"AccessToken":"tok","User":{"Id":"u1"}}""")
                    "/jellyfin/Sessions" -> json("""[{"Id":"s1","SupportsRemoteControl":true}]""")
                    // Hors du chemin de base, Jellyfin renvoie l'interface web (HTML).
                    else -> html
                }
            }
        }
        server.start()
    }

    @After
    fun tearDown() {
        server.close()
        http.dispatcher.executorService.shutdown()
        http.connectionPool.evictAll()
    }

    private fun device(extras: Map<String, String> = emptyMap()) = Device(
        "JELLYFIN:x", "thor", DeviceKind.JELLYFIN, server.hostName, server.port,
        mapOf("username" to "jerome", "password" to "pw") + extras,
    )

    @Test
    fun `base path is normalized`() {
        assertEquals("/jellyfin", MediaServerController.normalizeBasePath(" jellyfin/ "))
        assertEquals("/media/jellyfin", MediaServerController.normalizeBasePath("/media/jellyfin/"))
        assertNull(MediaServerController.normalizeBasePath("/"))
        assertNull(MediaServerController.normalizeBasePath(""))
        assertNull(MediaServerController.normalizeBasePath("/a b"))
        assertNull(MediaServerController.normalizeBasePath("/../x?y"))
    }

    @Test
    fun `discovery keeps the announced base path`() {
        val payload = """{"Address":"http://192.168.30.30:8096/jellyfin","Id":"742461e2","Name":"thor","EndpointAddress":null}"""
        val device = MediaServerDiscovery.parseReply(payload, MediaServerFlavor.JELLYFIN, "192.168.30.30")!!
        assertEquals("/jellyfin", device.extras[MediaServerController.EXTRA_BASE_PATH])
        assertEquals("http://192.168.30.30:8096/jellyfin", MediaServerController.baseUrl(device))
    }

    @Test
    fun `configured base path is used directly`() = runTest {
        val controller = MediaServerController.create(device(mapOf("base_path" to "/jellyfin")), http, "kodifin")
        controller.connect()
        assertEquals(listOf("/jellyfin/Users/AuthenticateByName", "/jellyfin/Sessions"), paths)
    }

    @Test
    fun `missing base path is detected and persisted`() = runTest {
        val controller = MediaServerController.create(device(), http, "kodifin")
        controller.connect()
        assertEquals(
            listOf(
                "/Users/AuthenticateByName", // HTML : réponse inattendue
                "/System/Info/Public", // racine : pas l'API
                "/jellyfin/System/Info/Public", // chemin usuel : l'API répond
                "/jellyfin/Users/AuthenticateByName",
                "/jellyfin/Sessions",
            ),
            paths,
        )
        assertEquals("/jellyfin", controller.persistableExtras()[MediaServerController.EXTRA_BASE_PATH])
        assertEquals("tok", controller.persistableExtras()[MediaServerController.EXTRA_TOKEN])
    }

    @Test
    fun `unknown base path gives an actionable message`() = runTest {
        val controller = MediaServerController.create(device(mapOf("base_path" to "/autre")), http, "kodifin")
        val error = runCatching { controller.connect() }.exceptionOrNull()
        // Chemin configuré explicitement : pas de détection, l'erreur d'origine est remontée.
        assertTrue("$error", error is RemoteException && error.reason == RemoteError.UnexpectedResponse("Jellyfin"))

        paths.clear()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = html.also { paths += request.url.encodedPath }
        }
        val failure = runCatching { MediaServerController.create(device(), http, "kodifin").connect() }.exceptionOrNull()
        assertTrue("$failure", failure is RemoteException && failure.reason == RemoteError.SetupRequired(Setup.MEDIA_SERVER_BASE_PATH))
    }
}
