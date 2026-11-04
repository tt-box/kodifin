package io.github.jmorille.zapette.mediaserver

import io.github.jmorille.zapette.model.Device
import io.github.jmorille.zapette.model.DeviceKind
import io.github.jmorille.zapette.model.RemoteException
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class MediaServerDescriptionTest {
    private lateinit var server: MockWebServer
    private val http = OkHttpClient()

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
    }

    @After
    fun tearDown() {
        server.close()
        http.dispatcher.executorService.shutdown()
        http.connectionPool.evictAll()
    }

    private fun json(body: String) = MockResponse.Builder().addHeader("Content-Type", "application/json").body(body).build()

    @Test
    fun `describe formats product, version and server name`() {
        val jf = PublicInfoDto("1", serverName = "thor", version = "10.10.3", productName = "Jellyfin Server")
        assertEquals("Jellyfin Server 10.10.3 – thor", MediaServerController.describe(DeviceKind.JELLYFIN, jf))
        assertEquals("Emby 4.8.10.0 – nas", MediaServerController.describe(DeviceKind.EMBY, PublicInfoDto("1", "nas", "4.8.10.0")))
        assertEquals("Jellyfin", MediaServerController.describe(DeviceKind.JELLYFIN, PublicInfoDto("1")))
    }

    @Test
    fun `publicInfo reads System Info Public under the base path`() = runTest {
        server.enqueue(json("""{"Id":"a","ServerName":"thor","Version":"10.10.3","ProductName":"Jellyfin Server","X":1}"""))
        val device = Device("J", "thor", DeviceKind.JELLYFIN, server.hostName, server.port, mapOf("base_path" to "/jellyfin"))
        val c = MediaServerController.create(device, http, "dev")
        assertEquals("Jellyfin Server 10.10.3 – thor", c.serverDescription())
        assertEquals("/jellyfin/System/Info/Public", server.next().url.encodedPath)
    }

    @Test
    fun `publicInfo rejects an answer without server id`() = runTest {
        server.enqueue(json("""{"foo":"bar"}"""))
        val client = MediaServerClient(http, server.url("/").toString(), MediaServerFlavor.EMBY, "dev")
        expectFailure<RemoteException> { client.publicInfo() }
    }
}
