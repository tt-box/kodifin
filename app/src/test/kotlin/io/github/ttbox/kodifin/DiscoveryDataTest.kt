package io.github.ttbox.kodifin

import io.github.ttbox.kodifin.data.DeviceCodec
import io.github.ttbox.kodifin.discovery.NsdMapping
import io.github.ttbox.kodifin.model.Device
import io.github.ttbox.kodifin.model.DeviceKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

class NsdAddressTest {
    private fun ip(literal: String) = InetAddress.getByName(literal)

    @Test
    fun `ipv4 is preferred and link local ipv6 is dropped`() {
        assertEquals("192.168.1.10", NsdMapping.preferredHost(listOf(ip("fe80::1"), ip("192.168.1.10"))))
        assertEquals("2001:db8:0:0:0:0:0:1", NsdMapping.preferredHost(listOf(ip("fe80::1"), ip("2001:db8::1"))))
        assertNull(NsdMapping.preferredHost(listOf(ip("fe80::1"))))
        assertNull(NsdMapping.preferredHost(emptyList()))
    }

    @Test
    fun `dns-sd escapes are decoded as utf-8 bytes`() {
        assertEquals("Télé Salon", NsdMapping.unescape("""T\195\169l\195\169\032Salon"""))
        assertEquals("""a.b\c""", NsdMapping.unescape("""a\.b\\c"""))
        assertEquals("Fire TV", NsdMapping.unescape("Fire TV"))
    }
}

class DeviceCodecTest {
    private val kodi = Device("k", "Kodi", DeviceKind.KODI, "10.0.0.2")

    @Test
    fun `one unreadable entry does not wipe the saved list`() {
        val raw = """[{"id":"x","name":"Futur","kind":"SMART_FRIDGE","host":"10.0.0.9"},""" +
            """{"id":"r","name":"Roku","kind":"ROKU","host":"10.0.0.3","port":8060}]"""
        assertEquals(listOf("r"), DeviceCodec.decode(raw).map { it.id })

        val updated = DeviceCodec.update(raw) { it + kodi }
        assertEquals(listOf("r", "k"), DeviceCodec.decode(updated).map { it.id })
        assertTrue("l'entrée illisible est conservée", updated.contains("SMART_FRIDGE"))
    }

    @Test
    fun `corrupt or missing json yields an empty list`() {
        assertTrue(DeviceCodec.decode("{tronqué").isEmpty())
        assertTrue(DeviceCodec.decode(null).isEmpty())
        assertEquals(listOf("k"), DeviceCodec.decode(DeviceCodec.update(null) { listOf(kodi) }).map { it.id })
    }
}
