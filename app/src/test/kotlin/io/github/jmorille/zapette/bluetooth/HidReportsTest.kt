package io.github.jmorille.zapette.bluetooth

import io.github.jmorille.zapette.model.RemoteKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Rapports HID : clavier (rapport 1) et multimédia (rapport 2), saisie QWERTY et AZERTY. */
class HidReportsTest {
    private fun hex(r: HidReport) = "${r.id}:" + r.data.joinToString(" ") { "%02x".format(it) }

    @Test
    fun `keyboard and consumer reports`() {
        assertEquals("1:00 00 52 00 00 00 00 00", hex(HidReports.press(HidReports.keyFor(RemoteKey.UP))))
        assertEquals("1:00 00 00 00 00 00 00 00", hex(HidReports.release(HidReports.ENTER)))
        assertEquals("1:42 00 1f 00 00 00 00 00", hex(HidReports.press(HidKey.Keyboard(0x1F, shift = true, altGr = true))))
        // 0x223 en petit-boutiste.
        assertEquals("2:23 02", hex(HidReports.press(HidReports.keyFor(RemoteKey.HOME))))
        assertEquals("2:e9 00", hex(HidReports.press(HidReports.keyFor(RemoteKey.VOLUME_UP))))
        assertEquals("2:00 00", hex(HidReports.release(HidReports.AC_BACK)))
        assertEquals(HidReports.press(HidReports.ENTER), HidReports.press(HidReports.keyFor(RemoteKey.SELECT)))
    }

    @Test
    fun `every remote key has a hid key`() {
        val keys = RemoteKey.entries.map(HidReports::keyFor)
        assertEquals(RemoteKey.entries.size, keys.toSet().size)
    }

    @Test
    fun `descriptor declares both report ids and closes its collections`() {
        val d = HidReports.DESCRIPTOR.map { it.toInt() and 0xFF }
        val ids = d.indices.filter { d[it] == 0x85 }.map { d[it + 1] }
        assertEquals(listOf(1, 2), ids)
        assertEquals(2, d.count { it == 0xA1 })
        assertEquals(0xC0, d.last())
    }

    @Test
    fun `qwerty text`() {
        val typed = HidReports.type("Ab1!", KeyboardLayout.QWERTY_US)
        assertEquals(
            listOf(HidKey.Keyboard(0x04, shift = true), HidKey.Keyboard(0x05), HidKey.Keyboard(0x1E), HidKey.Keyboard(0x1E, shift = true)),
            typed.keys,
        )
        assertTrue(typed.skipped.isEmpty())
        assertEquals(setOf('é'), HidReports.type("café", KeyboardLayout.QWERTY_US).skipped)
    }

    @Test
    fun `azerty text with accents, digits and altgr`() {
        val typed = HidReports.type("aqzwm é1@", KeyboardLayout.AZERTY_FR)
        assertEquals(
            listOf(
                HidKey.Keyboard(0x14), HidKey.Keyboard(0x04), HidKey.Keyboard(0x1A), HidKey.Keyboard(0x1D),
                HidKey.Keyboard(0x33), HidKey.Keyboard(0x2C), HidKey.Keyboard(0x1F),
                HidKey.Keyboard(0x1E, shift = true), HidKey.Keyboard(0x27, altGr = true),
            ),
            typed.keys,
        )
        assertEquals(HidKey.Keyboard(0x10, shift = true), HidReports.type("?", KeyboardLayout.AZERTY_FR).keys.single())
        assertEquals(setOf('ñ'), HidReports.type("ñ", KeyboardLayout.AZERTY_FR).skipped)
    }
}
