package io.github.jmorille.zapette

import io.github.jmorille.zapette.controller.ControllerFactory
import io.github.jmorille.zapette.discovery.NsdMapping
import io.github.jmorille.zapette.model.Device
import io.github.jmorille.zapette.model.DeviceKind
import io.github.jmorille.zapette.ui.config.ConfigValidation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NsdMappingTest {
    @Test
    fun `kodi service keeps announced http port`() {
        val d = NsdMapping.toDevice("._xbmc-jsonrpc-h._tcp.", "Kodi\\032Salon", "192.168.1.10", 8080)!!
        assertEquals(DeviceKind.KODI, d.kind)
        assertEquals("Kodi Salon", d.name)
        assertEquals(8080, d.port)
    }

    @Test
    fun `fire tv is driven through adb port`() {
        val d = NsdMapping.toDevice("_amzn-wplay._tcp", "Fire TV", "192.168.1.11", 41000)!!
        assertEquals(5555, d.port)
    }

    @Test
    fun `unknown services and empty hosts are ignored`() {
        assertNull(NsdMapping.toDevice("_printer._tcp", "x", "1.2.3.4", 1))
        assertNull(NsdMapping.toDevice(NsdMapping.AIRPLAY, "x", "", 7000))
    }
}

class ConfigValidationTest {
    @Test
    fun `validates host port and upnp location`() {
        assertNull(ConfigValidation.error(DeviceKind.KODI, "192.168.1.2", "8080", ""))
        assertNotNull(ConfigValidation.error(DeviceKind.KODI, "bad host!", "8080", ""))
        assertNotNull(ConfigValidation.error(DeviceKind.ROKU, "tv.local", "70000", ""))
        assertNotNull(ConfigValidation.error(DeviceKind.UPNP_RENDERER, "", "", "ftp://x"))
        assertNull(ConfigValidation.error(DeviceKind.UPNP_RENDERER, "", "", "http://10.0.0.3:1400/desc.xml"))
    }
}

class ControllerFactoryTest {
    @Test
    fun `media servers need credentials or token before use`() {
        val bare = Device("j", "Jelly", DeviceKind.JELLYFIN, "10.0.0.2", 8096)
        assertTrue(ControllerFactory.needsSetup(bare))
        assertFalse(ControllerFactory.needsSetup(bare.copy(extras = mapOf("token" to "t"))))
        assertFalse(ControllerFactory.needsSetup(bare.copy(kind = DeviceKind.ROKU)))
    }
}
