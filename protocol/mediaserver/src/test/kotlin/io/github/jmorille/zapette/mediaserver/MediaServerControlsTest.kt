package io.github.jmorille.zapette.mediaserver

import io.github.jmorille.zapette.model.Device
import io.github.jmorille.zapette.model.DeviceKind
import io.github.jmorille.zapette.model.MediaEntry
import io.github.jmorille.zapette.model.MediaSection
import io.github.jmorille.zapette.model.MediaTrack
import io.github.jmorille.zapette.model.PlaybackTarget
import io.github.jmorille.zapette.model.RemoteException
import io.github.jmorille.zapette.model.RemoteKey
import io.github.jmorille.zapette.model.RepeatMode
import io.github.jmorille.zapette.model.UiCommand
import io.github.jmorille.zapette.model.UnsupportedActionException
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Contrôles avancés : pistes, sauts, modes, interface, bibliothèque, file, cibles. */
class MediaServerControlsTest {
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

    private fun noContent() = server.enqueue(MockResponse.Builder().code(204).build())

    private fun RecordedRequest.path() = url.encodedPath

    private fun RecordedRequest.text() = body?.utf8().orEmpty()

    private val streams = """
        "MediaStreams":[
          {"Type":"Video","Index":0,"DisplayTitle":"1080p"},
          {"Type":"Audio","Index":1,"DisplayTitle":"Français - AC3","Language":"fre"},
          {"Type":"Audio","Index":2,"DisplayTitle":"English - AAC","Language":"eng"},
          {"Type":"Subtitle","Index":3,"DisplayTitle":"Français (forcés)","Language":"fre"},
          {"Type":"Subtitle","Index":4,"Language":"eng"}
        ]
    """.trimIndent()

    private fun session(
        id: String = "s1",
        nowPlaying: String? = null,
        playState: String = """{"PositionTicks":0}""",
        extra: String = "",
    ) = buildString {
        append("""{"Id":"$id","Client":"Jellyfin Web","DeviceName":"Salon","UserName":"jo","SupportsRemoteControl":true""")
        nowPlaying?.let { append(""","NowPlayingItem":$it""") }
        append(""","PlayState":$playState""")
        if (extra.isNotEmpty()) append(",").append(extra)
        append("}")
    }

    @Test
    fun `tracks are parsed from the session and next subtitle is computed`() = runTest {
        val c = controller()
        json(
            "[" + session(
                nowPlaying = """{"Id":"i1","Name":"Film","RunTimeTicks":100,$streams,"Chapters":[{"StartPositionTicks":0}]}""",
                playState = """{"PositionTicks":0,"AudioStreamIndex":2,"SubtitleStreamIndex":-1,"RepeatMode":"RepeatAll","PlaybackOrder":"Shuffle","IsPaused":true}""",
            ) + "]",
        )
        val d = c.mediaDetails()
        assertEquals(
            listOf(MediaTrack(1, "Français - AC3", "fre"), MediaTrack(2, "English - AAC", "eng")),
            d.audioTracks,
        )
        assertEquals(listOf(MediaTrack(3, "Français (forcés)", "fre"), MediaTrack(4, "eng", "eng")), d.subtitles)
        assertEquals(2, d.currentAudio)
        assertNull("index -1 : sous-titres désactivés", d.currentSubtitle)
        assertEquals(RepeatMode.ALL, d.repeat)
        assertEquals(true, d.shuffle)
        assertEquals(0, d.speed)
        assertEquals(1, d.chapterCount)
        val sessions = server.next()
        assertEquals("/Sessions", sessions.path())
        assertEquals("u1", sessions.url.queryParameter("ControllableByUserId"))

        // Sous-titres désactivés → premier sous-titre.
        json("[" + session(nowPlaying = """{"Id":"i1",$streams,"Chapters":[{"StartPositionTicks":0}]}""", playState = """{"SubtitleStreamIndex":-1}""") + "]")
        noContent()
        c.nextSubtitle()
        server.next()
        val cmd = server.next()
        assertEquals("/Sessions/s1/Command", cmd.path())
        assertEquals("""{"Name":"SetSubtitleStreamIndex","Arguments":{"Index":"3"}}""", cmd.text())

        // Dernier sous-titre → désactivés (-1) ; audio : dernière piste → première.
        noContent()
        c.setSubtitle(null)
        assertEquals("""{"Name":"SetSubtitleStreamIndex","Arguments":{"Index":"-1"}}""", server.next().text())
        json("[" + session(nowPlaying = """{"Id":"i1",$streams,"Chapters":[{"StartPositionTicks":0}]}""", playState = """{"AudioStreamIndex":2}""") + "]")
        noContent()
        c.nextAudioTrack()
        server.next()
        assertEquals("""{"Name":"SetAudioStreamIndex","Arguments":{"Index":"1"}}""", server.next().text())
    }

