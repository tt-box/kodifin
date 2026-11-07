package io.github.ttbox.kodifin.kodi

import io.github.ttbox.kodifin.model.Device
import io.github.ttbox.kodifin.model.DeviceKind
import io.github.ttbox.kodifin.testing.release
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Média en cours servi par le serveur web de Kodi pour un autre lecteur (Files.PrepareDownload). */
class KodiTransferTest {
    private lateinit var server: MockWebServer
    private val http = OkHttpClient()
    private val stub = KodiStub()
    private val device = Device("k", "Salon", DeviceKind.KODI, "10.0.0.2")

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = stub
        server.start()
        stub.results[KodiMethod.PLAYER_GET_ACTIVE] = """[{"playerid":1,"type":"video"}]"""
        stub.results[KodiMethod.APP_GET_PROPERTIES] = """{"volume":10,"muted":false}"""
        stub.results[KodiMethod.PLAYER_GET_PROPERTIES] = """{"speed":1,"time":{"hours":0,"minutes":10,"seconds":0,"milliseconds":0}}"""
    }

    @After
    fun tearDown() {
        server.close()
        http.release()
    }

    private fun controller(web: String? = "http://kodi:secret@10.0.0.2:8080/") = KodiController(
        device, KodiClient(KodiHttpTransport(http, server.url("/").toString(), null, null)), webBaseUrl = web,
    )

    private fun item(file: String) {
        stub.results[KodiMethod.PLAYER_GET_ITEM] = """{"item":{"id":7,"type":"movie","label":"x","title":"Film","file":"$file"}}"""
    }

    @Test
    fun `library file is served through the vfs path with credentials`() = runTest {
        item("smb://nas/films/film.mkv")
        stub.results[KodiMethod.FILES_PREPARE_DOWNLOAD] = """{"details":{"path":"vfs/smb%3a%2f%2fnas%2ffilms%2ffilm.mkv"},"mode":"redirect","protocol":"http"}"""
        val media = controller().currentMedia()!!
        assertEquals("http://kodi:secret@10.0.0.2:8080/vfs/smb%3a%2f%2fnas%2ffilms%2ffilm.mkv", media.url)
        assertEquals("Film", media.title)
        assertEquals(600_000L, media.positionMs)
    }

    @Test
    fun `http streams are passed through, other cases give null`() = runTest {
        item("https://cdn/flux.m3u8")
        assertEquals("https://cdn/flux.m3u8", controller(web = null).currentMedia()!!.url)

        item("plugin://plugin.video.x/play")
        stub.results[KodiMethod.FILES_PREPARE_DOWNLOAD] = """{"details":{},"mode":"direct","protocol":"plugin"}"""
        assertNull(controller().currentMedia())

        item("/storage/film.mkv")
        assertNull(controller(web = null).currentMedia())

        stub.errors[KodiMethod.FILES_PREPARE_DOWNLOAD] = -32602
        assertNull(controller().currentMedia())

        stub.results[KodiMethod.PLAYER_GET_ACTIVE] = "[]"
        assertNull(controller().currentMedia())
    }
}
