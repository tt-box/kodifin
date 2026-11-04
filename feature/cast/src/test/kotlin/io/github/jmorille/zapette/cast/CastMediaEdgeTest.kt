package io.github.jmorille.zapette.cast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Cas limites des fonctions pures de [CastMedia] (hors table des types MIME). */
class CastMediaEdgeTest {
    @Test
    fun `seek without known duration only refuses negative positions`() {
        assertEquals(0L, CastMedia.clampSeek(-1, 0))
        assertEquals(0L, CastMedia.clampSeek(-1, -1))
        assertEquals(42_000L, CastMedia.clampSeek(42_000, -1))
        assertEquals(42_000L, CastMedia.clampSeek(42_000, 0))
        assertEquals(0L, CastMedia.clampSeek(0, 60_000))
        assertEquals(60_000L, CastMedia.clampSeek(60_000, 60_000))
        assertEquals(1L, CastMedia.clampSeek(Long.MAX_VALUE, 1))
    }

    @Test
    fun `volume steps stay within bounds`() {
        assertEquals(0.55, CastMedia.stepVolume(0.5, 0.05), 1e-9)
        assertEquals(0.45, CastMedia.stepVolume(0.5, -0.05), 1e-9)
        assertEquals(1.0, CastMedia.stepVolume(1.0, 0.05), 1e-9)
        assertEquals(0.0, CastMedia.stepVolume(0.0, -0.05), 1e-9)
        // Valeur hors bornes renvoyée par le SDK : ramenée dans [0, 1].
        assertEquals(1.0, CastMedia.stepVolume(1.4, 0.0), 1e-9)
    }

    @Test
    fun `audio detection relies on the type prefix`() {
        assertTrue(CastMedia.isAudio("audio/mpeg"))
        assertFalse(CastMedia.isAudio("video/mp4"))
        assertFalse(CastMedia.isAudio("application/x-mpegURL"))
        assertFalse(CastMedia.isAudio(""))
    }

    @Test
    fun `host comparison ignores spaces, brackets, zone and case`() {
        assertTrue(CastMedia.sameHost(" 192.168.1.20 ", "192.168.1.20"))
        assertTrue(CastMedia.sameHost("[2001:DB8::1]", "2001:db8::1%wlan0"))
        assertFalse(CastMedia.sameHost("192.168.1.20", null))
        assertFalse(CastMedia.sameHost(null, null))
        assertFalse(CastMedia.sameHost("[]", ""))
    }
}