    @Test
    fun `tracks fall back on the item when sessions omit media streams`() = runTest {
        val c = controller()
        json("[" + session(nowPlaying = """{"Id":"i1","Name":"Film"}""", playState = """{"MediaSourceId":"ms2","SubtitleStreamIndex":5}""") + "]")
        json(
            """{"Id":"i1","MediaSources":[
                 {"Id":"ms1","MediaStreams":[{"Type":"Audio","Index":1,"DisplayTitle":"Autre"}]},
                 {"Id":"ms2","MediaStreams":[{"Type":"Audio","Index":1,"DisplayTitle":"Bonne"},{"Type":"Subtitle","Index":5,"DisplayTitle":"FR"}]}
               ],"Chapters":[{"StartPositionTicks":0},{"StartPositionTicks":50}]}""",
        )
        val d = c.mediaDetails()
        assertEquals(listOf(MediaTrack(1, "Bonne")), d.audioTracks)
        assertEquals(5, d.currentSubtitle)
        assertEquals(2, d.chapterCount)
        server.next()
        val item = server.next()
        assertEquals("/Items/i1", item.path())
        assertEquals("u1", item.url.queryParameter("userId"))
    }

    @Test
    fun `emby item fallback uses the user route and Emby authorization`() = runTest {
        val c = controller(DeviceKind.EMBY)
        json("[" + session(nowPlaying = """{"Id":"i1"}""") + "]")
        json("""{"Id":"i1","MediaSources":[{"MediaStreams":[{"Type":"Audio","Index":1}]}]}""")
        assertEquals(listOf(MediaTrack(1, "Piste 1")), c.mediaDetails().audioTracks)
        server.next()
        val item = server.next()
        assertEquals("/Users/u1/Items/i1", item.path())
        assertEquals("tok", item.headers["X-Emby-Token"])
        assertEquals(
            "Emby UserId=\"u1\", Client=\"Zapette\", Device=\"Android\", DeviceId=\"dev-1\", Version=\"1.0\", Token=\"tok\"",
            item.headers["X-Emby-Authorization"],
        )
        assertNull(item.headers["Authorization"])
    }

    @Test
    fun `jellyfin keeps the MediaBrowser authorization header`() = runTest {
        val c = controller()
        noContent()
        c.faster()
        val r = server.next()
        assertEquals("/Sessions/s1/Playing/FastForward", r.path())
        assertEquals(
            "MediaBrowser Client=\"Zapette\", Device=\"Android\", DeviceId=\"dev-1\", Version=\"1.0\", Token=\"tok\"",
            r.headers["Authorization"],
        )
        assertNull(r.headers["X-Emby-Token"])
        noContent()
        c.slower()
        assertEquals("/Sessions/s1/Playing/Rewind", server.next().path())
    }

    @Test
    fun `seek step adds the delta in ticks and stays within the media`() = runTest {
        val c = controller()
        json("[" + session(nowPlaying = """{"Id":"i1","RunTimeTicks":1200000000}""", playState = """{"PositionTicks":1000000000}""") + "]")
        noContent()
        c.seekBy(30_000)
        server.next()
        val seek = server.next()
        assertEquals("/Sessions/s1/Playing/Seek", seek.path())
        assertEquals("1200000000", seek.url.queryParameter("seekPositionTicks"))

        json("[" + session(nowPlaying = """{"Id":"i1","RunTimeTicks":1200000000}""", playState = """{"PositionTicks":100000000}""") + "]")
        noContent()
        c.seekBy(-15_000)
        server.next()
        assertEquals("0", server.next().url.queryParameter("seekPositionTicks"))

        json("[" + session(nowPlaying = """{"Id":"i1","RunTimeTicks":1200000000}""", playState = """{"PositionTicks":100000000}""") + "]")
        noContent()
        c.seekBy(5_000)
        server.next()
        assertEquals("150000000", server.next().url.queryParameter("seekPositionTicks"))
    }

