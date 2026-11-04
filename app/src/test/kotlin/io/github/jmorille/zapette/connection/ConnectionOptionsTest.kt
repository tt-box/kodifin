package io.github.jmorille.zapette.connection

import io.github.jmorille.zapette.data.DeviceSecrets
import io.github.jmorille.zapette.model.Capability
import io.github.jmorille.zapette.model.Device
import io.github.jmorille.zapette.model.DeviceKind
import io.github.jmorille.zapette.model.RemoteController
import io.github.jmorille.zapette.model.RemoteException
import io.github.jmorille.zapette.model.RemoteKey
import io.github.jmorille.zapette.ui.config.buildDevice
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private val KODI = Device("KODI:10.0.0.2:8080", "Salon", DeviceKind.KODI, "10.0.0.2", 8080)

class WakeOnLanTest {
    @Test
    fun `parses common MAC notations`() {
        val expected = byteArrayOf(0x58, 0x47, 0xCA.toByte(), 0x7E, 0x0D, 0x48)
        for (text in listOf("58:47:CA:7E:0D:48", "58-47-ca-7e-0d-48", "5847.CA7E.0D48", " 5847CA7E0D48 ")) {
            assertArrayEquals(text, expected, WakeOnLan.parseMac(text))
        }
        assertEquals("58:47:CA:7E:0D:48", WakeOnLan.formatMac(expected))
    }

    @Test
    fun `rejects invalid, null and broadcast MAC addresses`() {
        for (text in listOf("", "58:47:CA:7E:0D", "58:47:CA:7E:0D:4G", "58:47:CA:7E:0D:48:00", "00:00:00:00:00:00", "FF:FF:FF:FF:FF:FF")) {
            assertNull(text, WakeOnLan.parseMac(text))
        }
    }

    @Test
    fun `magic packet is 6 x FF followed by 16 x MAC`() {
        val mac = WakeOnLan.parseMac("01:02:03:04:05:06")!!
        val packet = WakeOnLan.magicPacket(mac)
        assertEquals(102, packet.size)
        assertTrue(packet.take(6).all { it == 0xFF.toByte() })
        for (i in 0 until 16) assertArrayEquals(mac, packet.copyOfRange(6 + i * 6, 12 + i * 6))
    }

    @Test
    fun `send transmits the packet several times to the configured target`() = runBlocking {
        val sent = mutableListOf<Triple<Int, String, Int>>()
        val target = WakeTarget(WakeOnLan.parseMac("01:02:03:04:05:06")!!, "192.168.1.255", 7)
        WakeOnLan.send(target, repeat = 3) { payload, address, port -> sent += Triple(payload.size, address, port) }
        assertEquals(List(3) { Triple(102, "192.168.1.255", 7) }, sent)
    }
}

class DeviceOptionsTest {
    @Test
    fun `defaults are not stored and options round-trip`() {
        assertEquals(emptyMap<String, String>(), DeviceOptions.apply(emptyMap(), DeviceKind.KODI, AdvancedOptions()))

        val options = AdvancedOptions(
            mode = ConnectionMode.HTTPS, kodiWebSocket = true, kodiWebSocketPort = "9091",
            wolMac = "58-47-ca-7e-0d-48", wolBroadcast = "192.168.30.255", wolPort = "7", wolAuto = true,
        )
        val extras = DeviceOptions.apply(mapOf("username" to "kodi"), DeviceKind.KODI, options)
        assertEquals(
            mapOf(
                "username" to "kodi", "scheme" to "https", "kodi_ws" to "true", "kodi_ws_port" to "9091",
                "wol_mac" to "58:47:CA:7E:0D:48", "wol_broadcast" to "192.168.30.255", "wol_port" to "7", "wol_auto" to "true",
            ),
            extras,
        )
        assertEquals(options.copy(wolMac = "58:47:CA:7E:0D:48"), DeviceOptions.read(KODI.copy(extras = extras)))
    }

    @Test
    fun `disabling an option removes its keys and unsupported options are ignored`() {
        val before = mapOf("scheme" to "https", "kodi_ws" to "true", "wol_mac" to "58:47:CA:7E:0D:48", "token" to "t")
        assertEquals(mapOf("token" to "t"), DeviceOptions.apply(before, DeviceKind.KODI, AdvancedOptions()))
        // HTTPS et WebSocket n'existent pas pour Roku ; Wake-on-LAN pas pour Chromecast.
        val roku = DeviceOptions.apply(emptyMap(), DeviceKind.ROKU, AdvancedOptions(mode = ConnectionMode.HTTPS, kodiWebSocket = true))
        assertEquals(emptyMap<String, String>(), roku)
        val cast = DeviceOptions.apply(emptyMap(), DeviceKind.CHROMECAST, AdvancedOptions(wolMac = "58:47:CA:7E:0D:48"))
        assertEquals(emptyMap<String, String>(), cast)
    }

    @Test
    fun `validation messages`() {
        val k = DeviceKind.KODI
        assertNull(DeviceOptions.error(k, AdvancedOptions()))
        assertEquals("Port WebSocket invalide", DeviceOptions.error(k, AdvancedOptions(kodiWebSocket = true, kodiWebSocketPort = "70000")))
        assertEquals("Adresse MAC requise pour le réveil automatique", DeviceOptions.error(k, AdvancedOptions(wolAuto = true)))
        assertEquals("Adresse MAC invalide (ex. 58:47:CA:7E:0D:48)", DeviceOptions.error(k, AdvancedOptions(wolMac = "xx")))
        assertEquals(
            "Adresse de diffusion invalide (IPv4, ex. 192.168.1.255)",
            DeviceOptions.error(k, AdvancedOptions(wolMac = "58:47:CA:7E:0D:48", wolBroadcast = "300.1.1.1")),
        )
        assertEquals("Port Wake-on-LAN invalide", DeviceOptions.error(k, AdvancedOptions(wolMac = "58:47:CA:7E:0D:48", wolPort = "0")))
    }

