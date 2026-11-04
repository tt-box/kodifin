package io.github.jmorille.zapette.kodi

import io.github.jmorille.zapette.model.Device
import io.github.jmorille.zapette.model.DeviceKind
import io.github.jmorille.zapette.model.HttpStatusException
import io.github.jmorille.zapette.model.RemoteException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Transport factice : réponses brutes par méthode, ou exception à lever. */
private class ScriptedTransport(
    private val responses: Map<String, String> = emptyMap(),
    private val errors: Map<String, Throwable> = emptyMap(),
) : KodiTransport {
    val calls = mutableListOf<Pair<String, JsonObject?>>()
    var closed = false

    override suspend fun call(method: String, params: JsonObject?): JsonElement {
        calls += method to params
        errors[method]?.let { throw it }
        return Json.parseToJsonElement(responses[method] ?: "\"OK\"")
    }

    override fun close() {
        closed = true
    }
}

private inline fun <reified T : Throwable> expect(block: () -> Unit): T {
    try {
        block()
    } catch (e: Throwable) {
        if (e is T) return e
        throw AssertionError("${T::class.simpleName} attendue, ${e::class.simpleName} reçue", e)
    }
    throw AssertionError("${T::class.simpleName} attendue")
}

class KodiClientRobustnessTest {
    private val device = Device("k", "Salon", DeviceKind.KODI, "10.0.0.2")

    @Test
    fun `unexpected result shapes raise RemoteException instead of crashing`() = runTest {
        val client = KodiClient(
            ScriptedTransport(
                mapOf(
                    KodiMethod.PLAYER_GET_ACTIVE to """{"oops":true}""",
                    KodiMethod.PLAYER_GET_ITEM to """{}""",
                    KodiMethod.VERSION to "null",
                    KodiMethod.APP_GET_PROPERTIES to "[]",
                ),
            ),
        )
        expect<RemoteException> { client.activePlayers() }
        expect<RemoteException> { client.playerItem(1) }
        expect<RemoteException> { client.apiVersion() }
        expect<RemoteException> { client.appProperties() }
    }

    @Test
    fun `players without usable id are skipped`() = runTest {
        val client = KodiClient(
            ScriptedTransport(
                mapOf(KodiMethod.PLAYER_GET_ACTIVE to """[{"type":"video"},"x",{"playerid":0,"type":"audio"}]"""),
            ),
        )
        assertEquals(listOf(KodiPlayer(0, "audio")), client.activePlayers())
    }

    @Test
    fun `malformed time and version fields fall back to defaults`() = runTest {
        val client = KodiClient(
            ScriptedTransport(
                mapOf(
                    KodiMethod.PLAYER_GET_PROPERTIES to """{"speed":{"x":1},"time":"12:00","totaltime":null}""",
                    KodiMethod.APP_GET_PROPERTIES to """{"volume":"fort","version":{"major":21}}""",
                ),
            ),
        )
        val props = client.playerProperties(1)
        assertEquals(0, props.speed)
        assertEquals(0, props.timeMs)
        assertEquals("21.0", client.appProperties().version)
    }

    @Test
    fun `negative seek position is clamped to zero`() = runTest {
        val transport = ScriptedTransport()
        KodiClient(transport).seekTo(1, -5_000)
        val time = transport.calls.single().second!!["value"]!!.jsonObject["time"]!!.jsonObject
        assertTrue(time.values.all { it.jsonPrimitive.int == 0 })
    }

    @Test
    fun `connect fails when ping does not answer pong`() = runTest {
        val controller = KodiController(device, KodiClient(ScriptedTransport(mapOf(KodiMethod.PING to "\"nope\""))))
        expect<RemoteException> { controller.connect() }
        KodiController(device, KodiClient(ScriptedTransport(mapOf(KodiMethod.PING to "\"pong\"")))).connect()
    }

    @Test
    fun `snapshot reports idle when the player stops between calls`() = runTest {
        val transport = ScriptedTransport(
            responses = mapOf(
                KodiMethod.APP_GET_PROPERTIES to """{"volume":30,"muted":true}""",
                KodiMethod.PLAYER_GET_ACTIVE to """[{"playerid":1,"type":"video"}]""",
                KodiMethod.PLAYER_GET_PROPERTIES to """{"speed":1}""",
            ),
            errors = mapOf(KodiMethod.PLAYER_GET_ITEM to KodiRpcException(-32100, "Failed to execute method.")),
        )
        val state = KodiController(device, KodiClient(transport)).snapshot()
        assertNull(state.title)
        assertFalse(state.isPlaying)
        assertEquals(30, state.volumePercent)
        assertTrue(state.muted)
    }

    @Test
    fun `disconnect closes the transport`() = runTest {
        val transport = ScriptedTransport()
        KodiController(device, KodiClient(transport)).disconnect()
        assertTrue(transport.closed)
    }
}

