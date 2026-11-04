package io.github.jmorille.zapette.model

import org.junit.Assert.assertEquals
import org.junit.Test

class ModelTest {
    @Test
    fun `formatHms formats hours minutes seconds`() {
        assertEquals("00:00:00", formatHms(0))
        assertEquals("01:02:03", formatHms(3_723_000))
        assertEquals("00:00:00", formatHms(-5))
    }

    @Test
    fun `parseHms accepts fractions and rejects garbage`() {
        assertEquals(3_723_500, parseHms("1:02:03.5"))
        assertEquals(0, parseHms("NOT_IMPLEMENTED"))
        assertEquals(0, parseHms(null))
        assertEquals(0, parseHms("-1:00:00"))
        assertEquals(0, parseHms("0:00:Infinity"))
        assertEquals(0, parseHms("0:00:NaN"))
    }

    @Test
    fun `baseUrl brackets IPv6 hosts`() {
        val v4 = Device("a", "a", DeviceKind.KODI, "192.168.1.2", 8080)
        val v6 = Device("b", "b", DeviceKind.KODI, "fe80::1", 8080)
        assertEquals("http://192.168.1.2:8080", v4.baseUrl)
        assertEquals("http://[fe80::1]:8080", v6.baseUrl)
    }

    @Test
    fun `default port follows protocol`() {
        assertEquals(8060, Device("r", "Roku", DeviceKind.ROKU, "10.0.0.5").port)
    }
}