    @Test
    fun `repeat and shuffle send general commands`() = runTest {
        val c = controller()
        noContent()
        c.setRepeat(RepeatMode.ONE)
        assertEquals("""{"Name":"SetRepeatMode","Arguments":{"RepeatMode":"RepeatOne"}}""", server.next().text())
        noContent()
        c.setRepeat(RepeatMode.OFF)
        assertEquals("""{"Name":"SetRepeatMode","Arguments":{"RepeatMode":"RepeatNone"}}""", server.next().text())
        noContent()
        c.setShuffle(true)
        assertEquals("""{"Name":"SetShuffleQueue","Arguments":{"ShuffleMode":"Shuffle"}}""", server.next().text())
        noContent()
        c.setShuffle(false)
        assertEquals("""{"Name":"SetShuffleQueue","Arguments":{"ShuffleMode":"Sorted"}}""", server.next().text())
    }

    @Test
    fun `supported commands filter keys and ui commands`() = runTest {
        val c = controller()
        // Session inconnue : tout est proposé.
        assertEquals(RemoteKey.entries.toSet(), c.supportedKeys)
        assertEquals(UiCommand.entries.toSet(), c.uiCommands)

        json(
            "[" + session(
                extra = """"Capabilities":{"SupportedCommands":["MoveUp","MoveDown","Select","Back","GoHome","ToggleStats","DisplayMessage"]}""",
            ) + "]",
        )
        c.targets()
        server.next()
        assertTrue(RemoteKey.UP in c.supportedKeys)
        assertFalse("MoveLeft non déclaré", RemoteKey.LEFT in c.supportedKeys)
        assertFalse(RemoteKey.VOLUME_UP in c.supportedKeys)
        assertTrue("commande de lecture, jamais filtrée", RemoteKey.PLAY_PAUSE in c.supportedKeys)
        assertEquals(setOf(UiCommand.CODEC_INFO, UiCommand.HOME_SCREEN), c.uiCommands)

        noContent()
        c.uiCommand(UiCommand.CODEC_INFO)
        assertEquals("""{"Name":"ToggleStats","Arguments":{}}""", server.next().text())
        expectFailure<UnsupportedActionException> { c.uiCommand(UiCommand.GUIDE) }
        expectFailure<UnsupportedActionException> { c.send(RemoteKey.LEFT) }
        expectFailure<UnsupportedActionException> { c.setRepeat(RepeatMode.ALL) }
        assertEquals("aucune requête pour une commande refusée", 2, server.requestCount)
    }

    @Test
    fun `top level SupportedCommands (Emby) are honoured`() = runTest {
        val c = controller(DeviceKind.EMBY)
        json("[" + session(extra = """"SupportedCommands":["ToggleFullscreen","PageUp","PageDown"]""") + "]")
        c.targets()
        assertEquals(setOf(UiCommand.FULLSCREEN, UiCommand.PAGE_UP, UiCommand.PAGE_DOWN), c.uiCommands)
    }

    @Test
    fun `every ui command maps to its general command`() = runTest {
        val c = controller()
        val expected = mapOf(
            UiCommand.OSD to "ToggleOsdMenu", UiCommand.CONTEXT_MENU to "ToggleContextMenu",
            UiCommand.CODEC_INFO to "ToggleStats", UiCommand.FULLSCREEN to "ToggleFullscreen",
            UiCommand.HOME_SCREEN to "GoHome", UiCommand.SETTINGS to "GoToSettings",
            UiCommand.SEARCH to "GoToSearch", UiCommand.PAGE_UP to "PageUp", UiCommand.PAGE_DOWN to "PageDown",
            UiCommand.CHANNEL_UP to "ChannelUp", UiCommand.CHANNEL_DOWN to "ChannelDown", UiCommand.GUIDE to "Guide",
        )
        for ((command, name) in expected) {
            noContent()
            c.uiCommand(command)
            assertEquals("""{"Name":"$name","Arguments":{}}""", server.next().text())
        }
    }

    @Test
    fun `message is posted to the session`() = runTest {
        val c = controller()
        noContent()
        c.showMessage("Titre", "Bonjour \"vous\"")
        val r = server.next()
        assertEquals("POST", r.method)
        assertEquals("/Sessions/s1/Message", r.path())
        assertEquals("""{"Header":"Titre","Text":"Bonjour \"vous\"","TimeoutMs":5000}""", r.text())
    }

