package io.github.ttbox.kodifin.kodi

import app.cash.turbine.test
import io.github.ttbox.kodifin.model.Device
import io.github.ttbox.kodifin.model.DeviceKind
import io.github.ttbox.kodifin.model.RemoteError
import io.github.ttbox.kodifin.model.RemoteKey
import io.github.ttbox.kodifin.testing.assertFailsWithReason
import io.github.ttbox.kodifin.testing.awaitRequest
import io.github.ttbox.kodifin.testing.release
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.util.Collections
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Transport factice qui enregistre les appels et renvoie des réponses programmées. */
private class FakeTransport(private val responses: Map<String, String> = emptyMap()) : KodiTransport {
    val calls = mutableListOf<Pair<String, JsonObject?>>()
    override suspend fun call(method: String, params: JsonObject?): JsonElement {
        calls += method to params
        return Json.parseToJsonElement(responses[method] ?: "\"OK\"")
    }
}

private const val ONE_PLAYER = """[{"playerid":1,"type":"video"}]"""

class KodiControllerTest {
    private val device = Device("k", "Salon", DeviceKind.KODI, "10.0.0.2")

    @Test
    fun `every key maps to the expected JSON-RPC call`() = runTest {
        // Touche → (méthode, paramètres JSON) du dernier appel émis.
        val expected: Map<RemoteKey, Pair<String, String?>> = mapOf(
            RemoteKey.UP to (KodiMethod.INPUT_UP to null),
            RemoteKey.DOWN to (KodiMethod.INPUT_DOWN to null),
            RemoteKey.LEFT to (KodiMethod.INPUT_LEFT to null),
            RemoteKey.RIGHT to (KodiMethod.INPUT_RIGHT to null),
            RemoteKey.SELECT to (KodiMethod.INPUT_SELECT to null),
            RemoteKey.BACK to (KodiMethod.INPUT_BACK to null),
            RemoteKey.HOME to (KodiMethod.INPUT_HOME to null),
            RemoteKey.MENU to (KodiMethod.INPUT_CONTEXT_MENU to null),
            RemoteKey.INFO to (KodiMethod.INPUT_INFO to null),
            RemoteKey.VOLUME_UP to (KodiMethod.APP_SET_VOLUME to """{"volume":"increment"}"""),
            RemoteKey.VOLUME_DOWN to (KodiMethod.APP_SET_VOLUME to """{"volume":"decrement"}"""),
            RemoteKey.MUTE to (KodiMethod.APP_SET_MUTE to """{"mute":"toggle"}"""),
            RemoteKey.PLAY_PAUSE to (KodiMethod.PLAYER_PLAY_PAUSE to """{"playerid":1}"""),
            RemoteKey.STOP to (KodiMethod.PLAYER_STOP to """{"playerid":1}"""),
            RemoteKey.NEXT to (KodiMethod.PLAYER_GO_TO to """{"playerid":1,"to":"next"}"""),
            RemoteKey.PREVIOUS to (KodiMethod.PLAYER_GO_TO to """{"playerid":1,"to":"previous"}"""),
            RemoteKey.REWIND to (KodiMethod.PLAYER_SEEK to """{"playerid":1,"value":{"step":"smallbackward"}}"""),
            RemoteKey.FAST_FORWARD to (KodiMethod.PLAYER_SEEK to """{"playerid":1,"value":{"step":"smallforward"}}"""),
        )
        assertEquals(RemoteKey.entries.toSet(), expected.keys, "table incomplète")
        for ((key, call) in expected) {
            val transport = FakeTransport(mapOf(KodiMethod.PLAYER_GET_ACTIVE to ONE_PLAYER))
            KodiController(device, KodiClient(transport)).send(key)
            val (method, params) = transport.calls.last()
            assertEquals(call.first, method, "méthode pour $key")
            assertEquals(call.second?.let { Json.parseToJsonElement(it) }, params, "paramètres pour $key")
        }
    }

    @Test
    fun `player keys query the active player first`() = runTest {
        val transport = FakeTransport(mapOf(KodiMethod.PLAYER_GET_ACTIVE to ONE_PLAYER))
        KodiController(device, KodiClient(transport)).send(RemoteKey.PLAY_PAUSE)
        assertEquals(listOf(KodiMethod.PLAYER_GET_ACTIVE, KodiMethod.PLAYER_PLAY_PAUSE), transport.calls.map { it.first })
    }

