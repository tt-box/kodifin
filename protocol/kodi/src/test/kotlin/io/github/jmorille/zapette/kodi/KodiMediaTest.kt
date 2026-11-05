package io.github.jmorille.zapette.kodi

import io.github.jmorille.zapette.model.Device
import io.github.jmorille.zapette.model.DeviceKind
import io.github.jmorille.zapette.model.MediaEntry
import io.github.jmorille.zapette.model.MediaKind
import io.github.jmorille.zapette.model.SeriesContext
import io.github.jmorille.zapette.testing.assertNoRequest
import io.github.jmorille.zapette.testing.awaitRequest
import io.github.jmorille.zapette.testing.release
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.Credentials
import okhttp3.OkHttpClient
import okio.Buffer
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.nio.charset.StandardCharsets
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun json(text: String) = Json.parseToJsonElement(text) as JsonObject

/** Série en cours, enrichissement des éléments, recherche et images (serveur JSON-RPC factice). */
class KodiMediaTest {
    private lateinit var server: MockWebServer
    private val http = OkHttpClient()
    private val stub = KodiStub()
    private lateinit var controller: KodiController
    private val device = Device("k", "Salon", DeviceKind.KODI, "10.0.0.2")

    /** Images servies sous `/image/…` ; le reste est du JSON-RPC traité par [stub]. */
    private val images = mutableMapOf<String, MockResponse>()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                if (request.url.encodedPath.startsWith("/image/")) {
                    images[request.url.encodedPath] ?: MockResponse.Builder().code(404).build()
                } else {
                    stub.dispatch(request)
                }
        }
        server.start()
        stub.results[KodiMethod.PLAYER_GET_ACTIVE] = """[{"playerid":1,"type":"video"}]"""
        controller = KodiController(device, KodiClient(KodiHttpTransport(http, server.url("/").toString(), "kodi", "sécret")))
    }

    @After
    fun tearDown() {
        server.close()
        http.release()
    }

    private fun paramsOf(method: String): JsonObject? = synchronized(stub.calls) { stub.calls.single { it.first == method }.second }

    // --- État de lecture et série en cours --------------------------------------

    @Test
    fun `snapshot reports series, season and episode for an episode only`() = runTest {
        stub.results[KodiMethod.APP_GET_PROPERTIES] = """{"volume":10,"muted":false}"""
        stub.results[KodiMethod.PLAYER_GET_PROPERTIES] = """{"speed":1}"""
        stub.results[KodiMethod.PLAYER_GET_ITEM] = """{"item":{"id":41,"type":"episode","label":"f.mkv",
            "title":"Épisode 2","showtitle":"Série","season":2,"episode":10,"tvshowid":3}}"""
        val state = controller.snapshot()
        assertEquals("Épisode 2", state.title)
        assertEquals("Série", state.seriesTitle)
        assertEquals(2, state.season)
        assertEquals(10, state.episode)
        assertEquals(
            json("""{"playerid":1,"properties":["title","showtitle","season","episode","tvshowid","artist","album","art","thumbnail","fanart"]}"""),
            paramsOf(KodiMethod.PLAYER_GET_ITEM),
        )

        // Film : Kodi renvoie showtitle vide et -1 pour saison et épisode.
        stub.results[KodiMethod.PLAYER_GET_ITEM] = """{"item":{"id":7,"type":"movie","label":"f.mkv",
            "title":"Alien","showtitle":"","season":-1,"episode":-1,"tvshowid":-1}}"""
        val movie = controller.snapshot()
        assertEquals("Alien", movie.title)
        assertNull(movie.seriesTitle)
        assertNull(movie.season)
        assertNull(movie.episode)
    }

    @Test
    fun `snapshot picks series poster and fanart, song artwork and artist`() = runTest {
        stub.results[KodiMethod.APP_GET_PROPERTIES] = """{"volume":10,"muted":false}"""
        stub.results[KodiMethod.PLAYER_GET_PROPERTIES] = """{"speed":1}"""
        stub.results[KodiMethod.PLAYER_GET_ITEM] = """{"item":{"id":41,"type":"episode","label":"x","title":"É",
            "art":{"thumb":"image://ep/","tvshow.poster":"image://poster/","tvshow.fanart":"image://fanart/"}}}"""
        var state = controller.snapshot()
        assertEquals("image://poster/", state.artworkKey)
        assertEquals("image://fanart/", state.backdropKey)

        stub.results[KodiMethod.PLAYER_GET_ITEM] = """{"item":{"id":5,"type":"song","label":"x","title":"Chanson",
            "artist":["", "Groupe"],"album":"Album","thumbnail":"image://cover/","fanart":"/pas/une/image",
            "art":{"artist.fanart":"image://artist/"}}}"""
        state = controller.snapshot()
        assertEquals("Groupe", state.artist)
        assertEquals("Album", state.album)
        assertEquals("image://cover/", state.artworkKey)
        assertEquals("image://artist/", state.backdropKey)

        stub.results[KodiMethod.PLAYER_GET_ITEM] = """{"item":{"id":5,"type":"movie","label":"x","title":"Film"}}"""
        state = controller.snapshot()
        assertNull(state.artworkKey)
        assertNull(state.backdropKey)
        assertNull(state.artist)
    }

    @Test
    fun `current series lists seasons of the playing episode`() = runTest {
        stub.results[KodiMethod.PLAYER_GET_ITEM] = """{"item":{"id":41,"type":"episode","label":"x",
            "title":"Épisode 2","showtitle":"Série","season":2,"episode":2,"tvshowid":3}}"""
        stub.results[KodiMethod.VIDEO_GET_SEASONS] = """{"seasons":[
            {"season":1,"label":"Saison 1","showtitle":"Série","playcount":1,"art":{"poster":"image://s1/","fanart":"image://f/"}},
            {"season":2,"label":"Saison 2","showtitle":"Série","playcount":0,"thumbnail":"image://s2/"}
        ]}"""
        assertEquals(
            SeriesContext(
                seriesId = "tvshowid:3",
                title = "Série",
                seasons = listOf(
                    MediaEntry(
                        "season:3:1", "Saison 1", "Série", browsable = true, kind = MediaKind.SEASON,
                        imageKey = "image://s1/", season = 1, watched = true,
                    ),
                    MediaEntry(
                        "season:3:2", "Saison 2", "Série", browsable = true, kind = MediaKind.SEASON,
                        imageKey = "image://s2/", season = 2, watched = false,
                    ),
                ),
                currentSeasonId = "season:3:2",
                currentEpisodeId = "episodeid:41",
            ),
            controller.currentSeries(),
        )
        assertEquals(
            json("""{"tvshowid":3,"properties":["season","showtitle","playcount","art","thumbnail"],"limits":{"start":0,"end":500}}"""),
            paramsOf(KodiMethod.VIDEO_GET_SEASONS),
        )
    }

    @Test
    fun `current series is null for a movie, an audio player or no player`() = runTest {
        stub.results[KodiMethod.PLAYER_GET_ITEM] = """{"item":{"id":7,"type":"movie","title":"Alien","tvshowid":-1}}"""
        assertNull(controller.currentSeries())
        stub.results[KodiMethod.PLAYER_GET_ACTIVE] = """[{"playerid":0,"type":"audio"}]"""
        assertNull(controller.currentSeries())
        stub.results[KodiMethod.PLAYER_GET_ACTIVE] = "[]"
        assertNull(controller.currentSeries())
        assertTrue(stub.calls.none { it.first == KodiMethod.VIDEO_GET_SEASONS })
        assertEquals(1, stub.calls.count { it.first == KodiMethod.PLAYER_GET_ITEM })
    }

    // --- Enrichissement ---------------------------------------------------------

    @Test
    fun `entries carry kind, year, image and watched state`() = runTest {
        stub.results[KodiMethod.VIDEO_GET_MOVIES] = """{"movies":[
            {"movieid":1,"label":"Alien","year":1979,"playcount":2,"thumbnail":"image://t1/","art":{"poster":"image://p1/","fanart":"image://f1/"}},
            {"movieid":2,"label":"Sans affiche","year":0,"playcount":0,"thumbnail":"","art":{"fanart":"image://f2/"}}
        ]}"""
        assertEquals(
            listOf(
                MediaEntry("movieid:1", "Alien", "1979", kind = MediaKind.MOVIE, imageKey = "image://p1/", year = 1979, watched = true),
                MediaEntry("movieid:2", "Sans affiche", kind = MediaKind.MOVIE, imageKey = "image://f2/", watched = false),
            ),
            controller.browse("movies"),
        )
    }

    @Test
    fun `season episodes show code, duration, thumbnail and watched state`() = runTest {
        stub.results[KodiMethod.VIDEO_GET_EPISODES] = """{"episodes":[
            {"episodeid":40,"label":"Pilote","season":1,"episode":3,"runtime":2520,"playcount":1,
             "thumbnail":"image://e40/","art":{"poster":"image://p/"}},
            {"episodeid":41,"label":"Long","season":1,"episode":4,"runtime":3900,"playcount":0},
            {"episodeid":42,"label":"Spécial","season":0,"episode":0}
        ]}"""
        assertEquals(
            listOf(
                MediaEntry(
                    "episodeid:40", "Pilote", "S01E03 · 42 min", kind = MediaKind.EPISODE,
                    imageKey = "image://e40/", season = 1, episode = 3, watched = true,
                ),
                MediaEntry("episodeid:41", "Long", "S01E04 · 1 h 05", kind = MediaKind.EPISODE, season = 1, episode = 4, watched = false),
                MediaEntry("episodeid:42", "Spécial", kind = MediaKind.EPISODE, season = 0, episode = 0),
            ),
            controller.browse("season:3:1"),
        )
    }

    @Test
    fun `queue entries are enriched`() = runTest {
        stub.results[KodiMethod.PLAYER_GET_PROPERTIES] = """{"playlistid":1,"position":0}"""
        stub.results[KodiMethod.PLAYLIST_GET_ITEMS] = """{"items":[
            {"id":3,"type":"episode","title":"Pilote","showtitle":"Série","season":1,"episode":1,"playcount":0,
             "thumbnail":"image://e3/","art":{"poster":"image://p/"}},
            {"id":7,"type":"movie","title":"Alien","year":1979,"season":-1,"episode":-1,"playcount":3,"art":{"poster":"image://p7/"}}
        ]}"""
        assertEquals(
            listOf(
                MediaEntry(
                    "episodeid:3", "Pilote", "Série", kind = MediaKind.EPISODE, imageKey = "image://e3/",
                    season = 1, episode = 1, watched = false,
                ),
                MediaEntry("movieid:7", "Alien", kind = MediaKind.MOVIE, imageKey = "image://p7/", year = 1979, watched = true),
            ),
            controller.queue(),
        )
    }

    // --- Recherche --------------------------------------------------------------

    private fun stubSearchResults() {
        stub.results[KodiMethod.VIDEO_GET_MOVIES] = """{"movies":[{"movieid":1,"label":"Alien","year":1979}]}"""
        stub.results[KodiMethod.VIDEO_GET_TV_SHOWS] = """{"tvshows":[{"tvshowid":3,"label":"Alien Nation","art":{"fanart":"image://f3/"}}]}"""
        stub.results[KodiMethod.VIDEO_GET_EPISODES] = """{"episodes":[
            {"episodeid":40,"label":"L'alien","showtitle":"Série","season":1,"episode":3,"runtime":2520}]}"""
        stub.results[KodiMethod.AUDIO_GET_ARTISTS] = """{"artists":[{"artistid":5,"label":"Alien Ant Farm","thumbnail":"","fanart":"image://a5/"}]}"""
        stub.results[KodiMethod.AUDIO_GET_ALBUMS] = """{"albums":[{"albumid":7,"label":"Aliens","artist":["X"],"year":2001}]}"""
        stub.results[KodiMethod.AUDIO_GET_SONGS] = """{"songs":[{"songid":9,"label":"Alien","artist":["A"],"album":"B"}]}"""
        stub.results[KodiMethod.PVR_GET_CHANNELS] = """{"channels":[
            {"channelid":8,"label":"ALIEN TV","thumbnail":"image://c8/"},{"channelid":9,"label":"Arte"}]}"""
    }

    @Test
    fun `search queries every kind with native filters and groups results by kind`() = runTest {
        stubSearchResults()
        val results = controller.search("alien", limit = 20)
        assertEquals(
            listOf(
                MediaEntry("movieid:1", "Alien", "1979", kind = MediaKind.MOVIE, year = 1979),
                MediaEntry("tvshowid:3", "Alien Nation", browsable = true, kind = MediaKind.SERIES, imageKey = "image://f3/"),
                MediaEntry(
                    "episodeid:40", "L'alien", "Série · S01E03 · 42 min", kind = MediaKind.EPISODE,
                    season = 1, episode = 3,
                ),
                MediaEntry("artistid:5", "Alien Ant Farm", browsable = true, kind = MediaKind.ARTIST, imageKey = "image://a5/"),
                MediaEntry("albumid:7", "Aliens", "X", browsable = true, kind = MediaKind.ALBUM, year = 2001),
                MediaEntry("songid:9", "Alien", "A — B", kind = MediaKind.SONG),
            ),
            results,
        )
        val sort = """"sort":{"method":"label","ignorearticle":true}"""
        val limits = """"limits":{"start":0,"end":20}"""
        fun filter(field: String) = """"filter":{"field":"$field","operator":"contains","value":"alien"}"""
        assertEquals(
            json("""{${filter("title")},"properties":["year","playcount","art","thumbnail"],$limits,$sort}"""),
            paramsOf(KodiMethod.VIDEO_GET_MOVIES),
        )
        assertEquals(
            json("""{${filter("title")},"properties":["year","playcount","art","thumbnail"],$limits,$sort}"""),
            paramsOf(KodiMethod.VIDEO_GET_TV_SHOWS),
        )
        assertEquals(
            json("""{${filter("title")},"properties":["season","episode","playcount","runtime","art","thumbnail","plot","showtitle"],$limits,$sort}"""),
            paramsOf(KodiMethod.VIDEO_GET_EPISODES),
        )
        assertEquals(json("""{${filter("artist")},"properties":["thumbnail","fanart"],$limits,$sort}"""), paramsOf(KodiMethod.AUDIO_GET_ARTISTS))
        assertEquals(
            json("""{${filter("album")},"properties":["artist","year","playcount","art","thumbnail"],$limits,$sort}"""),
            paramsOf(KodiMethod.AUDIO_GET_ALBUMS),
        )
        assertEquals(
            json("""{${filter("title")},"properties":["artist","album","playcount","thumbnail"],$limits,$sort}"""),
            paramsOf(KodiMethod.AUDIO_GET_SONGS),
        )
        // PVR non détecté : pas de recherche de chaînes.
        assertTrue(stub.calls.none { it.first == KodiMethod.PVR_GET_CHANNELS })
    }

    @Test
    fun `channels are searchable once PVR is detected and filtered locally by name`() = runTest {
        stubSearchResults()
        stub.results[KodiMethod.PING] = "\"pong\""
        stub.results[KodiMethod.VERSION] = """{"version":{"major":13,"minor":5,"patch":0}}"""
        stub.results[KodiMethod.PVR_GET_CHANNEL_GROUPS] = """{"channelgroups":[{"channelgroupid":1,"label":"Toutes"}]}"""
        assertEquals(KodiController.SEARCH_KINDS, controller.searchKinds)
        controller.connect()
        assertEquals(KodiController.SEARCH_KINDS + MediaKind.CHANNEL, controller.searchKinds)
        assertEquals(
            listOf(MediaEntry("channelid:8", "ALIEN TV", kind = MediaKind.CHANNEL, imageKey = "image://c8/")),
            controller.search("Alien", setOf(MediaKind.CHANNEL)),
        )
        assertEquals(json("""{"channelgroupid":"alltv","properties":["thumbnail"]}"""), paramsOf(KodiMethod.PVR_GET_CHANNELS))
        // Toutes les natures : les chaînes viennent en dernier.
        assertEquals(MediaKind.CHANNEL, controller.search("alien").last().kind)
    }

    @Test
    fun `search honours requested kinds, escapes the query and tolerates a refused kind`() = runTest {
        stubSearchResults()
        stub.errors[KodiMethod.AUDIO_GET_SONGS] = KodiClient.INVALID_PARAMS
        val query = """Al"i\en ' <&>"""
        val results = controller.search("  $query  ", setOf(MediaKind.SONG, MediaKind.MOVIE, MediaKind.CHANNEL), limit = 5)
        // CHANNEL ignoré (PVR non détecté), SONG refusé par le serveur : seuls les films restent.
        assertEquals(listOf("movieid:1"), results.map { it.id })
        assertEquals(
            setOf(KodiMethod.VIDEO_GET_MOVIES, KodiMethod.AUDIO_GET_SONGS),
            stub.calls.map { it.first }.toSet(),
        )
        val filter = paramsOf(KodiMethod.VIDEO_GET_MOVIES)!!["filter"]!!.jsonObject
        assertEquals(query, filter["value"]!!.jsonPrimitive.content)
        assertEquals(json("""{"start":0,"end":5}"""), paramsOf(KodiMethod.VIDEO_GET_MOVIES)!!["limits"])
    }

    @Test
    fun `blank query returns nothing without calling Kodi`() = runTest {
        assertEquals(emptyList(), controller.search("   "))
        assertEquals(emptyList(), controller.search("alien", limit = 0))
        assertTrue(stub.calls.isEmpty())
    }

    // --- Images -----------------------------------------------------------------

    @Test
    fun `image is downloaded from the device web server with basic auth`() = runTest {
        val key = "image://smb%3a%2f%2fnas%2fa b.jpg/"
        val bytes = byteArrayOf(1, 2, 3, 4)
        images["/image/image%3A%2F%2Fsmb%253a%252f%252fnas%252fa%20b.jpg%2F"] =
            MockResponse.Builder().addHeader("Content-Type", "image/jpeg").body(Buffer().write(bytes)).build()
        assertContentEquals(bytes, controller.image(key, maxWidth = 300))
        val request = server.awaitRequest()
        assertEquals("GET", request.method)
        assertEquals(Credentials.basic("kodi", "sécret", StandardCharsets.UTF_8), request.headers["Authorization"])
    }

    @Test
    fun `image refuses foreign keys, errors, non images and oversized bodies`() = runTest {
        // Clé hors `image://` (URL externe, chemin) : aucune requête.
        assertNull(controller.image("http://ailleurs.example/x.jpg", 100))
        assertNull(controller.image("/etc/passwd", 100))
        server.assertNoRequest()
        // 404.
        assertNull(controller.image("image://absent/", 100))
        // Type non image.
        images["/image/image%3A%2F%2Fhtml%2F"] = MockResponse.Builder().addHeader("Content-Type", "text/html").body("<html/>").build()
        assertNull(controller.image("image://html/", 100))
        // Trop volumineux pour la borne.
        images["/image/image%3A%2F%2Fgros%2F"] =
            MockResponse.Builder().addHeader("Content-Type", "image/png").body(Buffer().write(ByteArray(64))).build()
        val small = KodiImageFetcher(http, server.url("/").toString(), maxBytes = 16)
        assertNull(small.fetch("image://gros/"))
        assertEquals(64, KodiImageFetcher(http, server.url("/").toString()).fetch("image://gros/")?.size)
    }

    @Test
    fun `injected fetcher serves images for other transports`() = runTest {
        val asked = mutableListOf<String>()
        val ws = KodiController(
            device,
            KodiClient(object : KodiTransport {
                override suspend fun call(method: String, params: JsonObject?) = error("inutilisé")
            }),
            imageFetcher = { key -> asked += key; byteArrayOf(9) },
        )
        assertContentEquals(byteArrayOf(9), ws.image("image://x/", 0))
        assertNull(ws.image("http://x/", 0))
        assertEquals(listOf("image://x/"), asked)
        // Sans transport HTTP ni téléchargeur : pas d'image.
        val none = KodiController(device, KodiClient(object : KodiTransport {
            override suspend fun call(method: String, params: JsonObject?) = error("inutilisé")
        }))
        assertNull(none.image("image://x/", 0))
    }
}