    @Test
    fun `library scan requires an administrator`() = runTest {
        val c = controller()
        noContent()
        c.scanLibrary()
        val r = server.next()
        assertEquals("POST", r.method)
        assertEquals("/Library/Refresh", r.path())

        server.enqueue(MockResponse.Builder().code(403).build())
        val e = expectFailure<RemoteException> { c.scanLibrary() }
        assertTrue(e.message!!.contains("administrateur"))
        expectFailure<UnsupportedActionException> { c.cleanLibrary() }
    }

    @Test
    fun `sections browse play and enqueue`() = runTest {
        val c = controller()
        json("""{"Items":[{"Id":"v1","Name":"Films","CollectionType":"movies"},{"Id":"v2","Name":"Séries"}],"TotalRecordCount":2}""")
        assertEquals(listOf(MediaSection("v1", "Films"), MediaSection("v2", "Séries")), c.sections())
        val views = server.next()
        assertEquals("/UserViews", views.path())
        assertEquals("u1", views.url.queryParameter("userId"))

        json(
            """{"Items":[
                 {"Id":"m1","Name":"Alien","Type":"Movie","ProductionYear":1979,"IsFolder":false,"MediaType":"Video"},
                 {"Id":"se1","Name":"Dark","Type":"Series","IsFolder":true},
                 {"Id":"sa1","Name":"Saison 1","Type":"Season"},
                 {"Id":"e1","Name":"Secrets","Type":"Episode","SeriesName":"Dark","ParentIndexNumber":1,"IndexNumber":2}
               ]}""",
        )
        val entries = c.browse("v1", offset = 20, limit = 10)
        assertEquals(
            listOf(
                MediaEntry("m1", "Alien", "1979"),
                MediaEntry("se1", "Dark", browsable = true),
                MediaEntry("sa1", "Saison 1", browsable = true),
                MediaEntry("e1", "Secrets", "Dark · S01E02"),
            ),
            entries,
        )
        val browse = server.next()
        assertEquals("/Items", browse.path())
        assertEquals("u1", browse.url.queryParameter("userId"))
        assertEquals("v1", browse.url.queryParameter("ParentId"))
        assertEquals("20", browse.url.queryParameter("StartIndex"))
        assertEquals("10", browse.url.queryParameter("Limit"))
        assertEquals("SortName", browse.url.queryParameter("SortBy"))

        noContent()
        c.play("m1")
        val play = server.next()
        assertEquals("POST", play.method)
        assertEquals("/Sessions/s1/Playing", play.path())
        assertEquals("PlayNow", play.url.queryParameter("playCommand"))
        assertEquals("m1", play.url.queryParameter("itemIds"))

        noContent()
        c.enqueue("m1", next = true)
        assertEquals("PlayNext", server.next().url.queryParameter("playCommand"))
        noContent()
        c.enqueue("m1")
        assertEquals("PlayLast", server.next().url.queryParameter("playCommand"))
    }

    @Test
    fun `emby library uses user routes`() = runTest {
        val c = controller(DeviceKind.EMBY)
        json("""{"Items":[{"Id":"v1","Name":"Films"}]}""")
        c.sections()
        assertEquals("/Users/u1/Views", server.next().path())
        json("""{"Items":[]}""")
        c.browse("v1")
        val r = server.next()
        assertEquals("/Users/u1/Items", r.path())
        assertNull(r.url.queryParameter("userId"))
        assertEquals("v1", r.url.queryParameter("ParentId"))
    }

    @Test
    fun `media types not playable by the session are refused`() = runTest {
        val c = controller()
        json("[" + session(extra = """"PlayableMediaTypes":["Video"]""") + "]")
        c.targets()
        json("""{"Items":[{"Id":"a1","Name":"Chanson","MediaType":"Audio"},{"Id":"m1","Name":"Film","MediaType":"Video"}]}""")
        c.browse("v")
        expectFailure<UnsupportedActionException> { c.play("a1") }
        noContent()
        c.play("m1")
        server.next()
        server.next()
        assertEquals("m1", server.next().url.queryParameter("itemIds"))
    }