    @Test
    fun `playback keys are ignored without active player`() = runTest {
        val transport = FakeTransport(mapOf(KodiMethod.PLAYER_GET_ACTIVE to "[]"))
        val controller = KodiController(device, KodiClient(transport))
        listOf(RemoteKey.PLAY_PAUSE, RemoteKey.STOP, RemoteKey.NEXT, RemoteKey.FAST_FORWARD).forEach { controller.send(it) }
        controller.seekTo(1_000)
        assertTrue(transport.calls.all { it.first == KodiMethod.PLAYER_GET_ACTIVE }, transport.calls.toString())
        assertEquals(5, transport.calls.size)
    }

    @Test
    fun `setVolume clamps to 0-100`() = runTest {
        val transport = FakeTransport()
        val controller = KodiController(device, KodiClient(transport))
        controller.setVolume(150)
        controller.setVolume(-3)
        assertEquals(listOf(100, 0), transport.calls.map { it.second!!["volume"]!!.jsonPrimitive.int })
    }

    @Test
    fun `playUrl and sendText use Player Open and Input SendText`() = runTest {
        val transport = FakeTransport()
        val controller = KodiController(device, KodiClient(transport))
        controller.playUrl("http://nas/film.mkv", "ignoré")
        controller.sendText("bonjour")
        assertEquals(KodiMethod.PLAYER_OPEN to json("""{"item":{"file":"http://nas/film.mkv"}}"""), transport.calls[0])
        assertEquals(KodiMethod.INPUT_SEND_TEXT to json("""{"text":"bonjour","done":true}"""), transport.calls[1])
    }

    @Test
    fun `seekTo splits milliseconds into Kodi time object`() = runTest {
        val transport = FakeTransport(mapOf(KodiMethod.PLAYER_GET_ACTIVE to ONE_PLAYER))
        KodiController(device, KodiClient(transport)).seekTo(3_723_456)
        assertEquals(
            KodiMethod.PLAYER_SEEK to
                json("""{"playerid":1,"value":{"time":{"hours":1,"minutes":2,"seconds":3,"milliseconds":456}}}"""),
            transport.calls.last(),
        )
    }

    @Test
    fun `snapshot builds playback state`() = runTest {
        val transport = FakeTransport(
            mapOf(
                KodiMethod.APP_GET_PROPERTIES to """{"volume":42,"muted":false,"version":{"major":21,"minor":2}}""",
                KodiMethod.PLAYER_GET_ACTIVE to ONE_PLAYER,
                KodiMethod.PLAYER_GET_PROPERTIES to
                    """{"speed":1,"percentage":10.0,"time":{"hours":0,"minutes":1,"seconds":0,"milliseconds":0},"totaltime":{"hours":0,"minutes":10,"seconds":0,"milliseconds":0}}""",
                KodiMethod.PLAYER_GET_ITEM to """{"item":{"id":7,"label":"file.mkv","title":"Big Buck Bunny","type":"movie"}}""",
            ),
        )
        val state = KodiController(device, KodiClient(transport)).snapshot()
        assertEquals("Big Buck Bunny", state.title)
        assertTrue(state.isPlaying)
        assertEquals(60_000, state.positionMs)
        assertEquals(600_000, state.durationMs)
        assertEquals(42, state.volumePercent)
    }

    @Test
    fun `snapshot without player reports volume only`() = runTest {
        val transport = FakeTransport(
            mapOf(
                KodiMethod.APP_GET_PROPERTIES to """{"volume":12,"muted":true}""",
                KodiMethod.PLAYER_GET_ACTIVE to "[]",
            ),
        )
        val state = KodiController(device, KodiClient(transport)).snapshot()
        assertEquals(io.github.ttbox.kodifin.model.PlaybackState(volumePercent = 12, muted = true), state)
    }

