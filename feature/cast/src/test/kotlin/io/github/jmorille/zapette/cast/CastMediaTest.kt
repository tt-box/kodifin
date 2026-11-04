package io.github.jmorille.zapette.cast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CastMediaTest {
    @Test
    fun `content type ignores query and fragment`() {
        assertEquals("application/x-mpegURL", CastMedia.contentType("https://cdn/live/index.m3u8?token=1"))
        assertEquals("audio/mpeg", CastMedia.contentType("http://nas/a.MP3#t=10"))
        assertEquals("video/mp4", CastMedia.contentType("http://nas/stream"))
        assertTrue(CastMedia.isAudio(CastMedia.contentType("x.flac")))
    }

    @Test
    fun `volume and seek are clamped`() {
        assertEquals(1.0, CastMedia.stepVolume(0.98, 0.05), 1e-9)
        assertEquals(0.0, CastMedia.stepVolume(0.02, -0.05), 1e-9)
        assertEquals(0L, CastMedia.clampSeek(-5_000, 60_000))
        assertEquals(60_000L, CastMedia.clampSeek(90_000, 60_000))
        assertEquals(90_000L, CastMedia.clampSeek(90_000, 0))
    }
}