    @Test
    fun `queue titles position and replay from index`() = runTest {
        val c = controller()
        val s = "[" + session(
            nowPlaying = """{"Id":"b"}""",
            extra = """"NowPlayingQueue":[{"Id":"a","PlaylistItemId":"p1"},{"Id":"b","PlaylistItemId":"p2"},{"Id":"c","PlaylistItemId":"p3"}],"PlaylistItemId":"p2"""",
        ) + "]"
        json(s)
        json("""{"Items":[{"Id":"c","Name":"Trois"},{"Id":"a","Name":"Un"},{"Id":"b","Name":"Deux"}]}""")
        assertEquals(listOf("Un", "Deux", "Trois"), c.queue().map { it.title })
        server.next()
        val items = server.next()
        assertEquals("/Items", items.path())
        assertEquals("a,b,c", items.url.queryParameter("Ids"))

        json(s)
        assertEquals(1, c.queuePosition())
        server.next()

        json(s)
        noContent()
        c.playQueueItem(2)
        server.next()
        val play = server.next()
        assertEquals("/Sessions/s1/Playing", play.path())
        assertEquals("PlayNow", play.url.queryParameter("playCommand"))
        assertEquals("a,b,c", play.url.queryParameter("itemIds"))
        assertEquals("2", play.url.queryParameter("startIndex"))

        expectFailure<UnsupportedActionException> { c.removeQueueItem(0) }
        expectFailure<UnsupportedActionException> { c.clearQueue() }
    }

    @Test
    fun `chapters seek to next and previous start`() = runTest {
        val c = controller()
        val chapters = """"Chapters":[{"StartPositionTicks":0},{"StartPositionTicks":600000000},{"StartPositionTicks":1200000000}]"""
        json("[" + session(nowPlaying = """{"Id":"i1",$chapters}""", playState = """{"PositionTicks":700000000}""") + "]")
        noContent()
        c.nextChapter()
        server.next()
        assertEquals("1200000000", server.next().url.queryParameter("seekPositionTicks"))

        // 1 s après le début du chapitre 2 : « précédent » remonte au chapitre 1.
        json("[" + session(nowPlaying = """{"Id":"i1",$chapters}""", playState = """{"PositionTicks":610000000}""") + "]")
        noContent()
        c.previousChapter()
        server.next()
        assertEquals("0", server.next().url.queryParameter("seekPositionTicks"))

        json("[" + session(nowPlaying = """{"Id":"i1",$chapters}""", playState = """{"PositionTicks":700000000}""") + "]")
        noContent()
        c.previousChapter()
        server.next()
        assertEquals("600000000", server.next().url.queryParameter("seekPositionTicks"))
    }

    @Test
    fun `targets are listed and the manual selection is persisted`() = runTest {
        val c = controller(extras = mapOf("token" to "tok", "userId" to "u1"))
        val sessions = """[
            {"Id":"own","DeviceId":"dev-1","Client":"Zapette","DeviceName":"Android","SupportsRemoteControl":true},
            {"Id":"web","Client":"Jellyfin Web","DeviceName":"Firefox","UserName":"jo","SupportsRemoteControl":true,"NowPlayingItem":{"Name":"x"}},
            {"Id":"tv","Client":"Jellyfin Android TV","DeviceName":"Salon","UserName":"jo","SupportsRemoteControl":true},
            {"Id":"dlna","Client":"DLNA","DeviceName":"Ampli","SupportsRemoteControl":false}
        ]"""
        json(sessions)
        assertEquals(
            listOf(
                PlaybackTarget("web", "Jellyfin Web – Firefox", "jo"),
                PlaybackTarget("tv", "Jellyfin Android TV – Salon", "jo"),
            ),
            c.targets(),
        )
        server.next()

        // Sélection automatique : la session en lecture.
        json(sessions)
        c.connect()
        server.next()
        assertEquals("web", c.currentTargetId)
        assertNull("sélection automatique non persistée", c.persistableExtras()["sessionId"])

        json(sessions)
        c.selectTarget("tv")
        server.next()
        assertEquals("tv", c.currentTargetId)
        assertEquals("tv", c.persistableExtras()[MediaServerController.EXTRA_SESSION_ID])

        noContent()
        c.send(RemoteKey.SELECT)
        assertEquals("/Sessions/tv/Command", server.next().path())

        json(sessions)
        expectFailure<RemoteException> { c.selectTarget("dlna") }
        assertEquals("tv", c.currentTargetId)

        // Persisté puis relu : la session imposée est reprise sans nouvelle sélection.
        val restored = controller(extras = c.persistableExtras())
        assertEquals("tv", restored.currentTargetId)
    }

    @Test
    fun `controller never exposes the token`() {
        val c = controller(extras = mapOf("token" to "secret-tok", "userId" to "u1", "password" to "pw"))
        assertFalse(c.toString().contains("secret-tok"))
        assertFalse(c.toString().contains("pw"))
    }
}
