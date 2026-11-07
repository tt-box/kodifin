package io.github.ttbox.kodifin.mediaserver

import io.github.ttbox.kodifin.mediaserver.MediaServerController.Companion.imageKey
import io.github.ttbox.kodifin.mediaserver.MediaServerController.Companion.mediaKind
import io.github.ttbox.kodifin.mediaserver.MediaServerController.Companion.parseImageKey
import io.github.ttbox.kodifin.mediaserver.MediaServerController.Companion.toEntry
import io.github.ttbox.kodifin.model.Device
import io.github.ttbox.kodifin.model.DeviceKind
import io.github.ttbox.kodifin.model.MediaEntry
import io.github.ttbox.kodifin.model.MediaKind
import io.github.ttbox.kodifin.model.RemoteError
import io.github.ttbox.kodifin.model.RemoteException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Séries, recherche, images et enrichissement des entrées de bibliothèque. */
class MediaServerMediaTest {
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() = server.close()

    private fun controller(
        kind: DeviceKind = DeviceKind.JELLYFIN,
        extras: Map<String, String> = mapOf("token" to "tok", "userId" to "u1", "sessionId" to "s1"),
    ) = MediaServerController.create(Device("srv", "Serveur", kind, server.hostName, server.port, extras), OkHttpClient(), "dev-1")

    private fun json(body: String) = server.enqueue(MockResponse.Builder().body(body).build())

    private fun RecordedRequest.path() = url.encodedPath

    private fun session(nowPlaying: String) =
        """[{"Id":"s1","Client":"Jellyfin Web","DeviceName":"Salon","SupportsRemoteControl":true,
           "NowPlayingItem":$nowPlaying,"PlayState":{"PositionTicks":0}}]"""

    private val episodePlaying = """{"Id":"e5","Name":"Le Retour","Type":"Episode","SeriesName":"Dark",
        "SeriesId":"se1","SeasonId":"sa2","ParentIndexNumber":2,"IndexNumber":5,"RunTimeTicks":36000000000}"""

    @Test
    fun `playback state exposes series season and episode`() = runTest {
        val c = controller()
        json(session(episodePlaying))
        val state = c.playbackState().first()
        assertEquals("Le Retour", state.title)
        assertEquals("Dark", state.seriesTitle)
        assertEquals(2, state.season)
        assertEquals(5, state.episode)

        json(session("""{"Id":"m1","Name":"Alien","Type":"Movie","SeriesName":"","IndexNumber":3}"""))
        val movie = c.playbackState().first()
        assertEquals("Alien", movie.title)
        assertNull(movie.seriesTitle)
        assertNull(movie.season)
        assertNull(movie.episode)
    }

    @Test
    fun `current series lists seasons then episodes of a season`() = runTest {
        val c = controller()
        json(session(episodePlaying))
        json(
            """{"Items":[
                 {"Id":"sa1","Name":"Saison 1","Type":"Season","SeriesId":"se1","IndexNumber":1,"ImageTags":{"Primary":"t1"}},
                 {"Id":"sa2","Name":"Saison 2","Type":"Season","SeriesId":"se1","IndexNumber":2,"SeriesPrimaryImageTag":"st"}
               ]}""",
        )
        val series = c.currentSeries()!!
        assertEquals("se1", series.seriesId)
        assertEquals("Dark", series.title)
        assertEquals("sa2", series.currentSeasonId)
        assertEquals("e5", series.currentEpisodeId)
        assertEquals(
            listOf(
                MediaEntry("sa1", "Saison 1", browsable = true, kind = MediaKind.SEASON, imageKey = "sa1/Primary/t1"),
                MediaEntry("sa2", "Saison 2", browsable = true, kind = MediaKind.SEASON, imageKey = "se1/Primary/st"),
            ),
            series.seasons,
        )
        assertEquals("/Sessions", server.next().path())
        val seasons = server.next()
        assertEquals("/Shows/se1/Seasons", seasons.path())
        assertEquals("u1", seasons.url.queryParameter("userId"))

        // Épisodes dans le désordre : triés par numéro.
        json(
            """{"Items":[
                 {"Id":"e6","Name":"Six","Type":"Episode","SeriesName":"Dark","ParentIndexNumber":2,"IndexNumber":6,
                  "RunTimeTicks":14400000000,"ImageTags":{"Primary":"p6"},"UserData":{"Played":false}},
                 {"Id":"e5","Name":"Le Retour","Type":"Episode","SeriesName":"Dark","ParentIndexNumber":2,"IndexNumber":5,
                  "SeriesId":"se1","SeriesPrimaryImageTag":"st","UserData":{"Played":true}}
               ]}""",
        )
        val episodes = c.browse("sa2", offset = 0, limit = 50)
        assertEquals(
            listOf(
                MediaEntry("e5", "Le Retour", "S02E05", kind = MediaKind.EPISODE, imageKey = "se1/Primary/st", season = 2, episode = 5, watched = true),
                MediaEntry("e6", "Six", "S02E06 · 24 min", kind = MediaKind.EPISODE, imageKey = "e6/Primary/p6", season = 2, episode = 6, watched = false),
            ),
            episodes,
        )
        val r = server.next()
        assertEquals("/Shows/se1/Episodes", r.path())
        assertEquals("sa2", r.url.queryParameter("seasonId"))
        assertEquals("u1", r.url.queryParameter("userId"))
        assertEquals("50", r.url.queryParameter("Limit"))
        assertEquals("PrimaryImageAspectRatio,Overview", r.url.queryParameter("Fields"))

        // Dossier inconnu : parcours générique.
        json("""{"Items":[]}""")
        c.browse("autre")
        val generic = server.next()
        assertEquals("/Items", generic.path())
        assertEquals("autre", generic.url.queryParameter("ParentId"))
    }