    @Test
    fun `playbackState polls snapshots`() = runTest {
        val transport = FakeTransport(
            mapOf(
                KodiMethod.APP_GET_PROPERTIES to """{"volume":5,"muted":false}""",
                KodiMethod.PLAYER_GET_ACTIVE to "[]",
            ),
        )
        // Intervalle en temps virtuel : la boucle se suspend entre deux instantanés.
        KodiController(device, KodiClient(transport), pollIntervalMs = 1_000).playbackState().test {
            assertEquals(5, awaitItem().volumePercent)
            assertEquals(5, awaitItem().volumePercent)
            cancelAndIgnoreRemainingEvents()
        }
    }
}

private fun json(text: String) = Json.parseToJsonElement(text) as JsonObject

class KodiClientTest {
    private fun client(vararg responses: Pair<String, String>): Pair<KodiClient, FakeTransport> {
        val transport = FakeTransport(responses.toMap())
        return KodiClient(transport) to transport
    }

    @Test
    fun `commands emit the documented JSON-RPC payloads`() = runTest {
        val (client, transport) = client()
        val cases: List<Triple<suspend () -> Unit, String, String?>> = listOf(
            Triple({ client.openUrl("http://nas/a.mp4") }, KodiMethod.PLAYER_OPEN, """{"item":{"file":"http://nas/a.mp4"}}"""),
            Triple({ client.goTo(3, next = true) }, KodiMethod.PLAYER_GO_TO, """{"playerid":3,"to":"next"}"""),
            Triple({ client.goTo(3, next = false) }, KodiMethod.PLAYER_GO_TO, """{"playerid":3,"to":"previous"}"""),
            Triple({ client.seekStep(2, "bigforward") }, KodiMethod.PLAYER_SEEK, """{"playerid":2,"value":{"step":"bigforward"}}"""),
            Triple({ client.playPause(0) }, KodiMethod.PLAYER_PLAY_PAUSE, """{"playerid":0}"""),
            Triple({ client.stop(1) }, KodiMethod.PLAYER_STOP, """{"playerid":1}"""),
            Triple({ client.showNotification("Titre", "Corps") }, KodiMethod.GUI_SHOW_NOTIFICATION, """{"title":"Titre","message":"Corps"}"""),
            Triple({ client.activateWindow("videos") }, KodiMethod.GUI_ACTIVATE_WINDOW, """{"window":"videos"}"""),
            Triple({ client.executeAction("osd") }, KodiMethod.INPUT_EXECUTE_ACTION, """{"action":"osd"}"""),
            Triple({ client.sendText("abc", done = false) }, KodiMethod.INPUT_SEND_TEXT, """{"text":"abc","done":false}"""),
            Triple({ client.stepVolume("decrement") }, KodiMethod.APP_SET_VOLUME, """{"volume":"decrement"}"""),
            Triple({ client.setVolume(55) }, KodiMethod.APP_SET_VOLUME, """{"volume":55}"""),
            Triple({ client.toggleMute() }, KodiMethod.APP_SET_MUTE, """{"mute":"toggle"}"""),
            Triple({ client.input(KodiMethod.INPUT_SHOW_OSD) }, KodiMethod.INPUT_SHOW_OSD, null),
            Triple({ client.scanVideoLibrary() }, KodiMethod.VIDEO_SCAN, null),
            Triple({ client.scanAudioLibrary() }, KodiMethod.AUDIO_SCAN, null),
            Triple({ client.systemAction(KodiMethod.SYSTEM_SUSPEND) }, KodiMethod.SYSTEM_SUSPEND, null),
            Triple(
                { client.playerProperties(4) },
                KodiMethod.PLAYER_GET_PROPERTIES,
                """{"playerid":4,"properties":["speed","time","totaltime","percentage"]}""",
            ),
            Triple(
                { client.appProperties() },
                KodiMethod.APP_GET_PROPERTIES,
                """{"properties":["volume","muted","version"]}""",
            ),
        )
        for ((action, method, params) in cases) {
            transport.calls.clear()
            runCatching { action() } // les réponses factices « OK » ne sont pas toujours des objets
            assertEquals(method to params?.let(::json), transport.calls.single(), "appel $method")
        }
    }

