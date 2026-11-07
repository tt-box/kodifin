package io.github.ttbox.kodifin.mediaserver

import io.github.ttbox.kodifin.model.Device
import io.github.ttbox.kodifin.model.DeviceKind
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/** Média en cours décrit pour un autre lecteur : flux direct et variante convertie par le serveur. */
class MediaServerTransferTest {
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() = server.close()

    private fun controller() = MediaServerController.create(
        Device("srv", "Serveur", DeviceKind.JELLYFIN, server.hostName, server.port, mapOf("token" to "tok", "userId" to "u1", "sessionId" to "s1")),
        OkHttpClient(),
        "dev-1",
    )

    private fun sessions(nowPlaying: String?, playState: String = """{"PositionTicks":6000000000,"MediaSourceId":"ms1"}""") =
        server.enqueue(
            MockResponse.Builder().body(
                """[{"Id":"s1","Client":"Web","DeviceName":"Salon","SupportsRemoteControl":true""" +
                    (nowPlaying?.let { ""","NowPlayingItem":$it""" } ?: "") + ""","PlayState":$playState}]""",
            ).build(),
        )

    @Test
    fun `video gives a static stream with its container and a transcoded mp4 from the position`() = runTest {
        sessions("""{"Id":"i1","Name":"Film","Type":"Movie","Container":"mkv,webm"}""")
        val media = controller().currentMedia()!!
        assertEquals("Film", media.title)
        assertEquals(600_000L, media.positionMs)
        val direct = media.url.toHttpUrl()
        assertEquals("/Videos/i1/stream.mkv", direct.encodedPath)
        assertEquals("true", direct.queryParameter("static"))
        assertEquals("tok", direct.queryParameter("api_key"))
        assertEquals("ms1", direct.queryParameter("MediaSourceId"))
        val converted = media.transcodedUrl!!.toHttpUrl()
        assertEquals("/Videos/i1/stream.mp4", converted.encodedPath)
        assertEquals("h264", converted.queryParameter("VideoCodec"))
        assertEquals("aac", converted.queryParameter("AudioCodec"))
        assertEquals("6000000000", converted.queryParameter("StartTimeTicks"))
        assertEquals("false", converted.queryParameter("static"))
    }

    @Test
    fun `audio converts to mp3 and an unsafe container is ignored`() = runTest {
        sessions("""{"Id":"a1","Name":"Titre","Type":"Audio","Container":"../x"}""", playState = """{"PositionTicks":0}""")
        val media = controller().currentMedia()!!
        assertEquals("/Audio/a1/stream", media.url.toHttpUrl().encodedPath)
        val converted = media.transcodedUrl!!.toHttpUrl()
        assertEquals("/Audio/a1/stream.mp3", converted.encodedPath)
        assertNull(converted.queryParameter("StartTimeTicks"))
        assertNull(media.url.toHttpUrl().queryParameter("MediaSourceId"))
    }

    @Test
    fun `nothing playing gives null`() = runTest {
        sessions(null)
        assertNull(controller().currentMedia())
    }
}
