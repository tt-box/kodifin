package io.github.jmorille.zapette.kodi

import io.github.jmorille.zapette.model.Device
import io.github.jmorille.zapette.model.DeviceKind
import io.github.jmorille.zapette.model.RemoteKey
import io.github.jmorille.zapette.model.UdpDatagram
import io.github.jmorille.zapette.model.UdpDestination
import io.github.jmorille.zapette.model.UdpOptions
import io.github.jmorille.zapette.model.UdpSocket
import io.github.jmorille.zapette.model.UdpSocketFactory
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import org.junit.Test
import java.io.IOException
import java.nio.ByteBuffer
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

/** En-tête attendu, construit champ par champ d'après la spécification publique. */
private fun header(type: Int, seq: Int, max: Int, payloadSize: Int, uid: Int): ByteArray =
    bytes('X'.code, 'B'.code, 'M'.code, 'C'.code, 2, 0) +
        bytes(type ushr 8, type and 0xFF) +
        ByteBuffer.allocate(4).putInt(seq).array() +
        ByteBuffer.allocate(4).putInt(max).array() +
        bytes(payloadSize ushr 8, payloadSize and 0xFF) +
        ByteBuffer.allocate(4).putInt(uid).array() +
        ByteArray(10)

class KodiEventPacketTest {
    private val uid = 0x01020304

    @Test
    fun `ping and bye are bare 32 byte headers`() {
        assertContentEquals(header(0x05, 1, 1, 0, uid), KodiEventPacket.ping(uid))
        assertContentEquals(header(0x02, 1, 1, 0, uid), KodiEventPacket.bye(uid))
        assertEquals(KodiEventPacket.HEADER_SIZE, KodiEventPacket.ping(uid).size)
    }

    @Test
    fun `helo carries the name, no icon, port and reserved fields`() {
        val payload = "Zap".toByteArray() + bytes(0) + bytes(0) + bytes(0, 0) + ByteArray(4) + ByteArray(4)
        assertContentEquals(header(0x01, 1, 1, payload.size, uid) + payload, KodiEventPacket.helo(uid, "Zap"))
    }

    @Test
    fun `button by name uses the keymap and the name flag`() {
        val packet = KodiEventPacket.buttonByName(uid, "KB", "up")
        val flags = 0x01 or 0x02 or 0x10 or 0x20 // USE_NAME | DOWN | QUEUE | NO_REPEAT
        val payload = bytes(0, 0) + bytes(0, flags) + bytes(0, 0) + "KB".toByteArray() + bytes(0) + "up".toByteArray() + bytes(0)
        assertContentEquals(header(0x03, 1, 1, payload.size, uid) + payload, packet)
    }

    @Test
    fun `button by code clears the name flag and sets amount flag when needed`() {
        val packet = KodiEventPacket.buttonByCode(
            uid,
            code = 0xF00D,
            map = "R1",
            flags = KodiEventPacket.ButtonFlags.DOWN or KodiEventPacket.ButtonFlags.USE_NAME,
            amount = 0x1234,
        )
        val payload = bytes(0xF0, 0x0D) + bytes(0, 0x02 or 0x08) + bytes(0x12, 0x34) + "R1".toByteArray() + bytes(0) + bytes(0)
        assertContentEquals(header(0x03, 1, 1, payload.size, uid) + payload, packet)
    }

    @Test
    fun `release flag is preserved for up events`() {
        val packet = KodiEventPacket.buttonByName(uid, "R1", "select", flags = KodiEventPacket.ButtonFlags.UP)
        assertEquals(0x05, packet[32 + 3].toInt()) // UP | USE_NAME, octet de poids faible des drapeaux
    }

    @Test
    fun `large payloads are split with sequence numbers`() {
        val payload = ByteArray(KodiEventPacket.MAX_PAYLOAD_SIZE + 10) { it.toByte() }
        val packets = KodiEventPacket.encode(KodiEventPacket.Type.HELO, uid, payload)
        assertEquals(2, packets.size)
        assertContentEquals(header(0x01, 1, 2, KodiEventPacket.MAX_PAYLOAD_SIZE, uid), packets[0].copyOfRange(0, 32))
        assertContentEquals(header(0x01, 2, 2, 10, uid), packets[1].copyOfRange(0, 32))
        assertEquals(KodiEventPacket.MAX_PACKET_SIZE, packets[0].size)
        assertContentEquals(payload, packets[0].copyOfRange(32, packets[0].size) + packets[1].copyOfRange(32, packets[1].size))
    }

    @Test
    fun `names are utf8 and nul bytes are stripped`() {
        val packet = KodiEventPacket.helo(uid, "Télé\u0000")
        val name = packet.copyOfRange(32, packet.size).takeWhile { it != 0.toByte() }.toByteArray()
        assertEquals("Télé", String(name, Charsets.UTF_8))
    }
}

/** Faux socket UDP : enregistre les datagrammes, peut échouer à la demande. */
private class FakeUdp : UdpSocketFactory, UdpSocket {
    val sent = mutableListOf<Pair<UdpDestination, ByteArray>>()
    var opened = 0
    var closed = 0
    var failing = false

    override fun open(options: UdpOptions): UdpSocket = also { opened++ }

    override fun send(data: ByteArray, destination: UdpDestination) {
        if (failing) throw IOException("réseau injoignable")
        sent += destination to data
    }

    override fun receive(timeoutMs: Int): UdpDatagram? = null

    override fun close() {
        closed++
    }

    fun types(): List<Int> = sent.map { it.second[7].toInt() }
}

