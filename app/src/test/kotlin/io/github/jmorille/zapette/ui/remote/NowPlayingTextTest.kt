package io.github.jmorille.zapette.ui.remote

import io.github.jmorille.zapette.model.MediaDetails
import io.github.jmorille.zapette.model.PlaybackState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Libellés du média en cours : sous-titre musique / application, vitesse fractionnaire. */
class NowPlayingTextTest {
    @Test
    fun `music subtitle is artist then album, else the sender app`() {
        assertEquals(
            NowPlayingTitles("Titre", "Groupe · Album"),
            nowPlayingTitles(PlaybackState(title = "Titre", artist = "Groupe", album = "Album", appName = "Spotify")),
        )
        assertEquals("YouTube", nowPlayingTitles(PlaybackState(title = "Vidéo", appName = "YouTube")).subtitle)
        // Application dont le nom est le titre lui-même : pas de répétition.
        assertNull(nowPlayingTitles(PlaybackState(title = "YouTube", appName = "YouTube")).subtitle)
        assertNull(nowPlayingTitles(PlaybackState(title = "Film")).subtitle)
    }

    @Test
    fun `speed text covers integer and fractional speeds`() {
        assertEquals("Normale", speedText(null))
        assertEquals("En pause", speedText(MediaDetails(speed = 0, playbackRate = 1.5)))
        assertEquals("×4", speedText(MediaDetails(speed = 4)))
        assertEquals("Normale", speedText(MediaDetails(playbackRate = 1.0)))
        assertEquals("×1,25", speedText(MediaDetails(playbackRate = 1.25)))
        assertEquals("×0,5", speedText(MediaDetails(playbackRate = 0.5)))
        assertEquals("×2", speedText(MediaDetails(playbackRate = 2.0)))
    }
}