    @Test
    fun `recent lists send limits and parse items`() = runTest {
        val (client, transport) = client(
            KodiMethod.VIDEO_GET_RECENT_MOVIES to
                """{"movies":[{"movieid":7,"label":"Film"},{"label":"Sans id"},"x"],"limits":{"total":2}}""",
            KodiMethod.VIDEO_GET_RECENT_EPISODES to """{"episodes":[{"episodeid":3,"label":"Épisode"}]}""",
            KodiMethod.AUDIO_GET_RECENT_ALBUMS to """{"limits":{"total":0}}""",
        )
        assertEquals(
            listOf(KodiMediaItem(7, "Film", "movies"), KodiMediaItem(null, "Sans id", "movies")),
            client.recentMovies(limit = 10),
        )
        assertEquals(listOf(KodiMediaItem(3, "Épisode", "episodes")), client.recentEpisodes())
        assertEquals(emptyList<KodiMediaItem>(), client.recentAlbums(limit = 5))
        assertEquals<List<Pair<String, JsonObject?>>>(
            listOf(
                KodiMethod.VIDEO_GET_RECENT_MOVIES to json("""{"limits":{"start":0,"end":10}}"""),
                KodiMethod.VIDEO_GET_RECENT_EPISODES to json("""{"limits":{"start":0,"end":25}}"""),
                KodiMethod.AUDIO_GET_RECENT_ALBUMS to json("""{"limits":{"start":0,"end":5}}"""),
            ),
            transport.calls,
        )
    }

    @Test
    fun `recent list with non object result is rejected`() = runTest {
        val (client, _) = client(KodiMethod.VIDEO_GET_RECENT_MOVIES to "[]")
        val e = assertFailsWithReason(RemoteError.UnexpectedResponse("Kodi")) { client.recentMovies() }
        assertTrue(e.cause?.message.orEmpty().contains(KodiMethod.VIDEO_GET_RECENT_MOVIES), "cause : ${e.cause}")
    }

    @Test
    fun `ping version and item parsing`() = runTest {
        val (client, transport) = client(
            KodiMethod.PING to "\"pong\"",
            KodiMethod.VERSION to """{"version":{"major":13,"minor":5,"patch":"x"}}""",
            KodiMethod.PLAYER_GET_ITEM to """{"item":{"id":7,"label":"file.mkv","title":"  ","type":"movie"}}""",
        )
        assertTrue(client.ping())
        assertEquals("13.5.x", client.apiVersion())
        // Titre vide : repli sur le libellé.
        assertEquals(KodiMediaItem(7, "file.mkv", "movie"), client.playerItem(2))
        assertEquals(
            json("""{"playerid":2,"properties":["title","showtitle","season","episode","tvshowid","artist","album","art","thumbnail","fanart"]}"""),
            transport.calls.last().second,
        )
    }

    @Test
    fun `kodi time conversion`() {
        assertEquals(3_723_004, KodiClient.kodiTimeToMs(json("""{"hours":1,"minutes":2,"seconds":3,"milliseconds":4}""")))
        assertEquals(0, KodiClient.kodiTimeToMs(JsonPrimitive(5)))
    }
}

class KodiHttpTransportTest {
    private lateinit var server: MockWebServer
    private val http = OkHttpClient()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.close()
        http.release()
    }

    @Test
    fun `sends JSON-RPC 2 request with basic auth`() = runTest {
        server.enqueue(MockResponse.Builder().body("""{"id":1,"jsonrpc":"2.0","result":"pong"}""").build())
        val transport = KodiHttpTransport(http, server.url("/").toString(), "kodi", "secret")
        assertTrue(KodiClient(transport).ping())

        val request = server.awaitRequest()
        assertEquals("POST", request.method)
        assertEquals("/jsonrpc", request.url.encodedPath)
        assertEquals("Basic a29kaTpzZWNyZXQ=", request.headers["Authorization"])
        assertTrue(request.headers["Content-Type"]!!.startsWith("application/json"))
        val body = Json.parseToJsonElement(request.body!!.utf8()).jsonObject
        assertEquals("2.0", body["jsonrpc"]!!.jsonPrimitive.contentOrNull)
        assertEquals("JSONRPC.Ping", body["method"]!!.jsonPrimitive.contentOrNull)
        assertEquals(1, body["id"]!!.jsonPrimitive.int)
    }

    @Test
    fun `maps JSON-RPC errors to KodiRpcException`() = runTest {
        server.enqueue(
            MockResponse.Builder()
                .body("""{"id":1,"jsonrpc":"2.0","error":{"code":-32601,"message":"Method not found."}}""")
                .build(),
        )
        val transport = KodiHttpTransport(http, server.url("/").toString())
        val e = assertFailsWith<KodiRpcException> { transport.call("Nope.Nope") }
        assertEquals(-32601, e.code)
        assertEquals(RemoteError.DeviceRefused(detail = "Method not found.", code = "-32601"), e.reason)
    }
}