    @Test
    fun `URLs follow HTTPS and WebSocket options`() {
        assertEquals("http://10.0.0.2:8080", DeviceOptions.httpBaseUrl(KODI))
        assertNull(DeviceOptions.kodiWebSocketUrl(KODI))
        val secure = KODI.copy(extras = mapOf("scheme" to "https", "kodi_ws" to "true", "kodi_ws_port" to "9443"))
        assertEquals("https://10.0.0.2:8080", DeviceOptions.httpBaseUrl(secure))
        assertEquals("wss://10.0.0.2:9443/jsonrpc", DeviceOptions.kodiWebSocketUrl(secure))
        val v6 = KODI.copy(host = "fd00::2", extras = mapOf("kodi_ws" to "true"))
        assertEquals("ws://[fd00::2]:9090/jsonrpc", DeviceOptions.kodiWebSocketUrl(v6))
    }

    @Test
    fun `wake target uses defaults and requires a valid MAC`() {
        assertNull(DeviceOptions.wakeTarget(KODI))
        assertNull(DeviceOptions.wakeTarget(null))
        val device = KODI.copy(extras = mapOf("wol_mac" to "58:47:CA:7E:0D:48"))
        val target = DeviceOptions.wakeTarget(device)!!
        assertEquals("255.255.255.255", target.broadcast)
        assertEquals(9, target.port)
        assertFalse(DeviceOptions.autoWake(device))
        assertTrue(DeviceOptions.autoWake(device.copy(extras = device.extras + ("wol_auto" to "true"))))
    }
}

class EditDeviceTest {
    private val saved = KODI.copy(
        extras = mapOf("username" to "kodi", DeviceSecrets.ENCRYPTED_PASSWORD to "enc:x", "wol_mac" to "58:47:CA:7E:0D:48"),
    )

    @Test
    fun `editing with the same user and an empty password keeps the stored secret`() {
        val draft = buildDevice(saved, DeviceKind.KODI, "Salon", "10.0.0.2", "8080", "", "kodi", "", DeviceOptions.read(saved))
        assertNull(draft.password)
        assertEquals(saved.extras, draft.device.extras)
        assertEquals(saved.id, draft.device.id)
    }

    @Test
    fun `typing a new password replaces the stored secret`() {
        val draft = buildDevice(saved, DeviceKind.KODI, "Salon", "10.0.0.2", "8080", "", "kodi", "nouveau", DeviceOptions.read(saved))
        assertEquals("nouveau", draft.password)
        assertFalse(draft.device.extras.containsKey(DeviceSecrets.ENCRYPTED_PASSWORD))
        assertEquals("58:47:CA:7E:0D:48", draft.device.extras["wol_mac"])
    }

    @Test
    fun `advanced options are applied on save`() {
        val draft = buildDevice(saved, DeviceKind.KODI, "Salon", "10.0.0.2", "8080", "", "kodi", "", AdvancedOptions(mode = ConnectionMode.HTTPS))
        assertEquals("https", draft.device.extras["scheme"])
        assertFalse(draft.device.extras.containsKey("wol_mac"))
    }
}

class ConnectionTestTest {
    private class Fake(override val device: Device, val onConnect: suspend () -> Unit) : RemoteController {
        override val capabilities = setOf(Capability.NAVIGATION)
        override val supportedKeys = setOf(RemoteKey.UP)
        var disconnected = 0
        override suspend fun connect() = onConnect()
        override suspend fun send(key: RemoteKey) = Unit
        override suspend fun disconnect() {
            disconnected++
        }
    }

    @Test
    fun `success connects then disconnects`() = runTest {
        lateinit var fake: Fake
        val result = testConnection(KODI) { Fake(it) {}.also { f -> fake = f } }

        assertEquals(ConnectionTestResult.Success("Connexion réussie à Salon (Kodi)"), result)
        assertEquals(1, fake.disconnected)
    }

    @Test
    fun `failure reports the error message and still disconnects`() = runTest {
        lateinit var fake: Fake
        val result = testConnection(KODI) { Fake(it) { throw RemoteException("Kodi refuse : 401") }.also { f -> fake = f } }
        assertEquals(ConnectionTestResult.Failure("Kodi refuse : 401"), result)
        assertNull((result as ConnectionTestResult.Failure).advice)
        assertEquals(1, fake.disconnected)
    }

    @Test
    fun `timeout is reported as a failure`() = runTest {
        lateinit var fake: Fake
        val result = testConnection(KODI, timeoutMs = 2_000) { Fake(it) { awaitCancellation() }.also { f -> fake = f } }
        result as ConnectionTestResult.Failure
        assertEquals("Salon : Délai dépassé (2 s)", result.message)
        assertEquals(Problem.TIMEOUT, result.problem)
        assertEquals(1, fake.disconnected)
    }

    @Test
    fun `controller creation failure is reported`() = runTest {
        val result = testConnection(KODI) { throw IllegalArgumentException("Hôte invalide") }
        assertEquals(ConnectionTestResult.Failure("Hôte invalide"), result)
    }
}