    @Test
    fun `seasons met while browsing a series are listed through the episodes route`() = runTest {
        val c = controller(DeviceKind.EMBY)
        json("""{"Items":[{"Id":"sa1","Name":"Saison 1","Type":"Season","SeriesId":"se1"}]}""")
        c.browse("se1")
        assertEquals("/Users/u1/Items", server.next().path())
        json("""{"Items":[]}""")
        c.browse("sa1")
        val r = server.next()
        assertEquals("/Shows/se1/Episodes", r.path())
        assertEquals("u1", r.url.queryParameter("UserId"))
        assertEquals("sa1", r.url.queryParameter("seasonId"))

        // Route absente (404) : repli sur le parcours générique.
        server.enqueue(MockResponse.Builder().code(404).build())
        json("""{"Items":[{"Id":"x","Name":"X"}]}""")
        assertEquals(listOf("x"), c.browse("sa1").map { it.id })
        server.next()
        val fallback = server.next()
        assertEquals("/Users/u1/Items", fallback.path())
        assertEquals("sa1", fallback.url.queryParameter("ParentId"))
    }

    @Test
    fun `current series is null for a movie and read from the item when the session omits it`() = runTest {
        val c = controller()
        json(session("""{"Id":"m1","Name":"Alien","Type":"Movie"}"""))
        assertNull(c.currentSeries())
        server.next()

        json(session("""{"Id":"e5","Name":"Le Retour","Type":"Episode"}"""))
        json("""{"Id":"e5","Name":"Le Retour","Type":"Episode","SeriesId":"se1","SeasonId":"sa2","SeriesName":"Dark"}""")
        json("""{"Items":[]}""")
        val series = c.currentSeries()!!
        assertEquals("se1", series.seriesId)
        assertEquals("Dark", series.title)
        assertEquals("sa2", series.currentSeasonId)
        server.next()
        assertEquals("/Items/e5", server.next().path())
        assertEquals("/Shows/se1/Seasons", server.next().path())
    }

    @Test
    fun `entries are enriched with kind year watched and image`() {
        val movie = ItemDto(
            id = "m1", name = "Alien", type = "Movie", productionYear = 1979,
            imageTags = mapOf("Thumb" to "th"), backdropImageTags = listOf("bd"),
            userData = UserDataDto(played = true),
        ).toEntry()
        assertEquals(MediaEntry("m1", "Alien", "1979", kind = MediaKind.MOVIE, imageKey = "m1/Thumb/th", year = 1979, watched = true), movie)
        assertEquals("m1/Backdrop/bd", ItemDto(id = "m1", backdropImageTags = listOf("bd")).imageKey())
        assertEquals("se1/Primary", ItemDto(id = "e1", type = "Episode", seriesId = "se1").imageKey())
        assertNull("film sans image", ItemDto(id = "m1", type = "Movie", seriesId = "se1").imageKey())

        assertEquals(MediaKind.ARTIST, mediaKind("MusicArtist"))
        assertEquals(MediaKind.ALBUM, mediaKind("MusicAlbum"))
        assertEquals(MediaKind.SONG, mediaKind("Audio"))
        assertEquals(MediaKind.CHANNEL, mediaKind("TvChannel"))
        assertEquals(MediaKind.FOLDER, mediaKind("CollectionFolder"))
        assertEquals(MediaKind.OTHER, mediaKind("Book"))
        assertNull(mediaKind(null))
    }

    @Test
    fun `queue entries are enriched`() = runTest {
        val c = controller()
        json(
            """[{"Id":"s1","SupportsRemoteControl":true,"NowPlayingItem":{"Id":"a"},
                "NowPlayingQueue":[{"Id":"a","PlaylistItemId":"p1"}]}]""",
        )
        json("""{"Items":[{"Id":"a","Name":"Un","Type":"Audio","ImageTags":{"Primary":"x"}}]}""")
        assertEquals(listOf(MediaEntry("a", "Un", kind = MediaKind.SONG, imageKey = "a/Primary/x")), c.queue())
    }