class KodiWebSocketTransportTest {
    private fun withWebSocket(listener: WebSocketListener, block: suspend (KodiWebSocketTransport) -> Unit) = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse.Builder().webSocketUpgrade(listener).build())
        server.start()
        val url = server.url("/jsonrpc").toString().replaceFirst("http", "ws")
        val client = OkHttpClient()
        val transport = KodiWebSocketTransport(client, url)
        try {
            withTimeout(5_000) { block(transport) }
        } finally {
            transport.close()
            client.release()
            server.close()
        }
    }

    /** Base des serveurs factices : répond à la fermeture pour un arrêt propre. */
    private abstract class Server : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) = Unit

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null)
        }
    }

    @Test
    fun `correlates responses by id and forwards notifications`() = withWebSocket(
        object : Server() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                val id = Json.parseToJsonElement(text).jsonObject["id"]!!.jsonPrimitive.int
                webSocket.send("""{"jsonrpc":"2.0","method":"Player.OnPlay","params":{"data":{"item":1}}}""")
                webSocket.send("""{"jsonrpc":"2.0","id":$id,"result":"pong"}""")
            }
        },
    ) { transport ->
        transport.notifications.test {
            assertEquals("pong", transport.call(KodiMethod.PING).jsonPrimitive.contentOrNull)
            val notification = awaitItem()
            assertEquals("Player.OnPlay", notification.method)
            assertEquals(json("""{"data":{"item":1}}"""), notification.params)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `concurrent calls answered out of order are matched by id`() {
        val n = 8
        val received = Collections.synchronizedList(mutableListOf<JsonObject>())
        withWebSocket(
            object : Server() {
                override fun onMessage(webSocket: WebSocket, text: String) {
                    received += Json.parseToJsonElement(text).jsonObject
                    if (received.size < n) return
                    // Toutes les requêtes sont arrivées : réponse inconnue et notification
                    // sans id d'abord, puis réponses dans l'ordre inverse.
                    webSocket.send("""{"jsonrpc":"2.0","id":99999,"result":"orphelin"}""")
                    webSocket.send("""{"jsonrpc":"2.0","method":"GUI.OnScreensaverActivated","params":null}""")
                    synchronized(received) { received.toList() }.reversed().forEach { request ->
                        val id = request["id"]!!.jsonPrimitive.int
                        val method = request["method"]!!.jsonPrimitive.content
                        webSocket.send("""{"jsonrpc":"2.0","id":$id,"result":"$method"}""")
                    }
                }
            },
        ) { transport ->
            transport.notifications.test {
                val results = kotlinx.coroutines.coroutineScope {
                    (1..n).map { i -> async { transport.call("Test.M$i").jsonPrimitive.content } }.awaitAll()
                }
                assertEquals((1..n).map { "Test.M$it" }, results)
                assertEquals("GUI.OnScreensaverActivated", awaitItem().method)
                cancelAndIgnoreRemainingEvents()
            }
            assertEquals(n, received.map { it["id"]!!.jsonPrimitive.int }.toSet().size, "identifiants uniques")
        }
    }

    @Test
    fun `JSON-RPC errors over WebSocket raise KodiRpcException`() = withWebSocket(
        object : Server() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                val id = Json.parseToJsonElement(text).jsonObject["id"]!!.jsonPrimitive.int
                webSocket.send("""{"jsonrpc":"2.0","id":$id,"error":{"code":-32602,"message":"Invalid params."}}""")
            }
        },
    ) { transport ->
        val e = assertFailsWith<KodiRpcException> { transport.call(KodiMethod.PLAYER_OPEN) }
        assertEquals(-32602, e.code)
        assertEquals(RemoteError.DeviceRefused(detail = "Invalid params.", code = "-32602"), e.reason)
    }
}