class KodiHttpTransportRobustnessTest {
    private fun withServer(block: suspend (MockWebServer, OkHttpClient) -> Unit) = runBlocking {
        val server = MockWebServer()
        server.start()
        val client = OkHttpClient()
        try {
            block(server, client)
        } finally {
            server.close()
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
        }
    }

    @Test
    fun `invalid JSON body raises RemoteException`() = withServer { server, http ->
        server.enqueue(MockResponse.Builder().body("<html>pas du JSON</html>").build())
        val transport = KodiHttpTransport(http, server.url("/").toString())
        val e = expect<RemoteException> { transport.call(KodiMethod.PING) }
        assertFalse(e is KodiRpcException)
    }

    @Test
    fun `malformed error member still raises KodiRpcException`() = withServer { server, http ->
        server.enqueue(MockResponse.Builder().body("""{"id":1,"jsonrpc":"2.0","error":"boom"}""").build())
        val transport = KodiHttpTransport(http, server.url("/").toString())
        assertEquals(-1, expect<KodiRpcException> { transport.call(KodiMethod.PING) }.code)
    }

    @Test
    fun `401 gives a clear message without leaking credentials`() = withServer { server, http ->
        server.enqueue(MockResponse.Builder().code(401).build())
        val transport = KodiHttpTransport(http, server.url("/").toString(), "kodi", "s3cr3t")
        val e = expect<HttpStatusException> { transport.call(KodiMethod.PING) }
        assertEquals(401, e.code)
        assertFalse(e.message!!.contains("s3cr3t"))
    }

    @Test
    fun `basic auth encodes non ASCII passwords in UTF-8`() = withServer { server, http ->
        server.enqueue(MockResponse.Builder().body("""{"id":1,"jsonrpc":"2.0","result":"pong"}""").build())
        KodiHttpTransport(http, server.url("/").toString(), "kodi", "été").call(KodiMethod.PING)
        // base64("kodi:été") encodé en UTF-8
        assertEquals("Basic a29kaTrDqXTDqQ==", server.takeRequest().headers["Authorization"])
    }

    @Test
    fun `invalid base url raises RemoteException`() = runTest {
        val transport = KodiHttpTransport(OkHttpClient(), "http://hôte invalide:8080")
        expect<RemoteException> { transport.call(KodiMethod.PING) }
    }
}

class KodiWebSocketTransportRobustnessTest {
    private fun wsTest(listener: WebSocketListener, timeoutMs: Long, block: suspend CoroutineScope.(KodiWebSocketTransport) -> Unit) =
        runBlocking {
            val server = MockWebServer()
            server.enqueue(MockResponse.Builder().webSocketUpgrade(listener).build())
            server.start()
            val http = OkHttpClient()
            val url = server.url("/jsonrpc").toString().replaceFirst("http", "ws")
            val transport = KodiWebSocketTransport(http, url, timeoutMs)
            try {
                withTimeout(5_000) { block(transport) }
            } finally {
                transport.close()
                http.dispatcher.executorService.shutdown()
                http.connectionPool.evictAll()
                server.close()
            }
        }

    /** Serveur muet qui répond à la fermeture pour un arrêt propre. */
    private open class SilentServer : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) = Unit

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null)
        }
    }

    @Test
    fun `timeout surfaces as RemoteException, not as a cancellation`() = wsTest(SilentServer(), 300) { transport ->
        val e = expect<RemoteException> { transport.call(KodiMethod.PING) }
        assertTrue(e.message!!.contains(KodiMethod.PING))
    }

    @Test
    fun `server initiated close fails pending calls immediately`() = wsTest(
        object : SilentServer() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                webSocket.close(1001, "bye")
            }
        },
        timeoutMs = 60_000,
    ) { transport ->
        // Sans traitement de onClosing, l'appel attendrait le délai de 60 s (le test échoue à 5 s).
        expect<RemoteException> { transport.call(KodiMethod.PING) }
    }

    @Test
    fun `close fails in-flight calls and rejects later ones`() = wsTest(SilentServer(), 60_000) { transport ->
        val inFlight = async { runCatching { transport.call(KodiMethod.PING) } }
        delay(200)
        transport.close()
        assertTrue(inFlight.await().exceptionOrNull() is RemoteException)
        expect<RemoteException> { transport.call(KodiMethod.PING) }
    }

    @Test
    fun `unexpected message shapes are ignored`() {
        val http = OkHttpClient()
        val transport = KodiWebSocketTransport(http, "ws://127.0.0.1:1/jsonrpc")
        try {
            transport.handle("""{"id":{"x":1},"method":42}""")
            transport.handle("""[1,2,3]""")
            transport.handle("""pas du json""")
        } catch (e: Exception) {
            fail("handle ne doit pas lever : $e")
        } finally {
            transport.close()
            http.dispatcher.executorService.shutdown()
        }
    }
}
