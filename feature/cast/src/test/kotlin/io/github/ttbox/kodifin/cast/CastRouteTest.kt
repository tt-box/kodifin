package io.github.ttbox.kodifin.cast

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CastRouteTest {
    @Test
    fun `manual devices match their route by address`() {
        assertTrue(CastMedia.sameHost("192.168.1.20", "192.168.1.20"))
        assertTrue(CastMedia.sameHost("fe80::1%wlan0", "[FE80::1]"))
        assertFalse(CastMedia.sameHost("192.168.1.20", "192.168.1.21"))
        assertFalse(CastMedia.sameHost(null, ""))
        assertFalse(CastMedia.sameHost("", ""))
    }
}
