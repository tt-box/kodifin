package io.github.ttbox.kodifin.discovery

import io.github.ttbox.kodifin.connection.DeviceOptions
import io.github.ttbox.kodifin.model.Device
import io.github.ttbox.kodifin.model.DeviceKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Services DNS-SD ajoutés : Kodi TCP/WebSocket, Android TV Remote, débogage ADB sans fil. */
class NsdServicesTest {

    @Test
    fun `new service types are discovered`() {
        assertTrue(NsdMapping.serviceTypes.containsAll(listOf("_xbmc-jsonrpc._tcp", "_androidtvremote2._tcp", "_adb-tls-connect._tcp")))
        // Le nom convivial d'Android TV Remote doit l'emporter sur celui du débogage sans fil.
        assertTrue(NsdMapping.serviceTypes.indexOf(NsdMapping.ANDROID_TV_REMOTE) < NsdMapping.serviceTypes.indexOf(NsdMapping.ADB_TLS))
    }

    @Test
    fun `kodi tcp service carries the websocket port`() {
        val d = NsdMapping.toDevice("_xbmc-jsonrpc._tcp.", "Kodi (salon)", "192.168.1.10", 9090)!!
        assertEquals(DeviceKind.KODI, d.kind)
        assertEquals(DeviceKind.KODI.defaultPort, d.port)
        assertEquals("9090", d.extras[DeviceOptions.KODI_WEBSOCKET_PORT])
        assertEquals("kodi_ws_port", DeviceOptions.KODI_WEBSOCKET_PORT)
        assertNull(NsdMapping.toDevice(NsdMapping.KODI_TCP, "Kodi", "192.168.1.10", 0))
    }

    @Test
    fun `android tv services map to the adb device type`() {
        val remote = NsdMapping.toDevice("_androidtvremote2._tcp", "Salon\\032TV", "192.168.1.20", 6466)!!
        assertEquals(DeviceKind.FIRE_TV, remote.kind)
        assertEquals(5555, remote.port)
        assertEquals("Salon TV", remote.name)
        val adb = NsdMapping.toDevice("_adb-tls-connect._tcp", "adb-1A2B3C-xyz", "192.168.1.20", 37155)!!
        assertEquals(DeviceKind.FIRE_TV, adb.kind)
        assertEquals(remote.id, adb.id)
        assertEquals("37155", adb.extras[NsdMapping.EXTRA_ADB_TLS_PORT])
        assertEquals("Android TV (192.168.1.20)", adb.name)
    }

    @Test
    fun `kodi websocket port is merged into the http announcement of the same host`() {
        val http = NsdMapping.toDevice(NsdMapping.KODI, "Kodi", "192.168.1.10", 8081)!!
        val tcp = NsdMapping.toDevice(NsdMapping.KODI_TCP, "Kodi", "192.168.1.10", 9091)!!
        val alone = NsdMapping.toDevice(NsdMapping.KODI_TCP, "Kodi seul", "192.168.1.11", 9090)!!
        val merged = NsdMapping.consolidate(listOf(tcp, http, alone))
        assertEquals(2, merged.size)
        val first = merged.single { it.host == "192.168.1.10" }
        assertEquals(8081, first.port)
        assertEquals(mapOf(DeviceOptions.KODI_WEBSOCKET_PORT to "9091"), first.extras)
        // Sans annonce HTTP : appareil conservé, port HTTP par défaut, marqueur interne retiré.
        val orphan = merged.single { it.host == "192.168.1.11" }
        assertEquals(mapOf(DeviceOptions.KODI_WEBSOCKET_PORT to "9090"), orphan.extras)
        assertEquals("KODI:192.168.1.11:8080", orphan.id)
    }

    @Test
    fun `same id keeps the first device and unites extras`() {
        val remote = NsdMapping.toDevice(NsdMapping.ANDROID_TV_REMOTE, "Salon", "10.0.0.5", 6466)!!
        val adb = NsdMapping.toDevice(NsdMapping.ADB_TLS, "adb-x", "10.0.0.5", 40000)!!
        val other = Device("PLEX:p", "Plex", DeviceKind.PLEX, "10.0.0.6")
        val merged = NsdMapping.consolidate(listOf(remote, other, adb))
        assertEquals(listOf("Salon", "Plex"), merged.map { it.name })
        assertEquals("40000", merged.first().extras[NsdMapping.EXTRA_ADB_TLS_PORT])
    }
}