@OptIn(ExperimentalCoroutinesApi::class)
class KodiEventServerTest {
    private val fake = FakeUdp()

    private fun TestScope.server(port: Int = 9777) = KodiEventServer(
        "[fe80::1]",
        port,
        fake,
        pingIntervalMs = 1_000,
        uid = 42,
        context = StandardTestDispatcher(testScheduler),
    )

    @Test
    fun `helo is sent once before the first button, then pings, then bye`() = runTest {
        val es = server()
        es.sendButton("up")
        es.sendButton("down")
        assertEquals(listOf(0x01, 0x03, 0x03), fake.types())
        assertEquals(UdpDestination("fe80::1", 9777), fake.sent.first().first)
        advanceTimeBy(2_500)
        assertEquals(listOf(0x01, 0x03, 0x03, 0x05, 0x05), fake.types())
        es.stop()
        assertEquals(0x02, fake.types().last())
        assertEquals(1, fake.closed)
        advanceTimeBy(5_000)
        assertEquals(6, fake.sent.size, "plus aucun PING après BYE")
        assertFailsWith<io.github.jmorille.zapette.model.RemoteException> { es.sendButton("up") }
        assertEquals(1, fake.opened)
    }

    @Test
    fun `send failure is reported and the next send starts again with helo`() = runTest {
        val es = server()
        es.start()
        fake.failing = true
        assertFailsWith<io.github.jmorille.zapette.model.RemoteException> { es.sendButton("up") }
        assertEquals(1, fake.closed)
        fake.failing = false
        es.sendButton("up")
        assertEquals(listOf(0x01, 0x01, 0x03), fake.types())
        assertEquals(2, fake.opened)
        es.close()
        runCurrent()
    }

    @Test
    fun `factory reads the fixed extras`() {
        val base = Device("k", "Kodi", DeviceKind.KODI, "10.0.0.2")
        assertNull(KodiEventServer.fromDevice(base, fake))
        assertNull(KodiEventServer.fromDevice(base.copy(extras = mapOf("kodi_eventserver" to "false")), fake))
        val on = base.copy(extras = mapOf("kodi_eventserver" to "true"))
        assertTrue(KodiEventServer.fromDevice(on, fake) != null)
        assertEquals(9777, KodiEventServer.port(on))
        assertEquals(9800, KodiEventServer.port(on.copy(extras = on.extras + ("kodi_eventserver_port" to "9800"))))
        assertEquals(9777, KodiEventServer.port(on.copy(extras = on.extras + ("kodi_eventserver_port" to "70000"))))
        assertEquals(9777, KodiEventServer.port(on.copy(extras = on.extras + ("kodi_eventserver_port" to "abc"))))
    }
}

/** Transport JSON-RPC factice minimal. */
private class RecordingTransport : KodiTransport {
    val calls = mutableListOf<String>()
    override suspend fun call(method: String, params: JsonObject?): JsonElement {
        calls += method
        return Json.parseToJsonElement(if (method == KodiMethod.PLAYER_GET_ACTIVE) """[{"playerid":1,"type":"video"}]""" else "\"OK\"")
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class KodiControllerEventServerTest {
    private val device = Device("k", "Salon", DeviceKind.KODI, "10.0.0.2", extras = mapOf("kodi_eventserver" to "true"))
    private val fake = FakeUdp()

    private fun TestScope.controller(transport: RecordingTransport) = KodiController(
        device,
        KodiClient(transport),
        eventServer = KodiEventServer("10.0.0.2", 9777, fake, uid = 7, context = StandardTestDispatcher(testScheduler)),
    )

    private fun buttonName(packet: ByteArray): String =
        String(packet.copyOfRange(32 + 6, packet.size).toList().let { b ->
            val afterMap = b.indexOf(0.toByte()) + 1
            b.subList(afterMap, b.size).takeWhile { it != 0.toByte() }.toByteArray()
        }, Charsets.UTF_8)

    @Test
    fun `navigation and playback keys go through the event server`() = runTest {
        val transport = RecordingTransport()
        val c = controller(transport)
        for (key in KodiController.EVENT_BUTTONS.keys) c.send(key)
        assertEquals(emptyList(), transport.calls)
        val buttons = fake.sent.drop(1).map { buttonName(it.second) }
        assertEquals(KodiController.EVENT_BUTTONS.values.toList(), buttons)
        c.disconnect()
        assertEquals(0x02, fake.types().last())
    }

    @Test
    fun `volume, home and text stay on json-rpc`() = runTest {
        val transport = RecordingTransport()
        val c = controller(transport)
        c.send(RemoteKey.VOLUME_UP)
        c.send(RemoteKey.MUTE)
        c.send(RemoteKey.HOME)
        c.sendText("abc")
        assertEquals(
            listOf(KodiMethod.APP_SET_VOLUME, KodiMethod.APP_SET_MUTE, KodiMethod.INPUT_HOME, KodiMethod.INPUT_SEND_TEXT),
            transport.calls,
        )
        assertTrue(fake.sent.isEmpty())
        c.disconnect()
    }

    @Test
    fun `udp failure falls back to json-rpc`() = runTest {
        val transport = RecordingTransport()
        val c = controller(transport)
        fake.failing = true
        c.send(RemoteKey.UP)
        c.send(RemoteKey.PLAY_PAUSE)
        assertEquals(listOf(KodiMethod.INPUT_UP, KodiMethod.PLAYER_GET_ACTIVE, KodiMethod.PLAYER_PLAY_PAUSE), transport.calls)
        c.disconnect()
    }
}
