package io.github.jmorille.zapette.kodi

import io.github.jmorille.zapette.model.Device
import io.github.jmorille.zapette.model.DeviceKind
import io.github.jmorille.zapette.testing.release
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Fiches d'épisode et de film (VideoLibrary.GetEpisodeDetails / GetMovieDetails). */
class KodiInfoTest {
    private lateinit var server: MockWebServer
    private val http = OkHttpClient()
    private val stub = KodiStub()
    private lateinit var controller: KodiController

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = stub
        server.start()
        controller = KodiController(Device("k", "Salon", DeviceKind.KODI, "10.0.0.2"), KodiClient(KodiHttpTransport(http, server.url("/").toString(), null, null)))
    }

    @After
    fun tearDown() {
        server.close()
        http.release()
    }

    @Test
    fun `episode details give overview, air date, crew and streams`() = runTest {
        stub.results[KodiMethod.VIDEO_GET_EPISODE_DETAILS] = """{"episodedetails":{"episodeid":41,"title":"I'm Home",
            "plot":" Le bataillon rentre. ","showtitle":"Attack on Titan","season":2,"episode":2,"firstaired":"2017-04-08",
            "rating":8.2,"runtime":1440,"director":["Araki"],"writer":["Kobayashi",""],
            "cast":[{"name":"Yuki Kaji","role":"Eren"},{"name":"Sans rôle"},{"role":"Anonyme"}],
            "art":{"thumb":"image://ep/"},
            "streamdetails":{"video":[{"codec":"hevc","height":1080}],"audio":[{"codec":"aac","language":"jpn","channels":6}]}}}"""
        val info = controller.itemInfo("episodeid:41")!!
        assertEquals("I'm Home", info.title)
        assertEquals("Le bataillon rentre.", info.overview)
        assertEquals("Attack on Titan", info.seriesTitle)
        assertEquals(2, info.season)
        assertEquals("2017-04-08", info.airDate)
        assertEquals(2017, info.year)
        assertEquals(1_440_000L, info.runtimeMs)
        assertEquals(listOf("Araki"), info.directors)
        assertEquals(listOf("Kobayashi"), info.writers)
        assertEquals(listOf("Yuki Kaji (Eren)", "Sans rôle"), info.cast)
        assertEquals("1080p · HEVC", info.video)
        assertEquals("jpn · AAC · 5.1", info.audio)
        assertEquals("image://ep/", info.imageKey)
    }

    @Test
    fun `movie details and now playing info`() = runTest {
        stub.results[KodiMethod.VIDEO_GET_MOVIE_DETAILS] = """{"moviedetails":{"movieid":7,"title":"Alien","plot":"Espace.",
            "year":1979,"premiered":"1979-05-25","genre":["SF"],"studio":["Fox"],"mpaa":"PG-13",
            "streamdetails":{"audio":[{"codec":"dts","channels":8}]}}}"""
        val movie = controller.itemInfo("movieid:7")!!
        assertNull(movie.seriesTitle)
        assertEquals(listOf("SF"), movie.genres)
        assertEquals(listOf("Fox"), movie.studios)
        assertEquals("PG-13", movie.officialRating)
        assertEquals("DTS · 7.1", movie.audio)
        assertNull(movie.video)

        stub.results[KodiMethod.PLAYER_GET_ACTIVE] = """[{"playerid":1,"type":"video"}]"""
        stub.results[KodiMethod.PLAYER_GET_ITEM] = """{"item":{"id":7,"type":"movie","label":"Alien"}}"""
        assertEquals("Alien", controller.nowPlayingInfo()!!.title)
        stub.results[KodiMethod.PLAYER_GET_ITEM] = """{"item":{"id":3,"type":"song","label":"x"}}"""
        assertNull(controller.nowPlayingInfo())
        stub.results[KodiMethod.PLAYER_GET_ACTIVE] = "[]"
        assertNull(controller.nowPlayingInfo())
    }

    @Test
    fun `other items, bad ids and kodi errors give null`() = runTest {
        assertNull(controller.itemInfo("songid:3"))
        assertNull(controller.itemInfo("episodeid:abc"))
        assertNull(controller.itemInfo("sans-separateur"))
        stub.errors[KodiMethod.VIDEO_GET_EPISODE_DETAILS] = -32602
        assertNull(controller.itemInfo("episodeid:9"))
        stub.errors.clear()
        stub.results[KodiMethod.VIDEO_GET_EPISODE_DETAILS] = """{"autre":{}}"""
        assertNull(controller.itemInfo("episodeid:9"))
    }
}
