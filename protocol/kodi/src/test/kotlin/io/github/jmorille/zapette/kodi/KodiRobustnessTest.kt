package io.github.jmorille.zapette.kodi

import io.github.jmorille.zapette.model.Device
import io.github.jmorille.zapette.model.DeviceKind
import io.github.jmorille.zapette.model.HttpStatusException
import io.github.jmorille.zapette.model.RemoteException
import io.github.jmorille.zapette.testing.assertFailsWithMessage
import io.github.jmorille.zapette.testing.awaitRequest
import io.github.jmorille.zapette.testing.release
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
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
import org.junit.Test
import java.net.UnknownHostException
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

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
        assertFailsWithMessage<RemoteException>(KodiMethod.PLAYER_GET_ACTIVE) { client.activePlayers() }
        assertFailsWithMessage<RemoteException>(KodiMethod.PLAYER_GET_ITEM) { client.playerItem(1) }
        assertFailsWithMessage<RemoteException>(KodiMethod.VERSION) { client.apiVersion() }
        assertFailsWithMessage<RemoteException>(KodiMethod.APP_GET_PROPERTIES) { client.appProperties() }
    }

    @Test
    fun `players without usable id are skipped`() = runTest {
        val client = KodiClient(
            ScriptedTransport(
                mapOf(KodiMethod.PLAYER_GET_ACTIVE to """[{"type":"video"},"x",{"playerid":0,"type":"audio"},{"playerid":2}]"""),
            ),
        )
        assertEquals(listOf(KodiPlayer(0, "audio"), KodiPlayer(2, "unknown")), client.activePlayers())
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
        assertEquals(KodiPlayerProperties(speed = 0, timeMs = 0, totalTimeMs = 0, percentage = 0.0), client.playerProperties(1))
        assertEquals(KodiAppProperties(volume = 0, muted = false, version = "21.0"), client.appProperties())
    }

    @Test
    fun `negative seek position is clamped to zero`() = runTest {
        val transport = ScriptedTransport()
        KodiClient(transport).seekTo(1, -5_000)
        val time = transport.calls.single().second!!["value"]!!.jsonObject["time"]!!.jsonObject
        assertEquals(setOf("hours", "minutes", "seconds", "milliseconds"), time.keys)
        assertTrue(time.values.all { it.jsonPrimitive.int == 0 })
    }

    @Test
    fun `connect fails when ping does not answer pong`() = runTest {
        val controller = KodiController(device, KodiClient(ScriptedTransport(mapOf(KodiMethod.PING to "\"nope\""))))
        assertFailsWithMessage<RemoteException>("serveur Kodi") { controller.connect() }
        val ok = ScriptedTransport(mapOf(KodiMethod.PING to "\"pong\""))
        KodiController(device, KodiClient(ok)).connect()
        // Version JSON-RPC lue une fois à la connexion (forme de Player.Seek) ; réponse invalide tolérée.
        assertEquals(listOf(KodiMethod.PING, KodiMethod.VERSION), ok.calls.map { it.first })
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
    fun `transport errors other than JSON-RPC errors propagate from snapshot`() = runTest {
        val transport = ScriptedTransport(
            responses = mapOf(
                KodiMethod.APP_GET_PROPERTIES to """{"volume":30,"muted":true}""",
                KodiMethod.PLAYER_GET_ACTIVE to """[{"playerid":1,"type":"video"}]""",
            ),
            errors = mapOf(KodiMethod.PLAYER_GET_PROPERTIES to RemoteException("coupure réseau")),
        )
        assertFailsWithMessage<RemoteException>("coupure réseau") { KodiController(device, KodiClient(transport)).snapshot() }
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
            client.release()
        }
    }

    @Test
    fun `invalid JSON body raises RemoteException`() = withServer { server, http ->
        server.enqueue(MockResponse.Builder().body("<html>pas du JSON</html>").build())
        val transport = KodiHttpTransport(http, server.url("/").toString())
        val e = assertFailsWithMessage<RemoteException>("Réponse Kodi invalide") { transport.call(KodiMethod.PING) }
        assertFalse(e is KodiRpcException)
        assertTrue(e.cause is IllegalArgumentException, "cause : ${e.cause}")
    }

    @Test
    fun `malformed error member still raises KodiRpcException`() = withServer { server, http ->
        server.enqueue(MockResponse.Builder().body("""{"id":1,"jsonrpc":"2.0","error":"boom"}""").build())
        val transport = KodiHttpTransport(http, server.url("/").toString())
        val e = assertFailsWithMessage<KodiRpcException>("erreur inconnue") { transport.call(KodiMethod.PING) }
        assertEquals(-1, e.code)
    }

    @Test
    fun `null result is returned as JsonNull`() = withServer { server, http ->
        server.enqueue(MockResponse.Builder().body("""{"id":1,"jsonrpc":"2.0","error":null}""").build())
        val transport = KodiHttpTransport(http, server.url("/").toString())
        assertEquals(kotlinx.serialization.json.JsonNull, transport.call(KodiMethod.PING))
    }

    @Test
    fun `401 gives a clear message without leaking credentials`() = withServer { server, http ->
        server.enqueue(MockResponse.Builder().code(401).build())
        val transport = KodiHttpTransport(http, server.url("/").toString(), "kodi", "s3cr3t")
        val e = assertFailsWithMessage<HttpStatusException>("Identifiants Kodi refusés") { transport.call(KodiMethod.PING) }
        assertEquals(401, e.code)
        assertFalse(e.message!!.contains("s3cr3t"))
    }

    @Test
    fun `other HTTP errors are propagated unchanged`() = withServer { server, http ->
        server.enqueue(MockResponse.Builder().code(503).build())
        val transport = KodiHttpTransport(http, server.url("/").toString())
        val e = assertFailsWithMessage<HttpStatusException>("HTTP 503") { transport.call(KodiMethod.PING) }
        assertEquals(503, e.code)
    }

    @Test
    fun `basic auth encodes non ASCII passwords in UTF-8`() = withServer { server, http ->
        server.enqueue(MockResponse.Builder().body("""{"id":1,"jsonrpc":"2.0","result":"pong"}""").build())
        KodiHttpTransport(http, server.url("/").toString(), "kodi", "été").call(KodiMethod.PING)
        // base64("kodi:été") encodé en UTF-8
        assertEquals("Basic a29kaTrDqXTDqQ==", server.awaitRequest().headers["Authorization"])
    }

    @Test
    fun `no Authorization header without username`() = withServer { server, http ->
        server.enqueue(MockResponse.Builder().body("""{"id":1,"jsonrpc":"2.0","result":"pong"}""").build())
        KodiHttpTransport(http, server.url("/").toString(), "", "ignoré").call(KodiMethod.PING)
        assertNull(server.awaitRequest().headers["Authorization"])
    }

    @Test
    fun `request ids increase across calls`() = withServer { server, http ->
        repeat(2) { server.enqueue(MockResponse.Builder().body("""{"jsonrpc":"2.0","result":"pong"}""").build()) }
        val transport = KodiHttpTransport(http, server.url("/").toString())
        transport.call(KodiMethod.PING)
        transport.call(KodiMethod.PING)
        val ids = List(2) { Json.parseToJsonElement(server.awaitRequest().body!!.utf8()).jsonObject["id"]!!.jsonPrimitive.int }
        assertEquals(listOf(1, 2), ids)
    }

    @Test
    fun `invalid base url raises RemoteException`() = runTest {
        val transport = KodiHttpTransport(OkHttpClient(), "http://hôte invalide:8080")
        assertFailsWithMessage<RemoteException>("Adresse Kodi invalide") { transport.call(KodiMethod.PING) }
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
                http.release()
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
        val e = assertFailsWithMessage<RemoteException>("délai imparti") { transport.call(KodiMethod.PING) }
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
        assertFailsWithMessage<RemoteException>("WebSocket Kodi fermée") { transport.call(KodiMethod.PING) }
    }

    @Test
    fun `close fails in-flight calls and rejects later ones`() {
        // Signal du serveur : la requête est bien partie, l'appel est donc en cours d'attente.
        val received = CompletableDeferred<String>()
        val listener = object : SilentServer() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                received.complete(text)
            }
        }
        wsTest(listener, 60_000) { transport ->
            val inFlight = async { runCatching { transport.call(KodiMethod.PING) } }
            val sent = Json.parseToJsonElement(received.await()).jsonObject
            assertEquals(KodiMethod.PING, sent["method"]!!.jsonPrimitive.content)
            assertTrue(inFlight.isActive, "l'appel doit encore attendre sa réponse")
            transport.close()
            val error = inFlight.await().exceptionOrNull()
            assertIs<RemoteException>(error)
            assertTrue(error.message!!.contains("fermée"), error.message)
            assertFailsWithMessage<RemoteException>("fermée") { transport.call(KodiMethod.PING) }
        }
    }

    @Test
    fun `unexpected message shapes are ignored`() = runBlocking<Unit> {
        // Nom réservé (RFC 2606) et résolveur qui échoue : aucune connexion réelle n'est tentée.
        val http = OkHttpClient.Builder().dns { throw UnknownHostException(it) }.build()
        val transport = KodiWebSocketTransport(http, "ws://kodi.invalid/jsonrpc", timeoutMs = 2_000)
        try {
            transport.handle("""{"id":{"x":1},"method":42}""")
            transport.handle("""[1,2,3]""")
            transport.handle("""pas du json""")
            transport.handle("""{"id":12345,"result":"orphelin"}""")
            // L'échec de connexion est remonté aux appels, sans attendre le délai.
            assertFailsWithMessage<RemoteException>("WebSocket Kodi fermée") { transport.call(KodiMethod.PING) }
        } finally {
            transport.close()
            http.release()
        }
    }
}
