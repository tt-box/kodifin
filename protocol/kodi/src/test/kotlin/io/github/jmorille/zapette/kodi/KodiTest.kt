package io.github.jmorille.zapette.kodi

import app.cash.turbine.test
import io.github.jmorille.zapette.model.Device
import io.github.jmorille.zapette.model.DeviceKind
import io.github.jmorille.zapette.model.RemoteKey
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/** Transport factice qui enregistre les appels et renvoie des réponses programmées. */
private class FakeTransport(private val responses: Map<String, String> = emptyMap()) : KodiTransport {
    val calls = mutableListOf<Pair<String, JsonObject?>>()
    override suspend fun call(method: String, params: JsonObject?): JsonElement {
        calls += method to params
        return Json.parseToJsonElement(responses[method] ?: "\"OK\"")
    }
}

class KodiControllerTest {
    private val device = Device("k", "Salon", DeviceKind.KODI, "10.0.0.2")

    @Test
    fun `navigation keys map to Input methods`() = runTest {
        val transport = FakeTransport()
        val controller = KodiController(device, KodiClient(transport))
        controller.send(RemoteKey.UP)
        controller.send(RemoteKey.SELECT)
        controller.send(RemoteKey.MENU)
        assertEquals(listOf("Input.Up", "Input.Select", "Input.ContextMenu"), transport.calls.map { it.first })
    }

    @Test
    fun `play pause targets the active player`() = runTest {
        val transport = FakeTransport(mapOf(KodiMethod.PLAYER_GET_ACTIVE to """[{"playerid":1,"type":"video"}]"""))
        KodiController(device, KodiClient(transport)).send(RemoteKey.PLAY_PAUSE)
        val (method, params) = transport.calls.last()
        assertEquals("Player.PlayPause", method)
        assertEquals(1, params!!["playerid"]!!.jsonPrimitive.int)
    }

    @Test
    fun `playback keys are ignored without active player`() = runTest {
        val transport = FakeTransport(mapOf(KodiMethod.PLAYER_GET_ACTIVE to "[]"))
        KodiController(device, KodiClient(transport)).send(RemoteKey.STOP)
        assertEquals(listOf("Player.GetActivePlayers"), transport.calls.map { it.first })
    }

    @Test
    fun `volume keys use relative increments`() = runTest {
        val transport = FakeTransport()
        KodiController(device, KodiClient(transport)).send(RemoteKey.VOLUME_UP)
        assertEquals(JsonPrimitive("increment"), transport.calls.single().second!!["volume"])
    }

    @Test
    fun `setVolume clamps to 0-100`() = runTest {
        val transport = FakeTransport()
        KodiController(device, KodiClient(transport)).setVolume(150)
        assertEquals(100, transport.calls.single().second!!["volume"]!!.jsonPrimitive.int)
    }

    @Test
    fun `seekTo splits milliseconds into Kodi time object`() = runTest {
        val transport = FakeTransport(mapOf(KodiMethod.PLAYER_GET_ACTIVE to """[{"playerid":1,"type":"video"}]"""))
        KodiController(device, KodiClient(transport)).seekTo(3_723_456)
        val time = transport.calls.last().second!!["value"]!!.jsonObject["time"]!!.jsonObject
        assertEquals(1, time["hours"]!!.jsonPrimitive.int)
        assertEquals(2, time["minutes"]!!.jsonPrimitive.int)
        assertEquals(3, time["seconds"]!!.jsonPrimitive.int)
        assertEquals(456, time["milliseconds"]!!.jsonPrimitive.int)
    }

    @Test
    fun `snapshot builds playback state`() = runTest {
        val transport = FakeTransport(
            mapOf(
                KodiMethod.APP_GET_PROPERTIES to """{"volume":42,"muted":false,"version":{"major":21,"minor":2}}""",
                KodiMethod.PLAYER_GET_ACTIVE to """[{"playerid":1,"type":"video"}]""",
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
}

class KodiHttpTransportTest {
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() = server.close()

    @Test
    fun `sends JSON-RPC 2 request with basic auth`() = runTest {
        server.enqueue(MockResponse.Builder().body("""{"id":1,"jsonrpc":"2.0","result":"pong"}""").build())
        val transport = KodiHttpTransport(OkHttpClient(), server.url("/").toString(), "kodi", "secret")
        assertTrue(KodiClient(transport).ping())

        val request = server.takeRequest()
        assertEquals("/jsonrpc", request.url.encodedPath)
        assertEquals("Basic a29kaTpzZWNyZXQ=", request.headers["Authorization"])
        val body = Json.parseToJsonElement(request.body!!.utf8()).jsonObject
        assertEquals("2.0", body["jsonrpc"]!!.jsonPrimitive.contentOrNull)
        assertEquals("JSONRPC.Ping", body["method"]!!.jsonPrimitive.contentOrNull)
    }

    @Test
    fun `maps JSON-RPC errors to KodiRpcException`() = runTest {
        server.enqueue(
            MockResponse.Builder()
                .body("""{"id":1,"jsonrpc":"2.0","error":{"code":-32601,"message":"Method not found."}}""")
                .build(),
        )
        val transport = KodiHttpTransport(OkHttpClient(), server.url("/").toString())
        try {
            transport.call("Nope.Nope")
            fail("exception attendue")
        } catch (e: KodiRpcException) {
            assertEquals(-32601, e.code)
        }
    }
}

class KodiWebSocketTransportTest {
    @Test
    fun `correlates responses by id and forwards notifications`() = runBlocking {
        val server = MockWebServer()
        server.enqueue(
            MockResponse.Builder().webSocketUpgrade(object : WebSocketListener() {
                override fun onMessage(webSocket: WebSocket, text: String) {
                    val id = Json.parseToJsonElement(text).jsonObject["id"]!!.jsonPrimitive.int
                    webSocket.send("""{"jsonrpc":"2.0","method":"Player.OnPlay","params":{"data":{}}}""")
                    webSocket.send("""{"jsonrpc":"2.0","id":$id,"result":"pong"}""")
                }

                override fun onOpen(webSocket: WebSocket, response: Response) = Unit
            }).build(),
        )
        server.start()
        val url = server.url("/jsonrpc").toString().replaceFirst("http", "ws")
        val transport = KodiWebSocketTransport(OkHttpClient(), url)
        try {
            transport.notifications.test {
                assertEquals("pong", transport.call(KodiMethod.PING).jsonPrimitive.contentOrNull)
                assertEquals("Player.OnPlay", awaitItem().method)
                cancelAndIgnoreRemainingEvents()
            }
        } finally {
            transport.close()
            server.close()
        }
    }
}