    @Test
    fun `search uses items with search term and artists route`() = runTest {
        val c = controller()
        assertEquals(
            setOf(MediaKind.MOVIE, MediaKind.SERIES, MediaKind.EPISODE, MediaKind.ARTIST, MediaKind.ALBUM, MediaKind.SONG, MediaKind.CHANNEL),
            c.searchKinds,
        )
        assertEquals(emptyList<MediaEntry>(), c.search("   "))
        assertEquals(0, server.requestCount)

        json("""{"Items":[{"Id":"m1","Name":"Alien","Type":"Movie","ProductionYear":1979},{"Id":"e1","Name":"Alien ?","Type":"Episode"}]}""")
        val found = c.search(" alien ", setOf(MediaKind.MOVIE, MediaKind.EPISODE), limit = 20)
        assertEquals(listOf("m1", "e1"), found.map { it.id })
        assertEquals(listOf(MediaKind.MOVIE, MediaKind.EPISODE), found.map { it.kind })
        val r = server.next()
        assertEquals("/Items", r.path())
        assertEquals("u1", r.url.queryParameter("userId"))
        assertEquals("alien", r.url.queryParameter("searchTerm"))
        assertEquals("true", r.url.queryParameter("Recursive"))
        assertEquals("Movie,Episode", r.url.queryParameter("IncludeItemTypes"))
        assertEquals("20", r.url.queryParameter("Limit"))
        assertNull("pertinence du serveur", r.url.queryParameter("SortBy"))

        json("""{"Items":[{"Id":"ar1","Name":"Alice","Type":"MusicArtist"}]}""")
        json("""{"Items":[{"Id":"al1","Name":"Alice live","Type":"MusicAlbum"}]}""")
        val music = c.search("alice", setOf(MediaKind.ARTIST, MediaKind.ALBUM))
        assertEquals(listOf(MediaKind.ARTIST, MediaKind.ALBUM), music.map { it.kind })
        val artists = server.next()
        assertEquals("/Artists", artists.path())
        assertEquals("alice", artists.url.queryParameter("searchTerm"))
        assertEquals("MusicAlbum", server.next().url.queryParameter("IncludeItemTypes"))
    }

    @Test
    fun `emby search uses the user items route`() = runTest {
        val c = controller(DeviceKind.EMBY)
        json("""{"Items":[]}""")
        c.search("dark", setOf(MediaKind.SERIES))
        val r = server.next()
        assertEquals("/Users/u1/Items", r.path())
        assertEquals("Series", r.url.queryParameter("IncludeItemTypes"))
    }

    @Test
    fun `image is fetched with the auth header and never a token in the url`() = runTest {
        val c = controller()
        val bytes = byteArrayOf(1, 2, 3, 4)
        server.enqueue(MockResponse.Builder().body(Buffer().write(bytes)).build())
        assertArrayEquals(bytes, c.image("i1/Primary/abc", 300))
        val r = server.next()
        assertEquals("/Items/i1/Images/Primary", r.path())
        assertEquals("300", r.url.queryParameter("maxWidth"))
        assertEquals("85", r.url.queryParameter("quality"))
        assertEquals("abc", r.url.queryParameter("tag"))
        assertTrue(r.headers["Authorization"]!!.contains("Token=\"tok\""))
        assertFalse("jeton absent de l'URL", r.url.query.orEmpty().contains("tok", ignoreCase = true))
        assertNull(r.url.queryParameter("api_key"))

        server.enqueue(MockResponse.Builder().code(404).build())
        assertNull(c.image("i1/Thumb", 99_999))
        assertEquals("3840", server.next().url.queryParameter("maxWidth"))

        // Clés invalides : aucune requête.
        val before = server.requestCount
        assertNull(c.image("../Users/Primary", 100))
        assertNull(c.image("i1/Secret/abc", 100))
        assertNull(c.image("i1", 100))
        assertEquals(before, server.requestCount)
        assertNull(parseImageKey("i1/Primary/a/b"))
    }

    @Test
    fun `emby image carries the emby token header`() = runTest {
        val c = controller(DeviceKind.EMBY)
        server.enqueue(MockResponse.Builder().body("x").build())
        c.image("42/Primary", 200)
        val r = server.next()
        assertEquals("tok", r.headers["X-Emby-Token"])
        assertNull(r.url.queryParameter("api_key"))
    }

    @Test
    fun `oversized image and other host redirect are refused`() = runTest {
        val c = controller()
        server.enqueue(MockResponse.Builder().body(Buffer().write(ByteArray((MediaServerController.MAX_IMAGE_BYTES + 1).toInt()))).build())
        val big = expectFailure<RemoteException> { c.image("i1/Primary", 100) }
        assertEquals(RemoteError.ResponseTooLarge(server.url("/").host, MediaServerController.MAX_IMAGE_BYTES), big.reason)
        server.next()

        MockWebServer().use { other ->
            other.start()
            other.enqueue(MockResponse.Builder().body("ailleurs").build())
            server.enqueue(MockResponse.Builder().code(302).addHeader("Location", other.url("/img").toString()).build())
            val e = expectFailure<RemoteException> { c.image("i1/Primary", 100) }
            assertEquals(RemoteError.RedirectRefused, e.reason)
        }
    }
}
