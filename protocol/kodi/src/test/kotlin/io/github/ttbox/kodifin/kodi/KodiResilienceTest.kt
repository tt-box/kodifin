package io.github.ttbox.kodifin.kodi

import io.github.ttbox.kodifin.model.Device
import io.github.ttbox.kodifin.model.DeviceKind
import io.github.ttbox.kodifin.model.RemoteError
import io.github.ttbox.kodifin.model.RemoteException
import io.github.ttbox.kodifin.model.RemoteKey
import io.github.ttbox.kodifin.model.UdpDatagram
import io.github.ttbox.kodifin.model.UdpDestination
import io.github.ttbox.kodifin.model.UdpOptions
import io.github.ttbox.kodifin.model.UdpSocket
import io.github.ttbox.kodifin.model.UdpSocketFactory
import io.github.ttbox.kodifin.testing.release
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.junit.Test
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.PortUnreachableException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Faux UDP : refus ICMP simulé à la réception (sonde) ou à l'envoi, fil de chaque envoi noté. */
private class RefusingUdp(
    var refuseOnReceive: Boolean = false,
    var refuseOnSend: Boolean = false,
) : UdpSocketFactory, UdpSocket {
    val sent = mutableListOf<ByteArray>()
    val senderThreads = mutableListOf<Thread>()
    var opened = 0
    var closed = 0

    override fun open(options: UdpOptions): UdpSocket = also { opened++ }

    override fun send(data: ByteArray, destination: UdpDestination) {
        if (refuseOnSend) throw PortUnreachableException("ICMP port unreachable")
        sent += data
        senderThreads += Thread.currentThread()
    }

    override fun receive(timeoutMs: Int): UdpDatagram? {
        if (refuseOnReceive) throw PortUnreachableException("ICMP port unreachable")
        return null
    }

    override fun close() {
        closed++
    }

    fun types(): List<Int> = sent.map { it[7].toInt() }
}

