package io.github.jmorille.zapette.kodi

import io.github.jmorille.zapette.model.Device
import io.github.jmorille.zapette.model.DeviceKind
import io.github.jmorille.zapette.model.MediaDetails
import io.github.jmorille.zapette.model.MediaEntry
import io.github.jmorille.zapette.model.MediaSection
import io.github.jmorille.zapette.model.MediaTrack
import io.github.jmorille.zapette.model.PowerAction
import io.github.jmorille.zapette.model.RemoteKey
import io.github.jmorille.zapette.model.RepeatMode
import io.github.jmorille.zapette.model.UiCommand
import io.github.jmorille.zapette.model.UnsupportedActionException
import io.github.jmorille.zapette.testing.assertFailsWithMessage
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
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Serveur JSON-RPC factice : répond par méthode ([results] : JSON de `result`, [errors] : code
 * d'erreur) et enregistre chaque requête reçue (méthode, paramètres).
 */
internal class KodiStub : Dispatcher() {
    val results = ConcurrentHashMap<String, String>()
    val errors = ConcurrentHashMap<String, Int>()
    val calls: MutableList<Pair<String, JsonObject?>> = Collections.synchronizedList(mutableListOf())

    override fun dispatch(request: RecordedRequest): MockResponse {
        val body = Json.parseToJsonElement(request.body!!.utf8()).jsonObject
        val method = body["method"]!!.jsonPrimitive.content
        calls += method to body["params"]?.jsonObject
        val id = body["id"]
        val code = errors[method]
        val payload = if (code != null) {
            """{"jsonrpc":"2.0","id":$id,"error":{"code":$code,"message":"erreur $code"}}"""
        } else {
            """{"jsonrpc":"2.0","id":$id,"result":${results[method] ?: "\"OK\""}}"""
        }
        return MockResponse.Builder().body(payload).build()
    }

    /** Appels hors recherche du lecteur actif, pour comparer les commandes émises. */
    fun commands() = synchronized(calls) { calls.filter { it.first != KodiMethod.PLAYER_GET_ACTIVE } }
}

private fun json(text: String) = Json.parseToJsonElement(text) as JsonObject

private fun call(method: String, params: String? = null) = method to params?.let(::json)

class KodiControlsTest {
    private lateinit var server: MockWebServer
    private val http = OkHttpClient()
    private val stub = KodiStub()
    private lateinit var controller: KodiController

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = stub
        server.start()
        stub.results[KodiMethod.PLAYER_GET_ACTIVE] = """[{"playerid":1,"type":"video"}]"""
        val transport = KodiHttpTransport(http, server.url("/").toString())
        controller = KodiController(Device("k", "Salon", DeviceKind.KODI, "10.0.0.2"), KodiClient(transport))
    }

    @After
    fun tearDown() {
        server.close()
        http.release()
    }

    /** Exécute [action] et renvoie les appels émis (hors `Player.GetActivePlayers`). */
    private suspend fun commandsOf(action: suspend () -> Unit): List<Pair<String, JsonObject?>> {
        stub.calls.clear()
        action()
        return stub.commands()
    }

    @Test
    fun `player controls send the documented JSON-RPC payloads`() = runTest {
        val cases: List<Pair<suspend () -> Unit, List<Pair<String, JsonObject?>>>> = listOf(
            suspend { controller.setAudioTrack(2) } to
                listOf(call(KodiMethod.PLAYER_SET_AUDIO_STREAM, """{"playerid":1,"stream":2}""")),
            suspend { controller.nextAudioTrack() } to
                listOf(call(KodiMethod.PLAYER_SET_AUDIO_STREAM, """{"playerid":1,"stream":"next"}""")),
            suspend { controller.setSubtitle(3) } to
                listOf(call(KodiMethod.PLAYER_SET_SUBTITLE, """{"playerid":1,"subtitle":3,"enable":true}""")),
            suspend { controller.setSubtitle(null) } to
                listOf(call(KodiMethod.PLAYER_SET_SUBTITLE, """{"playerid":1,"subtitle":"off"}""")),
            suspend { controller.nextSubtitle() } to
                listOf(call(KodiMethod.PLAYER_SET_SUBTITLE, """{"playerid":1,"subtitle":"next","enable":true}""")),
            suspend { controller.seekBy(30_000) } to
                listOf(call(KodiMethod.PLAYER_SEEK, """{"playerid":1,"value":{"seconds":30}}""")),
            suspend { controller.seekBy(-10_500) } to
                listOf(call(KodiMethod.PLAYER_SEEK, """{"playerid":1,"value":{"seconds":-10}}""")),
            suspend { controller.seekBy(400) } to emptyList(),
            suspend { controller.faster() } to
                listOf(call(KodiMethod.PLAYER_SET_SPEED, """{"playerid":1,"speed":"increment"}""")),
            suspend { controller.slower() } to
                listOf(call(KodiMethod.PLAYER_SET_SPEED, """{"playerid":1,"speed":"decrement"}""")),
            suspend { controller.subtitleDelay(later = true) } to
                listOf(call(KodiMethod.INPUT_EXECUTE_ACTION, """{"action":"subtitledelayplus"}""")),
            suspend { controller.subtitleDelay(later = false) } to
                listOf(call(KodiMethod.INPUT_EXECUTE_ACTION, """{"action":"subtitledelayminus"}""")),
            suspend { controller.audioDelay(later = true) } to
                listOf(call(KodiMethod.INPUT_EXECUTE_ACTION, """{"action":"audiodelayplus"}""")),
            suspend { controller.audioDelay(later = false) } to
                listOf(call(KodiMethod.INPUT_EXECUTE_ACTION, """{"action":"audiodelayminus"}""")),
            suspend { controller.setRepeat(RepeatMode.ALL) } to
                listOf(call(KodiMethod.PLAYER_SET_REPEAT, """{"playerid":1,"repeat":"all"}""")),
            suspend { controller.setRepeat(RepeatMode.ONE) } to
                listOf(call(KodiMethod.PLAYER_SET_REPEAT, """{"playerid":1,"repeat":"one"}""")),
            suspend { controller.setRepeat(RepeatMode.OFF) } to
                listOf(call(KodiMethod.PLAYER_SET_REPEAT, """{"playerid":1,"repeat":"off"}""")),
            suspend { controller.setShuffle(true) } to
                listOf(call(KodiMethod.PLAYER_SET_SHUFFLE, """{"playerid":1,"shuffle":true}""")),
            suspend { controller.nextChapter() } to
                listOf(call(KodiMethod.INPUT_EXECUTE_ACTION, """{"action":"chapterorbigstepforward"}""")),
            suspend { controller.previousChapter() } to
                listOf(call(KodiMethod.INPUT_EXECUTE_ACTION, """{"action":"chapterorbigstepback"}""")),
            suspend { controller.showMessage("Titre", "Texte") } to
                listOf(call(KodiMethod.GUI_SHOW_NOTIFICATION, """{"title":"Titre","message":"Texte"}""")),
            suspend { controller.scanLibrary() } to listOf(call(KodiMethod.VIDEO_SCAN), call(KodiMethod.AUDIO_SCAN)),
            suspend { controller.cleanLibrary() } to listOf(call(KodiMethod.VIDEO_CLEAN), call(KodiMethod.AUDIO_CLEAN)),
        )
        cases.forEachIndexed { i, (action, expected) -> assertEquals(expected, commandsOf(action), "cas $i") }
    }

    @Test
    fun `rewind and fast forward keep their small step behaviour`() = runTest {
        assertEquals(
            listOf(call(KodiMethod.PLAYER_SEEK, """{"playerid":1,"value":{"step":"smallbackward"}}""")),
            commandsOf { controller.send(RemoteKey.REWIND) },
        )
        assertEquals(
            listOf(call(KodiMethod.PLAYER_SEEK, """{"playerid":1,"value":{"step":"smallforward"}}""")),
            commandsOf { controller.send(RemoteKey.FAST_FORWARD) },
        )
    }

    @Test
    fun `ui commands map to Kodi methods and actions`() = runTest {
        val expected = mapOf(
            UiCommand.OSD to call(KodiMethod.INPUT_SHOW_OSD),
            UiCommand.CONTEXT_MENU to call(KodiMethod.INPUT_CONTEXT_MENU),
            UiCommand.CODEC_INFO to call(KodiMethod.INPUT_SHOW_CODEC),
            UiCommand.FULLSCREEN to call(KodiMethod.GUI_SET_FULLSCREEN, """{"fullscreen":"toggle"}"""),
            UiCommand.HOME_SCREEN to call(KodiMethod.GUI_ACTIVATE_WINDOW, """{"window":"home"}"""),
            UiCommand.SETTINGS to call(KodiMethod.GUI_ACTIVATE_WINDOW, """{"window":"settings"}"""),
            UiCommand.PAGE_UP to call(KodiMethod.INPUT_EXECUTE_ACTION, """{"action":"pageup"}"""),
            UiCommand.PAGE_DOWN to call(KodiMethod.INPUT_EXECUTE_ACTION, """{"action":"pagedown"}"""),
            UiCommand.CHANNEL_UP to call(KodiMethod.INPUT_EXECUTE_ACTION, """{"action":"channelup"}"""),
            UiCommand.CHANNEL_DOWN to call(KodiMethod.INPUT_EXECUTE_ACTION, """{"action":"channeldown"}"""),
            UiCommand.GUIDE to call(KodiMethod.GUI_ACTIVATE_WINDOW, """{"window":"tvguide"}"""),
        )
        assertEquals(controller.uiCommands, expected.keys, "SEARCH n'est pas proposé")
        for ((command, request) in expected) {
            assertEquals(listOf(request), commandsOf { controller.uiCommand(command) }, "commande $command")
        }
        assertFailsWithMessage<UnsupportedActionException>("Recherche") { controller.uiCommand(UiCommand.SEARCH) }
    }

    @Test
    fun `method not found raises UnsupportedActionException, codec info falls back to an action`() = runTest {
        stub.errors[KodiMethod.VIDEO_CLEAN] = KodiClient.METHOD_NOT_FOUND
        assertFailsWithMessage<UnsupportedActionException>("« VideoLibrary.Clean » non prise en charge") {
            controller.cleanLibrary()
        }
        stub.errors[KodiMethod.INPUT_SHOW_CODEC] = KodiClient.METHOD_NOT_FOUND
        assertEquals(
            listOf(
                call(KodiMethod.INPUT_SHOW_CODEC),
                call(KodiMethod.INPUT_EXECUTE_ACTION, """{"action":"playerprocessinfo"}"""),
            ),
            commandsOf { controller.uiCommand(UiCommand.CODEC_INFO) },
        )
    }

    @Test
    fun `power actions are filtered by System GetProperties`() = runTest {
        stub.results[KodiMethod.SYSTEM_GET_PROPERTIES] =
            """{"canshutdown":true,"cansuspend":false,"canhibernate":false,"canreboot":true}"""
        assertEquals(setOf(PowerAction.SHUTDOWN, PowerAction.REBOOT, PowerAction.QUIT_APP), controller.availablePowerActions())
        assertEquals(
            json("""{"properties":["canshutdown","cansuspend","canhibernate","canreboot"]}"""),
            stub.calls.last().second,
        )

        stub.results[KodiMethod.SYSTEM_GET_PROPERTIES] = """{"cansuspend":true}"""
        assertEquals(setOf(PowerAction.SUSPEND, PowerAction.QUIT_APP), controller.availablePowerActions())

        val methods = mapOf(
            PowerAction.SUSPEND to KodiMethod.SYSTEM_SUSPEND,
            PowerAction.HIBERNATE to KodiMethod.SYSTEM_HIBERNATE,
            PowerAction.SHUTDOWN to KodiMethod.SYSTEM_SHUTDOWN,
            PowerAction.REBOOT to KodiMethod.SYSTEM_REBOOT,
            PowerAction.QUIT_APP to KodiMethod.APP_QUIT,
        )
        for ((action, method) in methods) {
            assertEquals(listOf(call(method)), commandsOf { controller.power(action) }, "action $action")
        }
    }

    @Test
    fun `mediaDetails parses streams, modes and chapter count`() = runTest {
        stub.results[KodiMethod.PLAYER_GET_PROPERTIES] = """{
            "speed":1,"repeat":"all","shuffled":true,
            "audiostreams":[{"index":0,"name":"Français AC3 5.1","language":"fre"},{"index":1,"name":"","language":"eng"},{"name":"sans index"}],
            "currentaudiostream":{"index":1,"name":"","language":"eng"},
            "subtitles":[{"index":0,"name":"","language":""},{"index":1,"name":"Forcés","language":"fre"}],
            "currentsubtitle":{"index":1},"subtitleenabled":true
        }"""
        stub.results[KodiMethod.XBMC_GET_INFO_LABELS] = """{"Player.ChapterCount":"12"}"""
        assertEquals(
            MediaDetails(
                audioTracks = listOf(MediaTrack(0, "Français AC3 5.1", "fre"), MediaTrack(1, "eng", "eng")),
                currentAudio = 1,
                subtitles = listOf(MediaTrack(0, "Sous-titres 1", null), MediaTrack(1, "Forcés", "fre")),
                currentSubtitle = 1,
                repeat = RepeatMode.ALL,
                shuffle = true,
                speed = 1,
                chapterCount = 12,
            ),
            controller.mediaDetails(),
        )
        assertEquals(
            listOf(
                call(
                    KodiMethod.PLAYER_GET_PROPERTIES,
                    """{"playerid":1,"properties":["speed","repeat","shuffled","audiostreams","currentaudiostream",
                    "subtitles","currentsubtitle","subtitleenabled"]}""",
                ),
                call(KodiMethod.XBMC_GET_INFO_LABELS, """{"labels":["Player.ChapterCount"]}"""),
            ),
            stub.commands(),
        )
    }

    @Test
    fun `mediaDetails reports disabled subtitles and idle player`() = runTest {
        stub.results[KodiMethod.PLAYER_GET_PROPERTIES] =
            """{"speed":0,"repeat":"off","shuffled":false,"currentsubtitle":{"index":0},"subtitleenabled":false}"""
        stub.errors[KodiMethod.XBMC_GET_INFO_LABELS] = KodiClient.METHOD_NOT_FOUND
        val details = controller.mediaDetails()
        assertNull(details.currentSubtitle)
        assertEquals(RepeatMode.OFF, details.repeat)
        assertEquals(0, details.chapterCount)

        stub.results[KodiMethod.PLAYER_GET_ACTIVE] = "[]"
        assertEquals(MediaDetails(speed = 0), controller.mediaDetails())
    }

    @Test
    fun `seekBy falls back to predefined steps when seconds are refused`() = runTest {
        stub.errors[KodiMethod.PLAYER_SEEK] = KodiClient.INVALID_PARAMS
        stub.calls.clear()
        runCatching { controller.seekBy(600_000) }
        assertEquals(
            listOf(
                call(KodiMethod.PLAYER_SEEK, """{"playerid":1,"value":{"seconds":600}}"""),
                call(KodiMethod.PLAYER_SEEK, """{"playerid":1,"value":{"step":"bigforward"}}"""),
            ),
            stub.commands(),
        )
    }

    @Test
    fun `JSON-RPC below v12 uses the legacy Player Seek forms`() = runTest {
        stub.results[KodiMethod.PING] = "\"pong\""
        stub.results[KodiMethod.VERSION] = """{"version":{"major":8,"minor":0,"patch":0}}"""
        controller.connect()
        assertEquals(8, controller.apiMajor)
        assertEquals(
            listOf(call(KodiMethod.PLAYER_SEEK, """{"playerid":1,"value":"smallforward"}""")),
            commandsOf { controller.send(RemoteKey.FAST_FORWARD) },
        )
        assertEquals(
            listOf(call(KodiMethod.PLAYER_SEEK, """{"playerid":1,"value":"smallbackward"}""")),
            commandsOf { controller.seekBy(-30_000) },
        )
        assertEquals(
            listOf(call(KodiMethod.PLAYER_SEEK, """{"playerid":1,"value":"bigforward"}""")),
            commandsOf { controller.seekBy(900_000) },
        )
        assertEquals(
            listOf(
                call(
                    KodiMethod.PLAYER_SEEK,
                    """{"playerid":1,"value":{"hours":1,"minutes":2,"seconds":3,"milliseconds":4}}""",
                ),
            ),
            commandsOf { controller.seekTo(3_723_004) },
        )
    }

    @Test
    fun `JSON-RPC v13 keeps the nested seek forms`() = runTest {
        stub.results[KodiMethod.PING] = "\"pong\""
        stub.results[KodiMethod.VERSION] = """{"version":{"major":13,"minor":5,"patch":0}}"""
        controller.connect()
        assertEquals(
            listOf(
                call(
                    KodiMethod.PLAYER_SEEK,
                    """{"playerid":1,"value":{"time":{"hours":0,"minutes":0,"seconds":5,"milliseconds":0}}}""",
                ),
            ),
            commandsOf { controller.seekTo(5_000) },
        )
    }

    // --- File d'attente -------------------------------------------------------

    @Test
    fun `queue is read from the active player's playlist`() = runTest {
        stub.results[KodiMethod.PLAYER_GET_ACTIVE] = """[{"playerid":0,"type":"audio"}]"""
        stub.results[KodiMethod.PLAYER_GET_PROPERTIES] = """{"playlistid":0,"position":2}"""
        stub.results[KodiMethod.PLAYLIST_GET_ITEMS] = """{"items":[
            {"id":7,"type":"movie","label":"film.mkv","title":"Film A","file":"/f/a.mkv"},
            {"type":"unknown","label":"Flux","file":"http://radio/x"},
            {"id":3,"type":"episode","label":"Pilote","title":"Pilote","showtitle":"Série"},
            {"id":9,"type":"song","label":"Chanson","title":"","artist":["A","B"]},
            {"label":"Inconnu"}
        ],"limits":{"start":0,"end":5,"total":5}}"""
        assertEquals(
            listOf(
                MediaEntry("movieid:7", "Film A"),
                MediaEntry("file:http://radio/x", "Flux"),
                MediaEntry("episodeid:3", "Pilote", "Série"),
                MediaEntry("songid:9", "Chanson", "A, B"),
                MediaEntry("position:4", "Inconnu"),
            ),
            controller.queue(),
        )
        assertEquals(
            listOf(
                call(KodiMethod.PLAYER_GET_PROPERTIES, """{"playerid":0,"properties":["playlistid","position"]}"""),
                call(KodiMethod.PLAYLIST_GET_ITEMS, """{"playlistid":0,"properties":["title","file","showtitle","artist"]}"""),
            ),
            stub.commands(),
        )
        assertEquals(2, controller.queuePosition())

        assertEquals(
            listOf(
                call(KodiMethod.PLAYER_GET_PROPERTIES, """{"playerid":0,"properties":["playlistid","position"]}"""),
                call(KodiMethod.PLAYER_GO_TO, """{"playerid":0,"to":4}"""),
            ),
            commandsOf { controller.playQueueItem(4) },
        )
        assertEquals(
            call(KodiMethod.PLAYLIST_REMOVE, """{"playlistid":0,"position":1}"""),
            commandsOf { controller.removeQueueItem(1) }.last(),
        )
        assertEquals(call(KodiMethod.PLAYLIST_CLEAR, """{"playlistid":0}"""), commandsOf { controller.clearQueue() }.last())
    }

    @Test
    fun `without player the video playlist is used and played with Player Open`() = runTest {
        stub.results[KodiMethod.PLAYER_GET_ACTIVE] = "[]"
        stub.results[KodiMethod.PLAYER_GET_PROPERTIES] = """{"playlistid":-1,"position":-1}"""
        assertNull(controller.queuePosition())
        assertEquals(
            listOf(call(KodiMethod.PLAYER_OPEN, """{"item":{"playlistid":1,"position":3}}""")),
            commandsOf { controller.playQueueItem(3) },
        )
    }

    @Test
    fun `enqueue targets audio or video playlist and inserts after the current item`() = runTest {
        stub.results[KodiMethod.PLAYER_GET_ACTIVE] = """[{"playerid":0,"type":"audio"}]"""
        stub.results[KodiMethod.PLAYER_GET_PROPERTIES] = """{"playlistid":0,"position":4}"""
        assertEquals(
            call(KodiMethod.PLAYLIST_INSERT, """{"playlistid":0,"position":5,"item":{"songid":9}}"""),
            commandsOf { controller.enqueue("songid:9", next = true) }.last(),
        )
        assertEquals(
            call(KodiMethod.PLAYLIST_ADD, """{"playlistid":0,"item":{"albumid":2}}"""),
            commandsOf { controller.enqueue("albumid:2") }.last(),
        )
        // Playlist vidéo différente de celle en cours : ajout en fin, même avec next.
        assertEquals(
            call(KodiMethod.PLAYLIST_ADD, """{"playlistid":1,"item":{"movieid":12}}"""),
            commandsOf { controller.enqueue("movieid:12", next = true) }.last(),
        )
        // Fichier : playlist active.
        assertEquals(
            call(KodiMethod.PLAYLIST_ADD, """{"playlistid":0,"item":{"file":"smb://nas/a.flac"}}"""),
            commandsOf { controller.enqueue("file:smb://nas/a.flac") }.last(),
        )
        assertFailsWithMessage<UnsupportedActionException>("file d'attente") { controller.enqueue("channelid:3") }
    }

    // --- Bibliothèque ---------------------------------------------------------

    @Test
    fun `sections include TV channels only when PVR answers`() = runTest {
        stub.results[KodiMethod.PVR_GET_CHANNEL_GROUPS] = """{"channelgroups":[{"channelgroupid":1,"label":"Toutes"}]}"""
        val all = controller.sections()
        assertEquals(
            listOf("movies", "tvshows", "artists", "albums", "tv", "favourites", "addons"),
            all.map(MediaSection::id),
        )
        assertEquals(
            json("""{"channeltype":"tv","limits":{"start":0,"end":1}}"""),
            stub.calls.single { it.first == KodiMethod.PVR_GET_CHANNEL_GROUPS }.second,
        )
        stub.errors[KodiMethod.PVR_GET_CHANNEL_GROUPS] = -32100
        assertEquals(all.filter { it.id != "tv" }, controller.sections())
    }

    @Test
    fun `browse movies sends limits and sort and parses entries`() = runTest {
        stub.results[KodiMethod.VIDEO_GET_MOVIES] =
            """{"movies":[{"movieid":12,"label":"Alien","year":1979},{"movieid":13,"label":"Sans année","year":0},{"label":"sans id"}]}"""
        assertEquals(
            listOf(MediaEntry("movieid:12", "Alien", "1979"), MediaEntry("movieid:13", "Sans année")),
            controller.browse("movies", offset = 20, limit = 10),
        )
        assertEquals(
            call(
                KodiMethod.VIDEO_GET_MOVIES,
                """{"properties":["year"],"limits":{"start":20,"end":30},"sort":{"method":"label","ignorearticle":true}}""",
            ),
            stub.commands().single(),
        )
    }

    @Test
    fun `tv shows browse into seasons then episodes`() = runTest {
        stub.results[KodiMethod.VIDEO_GET_TV_SHOWS] = """{"tvshows":[{"tvshowid":3,"label":"Série","year":2010}]}"""
        stub.results[KodiMethod.VIDEO_GET_SEASONS] =
            """{"seasons":[{"seasonid":30,"season":1,"label":"Saison 1","showtitle":"Série"},{"season":0,"label":"","showtitle":""}]}"""
        stub.results[KodiMethod.VIDEO_GET_EPISODES] =
            """{"episodes":[{"episodeid":40,"label":"Pilote","season":1,"episode":1}]}"""
        assertEquals(listOf(MediaEntry("tvshowid:3", "Série", "2010", browsable = true)), controller.browse("tvshows"))
        assertEquals(
            listOf(
                MediaEntry("season:3:1", "Saison 1", "Série", browsable = true),
                MediaEntry("season:3:0", "Saison 0", null, browsable = true),
            ),
            controller.browse("tvshowid:3"),
        )
        assertEquals(listOf(MediaEntry("episodeid:40", "Pilote", "S01E01")), controller.browse("season:3:1", 0, 50))
        val calls = stub.commands()
        assertEquals(
            call(KodiMethod.VIDEO_GET_SEASONS, """{"tvshowid":3,"properties":["season","showtitle"],"limits":{"start":0,"end":100}}"""),
            calls[1],
        )
        assertEquals(
            call(
                KodiMethod.VIDEO_GET_EPISODES,
                """{"tvshowid":3,"season":1,"properties":["season","episode"],"limits":{"start":0,"end":50}}""",
            ),
            calls[2],
        )
    }

    @Test
    fun `music browses artists, albums and songs`() = runTest {
        stub.results[KodiMethod.AUDIO_GET_ARTISTS] = """{"artists":[{"artistid":5,"label":"Daft Punk","artist":"Daft Punk"}]}"""
        stub.results[KodiMethod.AUDIO_GET_ALBUMS] = """{"albums":[{"albumid":7,"label":"Discovery","artist":["Daft Punk"],"year":2001}]}"""
        stub.results[KodiMethod.AUDIO_GET_SONGS] = """{"songs":[{"songid":90,"label":"One More Time","track":1}]}"""
        assertEquals(listOf(MediaEntry("artistid:5", "Daft Punk", browsable = true)), controller.browse("artists"))
        assertEquals(listOf(MediaEntry("albumid:7", "Discovery", "Daft Punk", browsable = true)), controller.browse("artistid:5"))
        assertEquals(listOf(MediaEntry("songid:90", "One More Time", "Piste 1")), controller.browse("albumid:7"))
        val calls = stub.commands()
        assertEquals(
            call(
                KodiMethod.AUDIO_GET_ARTISTS,
                """{"properties":[],"limits":{"start":0,"end":100},"sort":{"method":"label","ignorearticle":true}}""",
            ),
            calls[0],
        )
        assertEquals(
            call(KodiMethod.AUDIO_GET_ALBUMS, """{"filter":{"artistid":5},"properties":["artist","year"],"limits":{"start":0,"end":100}}"""),
            calls[1],
        )
        assertEquals(
            call(
                KodiMethod.AUDIO_GET_SONGS,
                """{"filter":{"albumid":7},"sort":{"method":"track"},"properties":["track"],"limits":{"start":0,"end":100}}""",
            ),
            calls[2],
        )
    }

    @Test
    fun `channels, favourites and add-ons are listed`() = runTest {
        stub.results[KodiMethod.PVR_GET_CHANNEL_GROUPS] = """{"channelgroups":[{"channelgroupid":1,"label":"Toutes les chaînes"}]}"""
        stub.results[KodiMethod.PVR_GET_CHANNELS] = """{"channels":[{"channelid":8,"label":"Arte"}]}"""
        stub.results[KodiMethod.FAVOURITES_GET] = """{"favourites":[
            {"title":"Film favori","type":"media","path":"smb://nas/film.mkv"},
            {"title":"Vidéos","type":"window","window":"videos","windowparameter":"sources://video/"},
            {"title":"Météo","type":"window","window":"weather"},
            {"title":"Script","type":"script","path":"RunScript(x)"}
        ]}"""
        stub.results[KodiMethod.ADDONS_GET_ADDONS] =
            """{"addons":[{"addonid":"plugin.video.arteplussept","type":"xbmc.python.pluginsource","name":"Arte"}]}"""
        assertEquals(listOf(MediaEntry("channelgroupid:1", "Toutes les chaînes", browsable = true)), controller.browse("tv"))
        assertEquals(listOf(MediaEntry("channelid:8", "Arte")), controller.browse("channelgroupid:1"))
        assertEquals(
            listOf(
                MediaEntry("file:smb://nas/film.mkv", "Film favori"),
                MediaEntry("window:videos|sources://video/", "Vidéos"),
                MediaEntry("window:weather", "Météo"),
            ),
            controller.browse("favourites"),
        )
        assertEquals(listOf(MediaEntry("window:weather", "Météo")), controller.browse("favourites", offset = 2, limit = 5))
        assertEquals(listOf(MediaEntry("addonid:plugin.video.arteplussept", "Arte")), controller.browse("addons"))
        val calls = stub.commands()
        assertEquals(call(KodiMethod.PVR_GET_CHANNEL_GROUPS, """{"channeltype":"tv","limits":{"start":0,"end":100}}"""), calls[0])
        assertEquals(call(KodiMethod.PVR_GET_CHANNELS, """{"channelgroupid":1,"limits":{"start":0,"end":100}}"""), calls[1])
        assertEquals(call(KodiMethod.FAVOURITES_GET, """{"properties":["window","windowparameter","path"]}"""), calls[2])
        assertEquals(
            call(
                KodiMethod.ADDONS_GET_ADDONS,
                """{"type":"xbmc.python.pluginsource","content":"video","enabled":true,"properties":["name"],"limits":{"start":0,"end":100}}""",
            ),
            calls.last(),
        )
        assertFailsWithMessage<UnsupportedActionException>("ne se parcourt pas") { controller.browse("movieid:12") }
    }

    @Test
    fun `play opens library items, files, channels, add-ons and windows`() = runTest {
        val cases = mapOf(
            "movieid:12" to listOf(call(KodiMethod.PLAYER_OPEN, """{"item":{"movieid":12}}""")),
            "episodeid:40" to listOf(call(KodiMethod.PLAYER_OPEN, """{"item":{"episodeid":40}}""")),
            "albumid:7" to listOf(call(KodiMethod.PLAYER_OPEN, """{"item":{"albumid":7}}""")),
            "songid:90" to listOf(call(KodiMethod.PLAYER_OPEN, """{"item":{"songid":90}}""")),
            "channelid:8" to listOf(call(KodiMethod.PLAYER_OPEN, """{"item":{"channelid":8}}""")),
            "file:smb://nas/a:b.mkv" to listOf(call(KodiMethod.PLAYER_OPEN, """{"item":{"file":"smb://nas/a:b.mkv"}}""")),
            "addonid:plugin.video.x" to listOf(call(KodiMethod.ADDONS_EXECUTE, """{"addonid":"plugin.video.x"}""")),
            "window:weather" to listOf(call(KodiMethod.GUI_ACTIVATE_WINDOW, """{"window":"weather"}""")),
            "window:videos|sources://video/" to
                listOf(call(KodiMethod.GUI_ACTIVATE_WINDOW, """{"window":"videos","parameters":["sources://video/"]}""")),
        )
        for ((id, expected) in cases) assertEquals(expected, commandsOf { controller.play(id) }, "lecture de $id")
        assertFailsWithMessage<UnsupportedActionException>("ne se lit pas") { controller.play("channelgroupid:1") }
        assertFailsWithMessage<UnsupportedActionException>("inconnu") { controller.play("sansdeuxpoints") }
    }

    @Test
    fun `playing a season fills the video playlist with its episodes`() = runTest {
        stub.results[KodiMethod.VIDEO_GET_EPISODES] = """{"episodes":[{"episodeid":40,"label":"A"},{"episodeid":41,"label":"B"}]}"""
        assertEquals(
            listOf(
                call(KodiMethod.VIDEO_GET_EPISODES, """{"tvshowid":3,"season":2}"""),
                call(KodiMethod.PLAYLIST_CLEAR, """{"playlistid":1}"""),
                call(KodiMethod.PLAYLIST_ADD, """{"playlistid":1,"item":[{"episodeid":40},{"episodeid":41}]}"""),
                call(KodiMethod.PLAYER_OPEN, """{"item":{"playlistid":1,"position":0}}"""),
            ),
            commandsOf { controller.play("season:3:2") },
        )
    }
}
