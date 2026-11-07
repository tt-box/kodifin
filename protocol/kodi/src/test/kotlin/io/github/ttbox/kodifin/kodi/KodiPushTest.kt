package io.github.ttbox.kodifin.kodi

import app.cash.turbine.test
import io.github.ttbox.kodifin.model.Device
import io.github.ttbox.kodifin.model.DeviceKind
import io.github.ttbox.kodifin.testing.release
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
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
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import org.junit.Test
import kotlin.test.assertEquals

/** Transport factice qui pousse des notifications (comme le WebSocket). */
private class PushTransport : KodiTransport {
    val events = MutableSharedFlow<KodiNotification>(extraBufferCapacity = 16)
    override val notifications = events
    override val pushesNotifications = true
    var volumeReads = 0

    override suspend fun call(method: String, params: JsonObject?): JsonElement = when (method) {
        KodiMethod.APP_GET_PROPERTIES -> {
            volumeReads++
            Json.parseToJsonElement("""{"volume":$volumeReads,"muted":false}""")
        }
        KodiMethod.PLAYER_GET_ACTIVE -> Json.parseToJsonElement("[]")
        else -> Json.parseToJsonElement("\"OK\"")
    }
}

class KodiPushTest {
    private val device = Device("k", "Salon", DeviceKind.KODI, "10.0.0.2")

    @Test
    fun `with pushed notifications the state is refreshed on events, with a slow fallback poll`() = runTest {
        val transport = PushTransport()
        val controller = KodiController(device, KodiClient(transport), pollIntervalMs = 1_000, fallbackPollIntervalMs = 10_000)
        controller.playbackState().test {
            assertEquals(1, awaitItem().volumePercent)
            // Notification sans rapport avec l'état : aucune relecture.
            transport.events.emit(KodiNotification("GUI.OnScreensaverActivated", JsonNull))
            testScheduler.advanceTimeBy(5_000)
            testScheduler.runCurrent()
            expectNoEvents()
            // Changement de volume : relecture immédiate, sans attendre l'interrogation de secours.
            transport.events.emit(KodiNotification("Application.OnVolumeChanged", JsonNull))
            testScheduler.runCurrent()
            assertEquals(2, expectMostRecentItem().volumePercent)
            // Rien pendant 10 s : interrogation de secours (position de lecture).
            testScheduler.advanceTimeBy(10_001)
            testScheduler.runCurrent()
            assertEquals(3, expectMostRecentItem().volumePercent)
            for (method in listOf("Player.OnPlay", "Player.OnSeek", "Playlist.OnClear", KodiWebSocketTransport.RECONNECTED)) {
                val before = transport.volumeReads
                transport.events.emit(KodiNotification(method, JsonNull))
                testScheduler.runCurrent()
                assertEquals(before + 1, expectMostRecentItem().volumePercent, method)
            }
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `refresh notifications cover player, volume and playlist events`() {
        val expected = setOf(
            "Player.OnPlay", "Player.OnPause", "Player.OnResume", "Player.OnStop", "Player.OnSeek",
            "Player.OnSpeedChanged", "Player.OnAVChange", "Application.OnVolumeChanged",
            "Playlist.OnAdd", "Playlist.OnRemove", "Playlist.OnClear",
        )
        assertEquals(emptySet(), expected - KodiController.STATE_NOTIFICATIONS)
    }

    @Test
    fun `HTTP transport does not push notifications`() {
        val http = OkHttpClient()
        try {
            assertEquals(false, KodiHttpTransport(http, "http://10.0.0.2:8080").pushesNotifications)
        } finally {
            http.release()
        }
    }

    @Test
    fun `WebSocket timeout follows the device client and keepalive pings are enabled`() = runBlocking {
        val base = OkHttpClient.Builder().dns { throw UnknownHostException(it) }
        assertEquals(3_000, KodiWebSocketTransport.defaultTimeoutMs(base.readTimeout(3, TimeUnit.SECONDS).build()))
        assertEquals(
            7_000,
            KodiWebSocketTransport.defaultTimeoutMs(base.readTimeout(0, TimeUnit.SECONDS).callTimeout(7, TimeUnit.SECONDS).build()),
        )
        assertEquals(
            10_000,
            KodiWebSocketTransport.defaultTimeoutMs(base.readTimeout(0, TimeUnit.SECONDS).callTimeout(0, TimeUnit.SECONDS).build()),
        )

        val http = base.build()
        val transport = KodiWebSocketTransport(http, "ws://kodi.invalid/jsonrpc", reconnectDelaysMs = emptyList())
        val custom = KodiWebSocketTransport(
            http.newBuilder().pingInterval(5, TimeUnit.SECONDS).build(),
            "ws://kodi.invalid/jsonrpc",
            reconnectDelaysMs = emptyList(),
        )
        try {
            assertEquals(KodiWebSocketTransport.PING_INTERVAL_MS, transport.pingIntervalMs)
            assertEquals(5_000, custom.pingIntervalMs, "intervalle du client conservé")
        } finally {
            transport.close()
            custom.close()
            http.release()
        }
    }

    @Test
    fun `WebSocket reconnects after the server closes and announces it`() = runBlocking {
        val server = MockWebServer()
        // Première connexion : Kodi la ferme aussitôt (redémarrage).
        server.enqueue(
            MockResponse.Builder().webSocketUpgrade(
                object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        webSocket.close(1001, "redémarrage")
                    }
                },
            ).build(),
        )
        // Seconde connexion : répond normalement.
        server.enqueue(
            MockResponse.Builder().webSocketUpgrade(
                object : WebSocketListener() {
                    override fun onMessage(webSocket: WebSocket, text: String) {
                        val id = Json.parseToJsonElement(text).jsonObject["id"]!!.jsonPrimitive.int
                        webSocket.send("""{"jsonrpc":"2.0","id":$id,"result":"pong"}""")
                    }

                    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                        webSocket.close(1000, null)
                    }
                },
            ).build(),
        )
        server.start()
        val http = OkHttpClient()
        val url = server.url("/jsonrpc").toString().replaceFirst("http", "ws")
        val transport = KodiWebSocketTransport(http, url, 2_000, reconnectDelaysMs = listOf(300))
        try {
            withTimeout(5_000) {
                transport.notifications.test {
                    assertEquals(KodiWebSocketTransport.RECONNECTED, awaitItem().method)
                    cancelAndIgnoreRemainingEvents()
                }
                assertEquals("pong", transport.call(KodiMethod.PING).jsonPrimitive.contentOrNull)
            }
        } finally {
            transport.close()
            http.release()
            server.close()
        }
    }
}