/** Transport factice : Kodi répond, PVR muet (ou non), réglages de l'Event Server configurables. */
private class EsScriptedTransport(
    private val pvrHangs: Boolean = false,
    private val esEnabled: Boolean? = true,
    private val esAllInterfaces: Boolean? = true,
) : KodiTransport {
    val calls = mutableListOf<String>()

    override suspend fun call(method: String, params: JsonObject?): JsonElement {
        calls += method
        val body = when (method) {
            KodiMethod.PING -> "\"pong\""
            KodiMethod.VERSION -> """{"version":{"major":13,"minor":5,"patch":0}}"""
            KodiMethod.PVR_GET_CHANNEL_GROUPS -> if (pvrHangs) awaitCancellation() else """{"channelgroups":[{"channelgroupid":1}]}"""
            KodiMethod.SETTINGS_GET_VALUE -> {
                val value = when (params!!["setting"]!!.jsonPrimitive.content) {
                    KodiController.SETTING_ES_ENABLED -> esEnabled
                    else -> esAllInterfaces
                }
                if (value == null) throw KodiRpcException(-32602, "Invalid params.") else """{"value":$value}"""
            }
            KodiMethod.PLAYER_GET_ACTIVE -> "[]"
            else -> "\"OK\""
        }
        return Json.parseToJsonElement(body)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class KodiConnectResilienceTest {
    private val device = Device("k", "Salon", DeviceKind.KODI, "10.0.0.2", extras = mapOf("kodi_eventserver" to "true"))

    private fun TestScope.eventServer(udp: RefusingUdp) =
        KodiEventServer("10.0.0.2", 9777, udp, uid = 7, context = StandardTestDispatcher(testScheduler))

    @Test
    fun `a silent pvr does not block the connection`() = runTest {
        val transport = EsScriptedTransport(pvrHangs = true)
        val c = KodiController(device.copy(extras = emptyMap()), KodiClient(transport), probeContext = EmptyCoroutineContext)
        c.connect()
        assertEquals(KodiController.PVR_PROBE_TIMEOUT_MS, currentTime, "connexion bornée par le délai du PVR")
    }

    @Test
    fun `a responsive pvr is detected without waiting`() = runTest {
        val c = KodiController(device.copy(extras = emptyMap()), KodiClient(EsScriptedTransport()), probeContext = EmptyCoroutineContext)
        c.connect()
        assertEquals(0, currentTime)
    }

    @Test
    fun `event server disabled in kodi settings sends keys over json-rpc`() = runTest {
        val udp = RefusingUdp()
        val transport = EsScriptedTransport(esAllInterfaces = false)
        val c = KodiController(device, KodiClient(transport), eventServer = eventServer(udp), probeContext = EmptyCoroutineContext)
        c.connect()
        c.send(RemoteKey.UP)
        assertTrue(udp.sent.isEmpty(), "aucun paquet UDP : Kodi ignorerait les touches")
        assertEquals(KodiMethod.INPUT_UP, transport.calls.last())
        c.disconnect()
    }

    @Test
    fun `unreadable settings keep the event server`() = runTest {
        val udp = RefusingUdp()
        val transport = EsScriptedTransport(esEnabled = null, esAllInterfaces = null)
        val c = KodiController(device, KodiClient(transport), eventServer = eventServer(udp), probeContext = EmptyCoroutineContext)
        c.connect()
        assertEquals(listOf(0x01), udp.types(), "HELO envoyé dès la connexion")
        c.send(RemoteKey.UP)
        assertEquals(listOf(0x01, 0x03), udp.types())
        assertFalse(KodiMethod.INPUT_UP in transport.calls)
        c.disconnect()
    }

    @Test
    fun `port unreachable after helo switches keys to json-rpc for the session`() = runTest {
        val udp = RefusingUdp(refuseOnReceive = true)
        val transport = EsScriptedTransport()
        val es = eventServer(udp)
        val c = KodiController(device, KodiClient(transport), eventServer = es, probeContext = EmptyCoroutineContext)
        c.connect()
        assertTrue(es.isUnavailable)
        udp.refuseOnReceive = false
        c.send(RemoteKey.UP)
        c.send(RemoteKey.DOWN)
        assertEquals(listOf(0x01), udp.types(), "seul le HELO initial est parti")
        assertEquals(1, udp.opened, "pas de nouvelle tentative UDP")
        assertEquals(listOf(KodiMethod.INPUT_UP, KodiMethod.INPUT_DOWN), transport.calls.takeLast(2))
        c.disconnect()
    }

    @Test
    fun `port unreachable on send is permanent`() = runTest {
        val udp = RefusingUdp()
        val es = eventServer(udp)
        val transport = EsScriptedTransport()
        val c = KodiController(device, KodiClient(transport), eventServer = es, probeContext = EmptyCoroutineContext)
        es.start()
        udp.refuseOnSend = true
        c.send(RemoteKey.SELECT)
        assertTrue(es.isUnavailable)
        udp.refuseOnSend = false
        c.send(RemoteKey.SELECT)
        assertEquals(1, udp.opened)
        assertEquals(listOf(KodiMethod.INPUT_SELECT, KodiMethod.INPUT_SELECT), transport.calls)
        assertFailsWith<RemoteException> { es.sendButton("up") }
        c.disconnect()
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class KodiEventServerShutdownTest {
    @Test
    fun `close does no network on the caller and sends bye on the io context`() = runTest {
        val udp = RefusingUdp()
        val es = KodiEventServer("10.0.0.2", 9777, udp, uid = 7, context = StandardTestDispatcher(testScheduler))
        es.start()
        es.close()
        assertEquals(listOf(0x01), udp.types(), "BYE pas encore envoyé : rien sur le fil appelant")
        assertEquals(0, udp.closed)
        runCurrent()
        assertEquals(listOf(0x01, 0x02), udp.types())
        assertEquals(1, udp.closed)
        es.close()
        es.stop()
        runCurrent()
        assertEquals(1, udp.closed, "fermeture idempotente")
        assertFailsWith<RemoteException> { es.sendButton("up") }
        assertEquals(1, udp.opened)
    }

    @Test
    fun `close before any send opens nothing`() = runTest {
        val udp = RefusingUdp()
        val es = KodiEventServer("10.0.0.2", 9777, udp, context = StandardTestDispatcher(testScheduler))
        es.close()
        runCurrent()
        assertEquals(0, udp.opened)
        assertTrue(udp.sent.isEmpty())
    }

    @Test
    fun `transient send failure keeps the event server usable`() = runTest {
        var failNext = true
        val udp = object : UdpSocketFactory, UdpSocket {
            var opened = 0
            override fun open(options: UdpOptions): UdpSocket = also { opened++ }
            override fun send(data: ByteArray, destination: UdpDestination) {
                if (data[7].toInt() == 0x03 && failNext) {
                    failNext = false
                    throw IOException("réseau absent")
                }
            }
            override fun receive(timeoutMs: Int): UdpDatagram? = throw IOException("non concluant")
            override fun close() = Unit
        }
        val es = KodiEventServer("10.0.0.2", 9777, udp, context = StandardTestDispatcher(testScheduler))
        assertFailsWith<RemoteException> { es.sendButton("up") }
        assertFalse(es.isUnavailable)
        es.sendButton("up")
        assertEquals(2, udp.opened)
        es.stop()
    }
}

class ConnectedUdpSocketTest {
    @Test
    fun `ipv4 is preferred when a name resolves to both families`() {
        val v6 = InetAddress.getByName("fe80::1")
        val v4 = InetAddress.getByName("192.168.1.10")
        assertEquals(v4, preferIpv4(listOf(v6, v4)))
        assertEquals(v6, preferIpv4(listOf(v6)))
        assertEquals(null, preferIpv4(emptyList()))
    }

    @Test
    fun `datagrams reach the resolved ipv4 address through a connected socket`() {
        DatagramSocket(0, InetAddress.getByName("127.0.0.1")).use { receiver ->
            receiver.soTimeout = 5_000
            val resolutions = AtomicInteger()
            val factory = ConnectedUdpSocketFactory { host ->
                resolutions.incrementAndGet()
                assertEquals("kodi.lan", host)
                listOf(InetAddress.getByName("::1"), InetAddress.getByName("127.0.0.1"))
            }
            factory.open(UdpOptions()).use { socket ->
                val destination = UdpDestination("kodi.lan", receiver.localPort)
                socket.send(byteArrayOf(1, 2, 3), destination)
                socket.send(byteArrayOf(4), destination)
                val packet = DatagramPacket(ByteArray(16), 16)
                receiver.receive(packet)
                assertEquals(3, packet.length)
                receiver.receive(packet)
                assertEquals(1, packet.length)
                assertEquals(1, resolutions.get(), "résolution unique")
                assertEquals(null, socket.receive(10))
            }
        }
    }

    @Test
    fun `unresolvable host is an io failure`() {
        val factory = ConnectedUdpSocketFactory { emptyList() }
        factory.open(UdpOptions()).use { socket ->
            assertFailsWith<IOException> { socket.send(byteArrayOf(1), UdpDestination("nulle.part", 9777)) }
        }
    }
}

class KodiWebSocketLifecycleTest {
    @Test
    fun `nothing is opened before the first call and nothing after close`() = runBlocking {
        val server = MockWebServer()
        server.start()
        val http = OkHttpClient()
        val url = server.url("/jsonrpc").toString().replaceFirst("http", "ws")
        val transport = KodiWebSocketTransport(http, url, 1_000, reconnectDelaysMs = listOf(10))
        try {
            Thread.sleep(200)
            assertEquals(0, server.requestCount, "aucune connexion à la construction")
            transport.close()
            transport.open()
            assertIs<RemoteError.Interrupted>(assertFailsWith<RemoteException> { transport.call(KodiMethod.PING) }.reason)
            Thread.sleep(200)
            assertEquals(0, server.requestCount, "ni connexion ni reconnexion après close")
        } finally {
            transport.close()
            http.release()
            server.close()
        }
    }

    @Test
    fun `an error with a null id fails the oldest pending call instead of timing out`() = runBlocking {
        val server = MockWebServer()
        val received = CompletableDeferred<Unit>()
        server.enqueue(
            MockResponse.Builder().webSocketUpgrade(
                object : WebSocketListener() {
                    override fun onMessage(webSocket: WebSocket, text: String) {
                        Json.parseToJsonElement(text).jsonObject
                        webSocket.send("""{"jsonrpc":"2.0","id":null,"error":{"code":-32700,"message":"Parse error."}}""")
                        received.complete(Unit)
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
        val transport = KodiWebSocketTransport(http, url, timeoutMs = 30_000)
        try {
            withTimeout(5_000) {
                val call = async { runCatching { transport.call(KodiMethod.PING) } }
                val error = call.await().exceptionOrNull()
                assertIs<KodiRpcException>(error)
                assertEquals(-32700, error.code)
                received.await()
            }
        } finally {
            transport.close()
            http.release()
            server.close()
        }
    }

    @Test
    fun `an error with a null id and no pending call is ignored`() {
        val http = OkHttpClient()
        val transport = KodiWebSocketTransport(http, "ws://kodi.invalid/jsonrpc")
        try {
            transport.handle("""{"jsonrpc":"2.0","id":null,"error":{"code":-32700,"message":"Parse error."}}""")
        } finally {
            transport.close()
            http.release()
        }
    }
}
