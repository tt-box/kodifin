package io.github.ttbox.kodifin.mediaserver

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Pochette et fond du média en cours (clés d'image Jellyfin/Emby). */
class NowPlayingImagesTest {
    @Test
    fun `episode uses the series poster and backdrop`() {
        val episode = NowPlayingDto(id = "ep", type = "Episode", seriesId = "show")
        assertEquals("show/Primary", episode.artworkKey)
        assertEquals("show/Backdrop", episode.backdropKey)
        val withParent = episode.copy(parentBackdropItemId = "parent")
        assertEquals("parent/Backdrop", withParent.backdropKey)
    }

    @Test
    fun `movie uses its own images, tagged backdrop first`() {
        val movie = NowPlayingDto(id = "m1", type = "Movie", backdropImageTags = listOf("tag1", "tag2"))
        assertEquals("m1/Primary", movie.artworkKey)
        assertEquals("m1/Backdrop/tag1", movie.backdropKey)
        assertNull(NowPlayingDto(id = "m2", type = "Movie").backdropKey)
    }

    @Test
    fun `song without its own image falls back to the album cover`() {
        val song = NowPlayingDto(id = "s", type = "Audio", albumId = "alb")
        assertEquals("alb/Primary", song.artworkKey)
        assertEquals("s/Primary", song.copy(imageTags = mapOf("Primary" to "t")).artworkKey)
        assertNull(NowPlayingDto(id = null, type = "Movie").artworkKey)
    }
}
