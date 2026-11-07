package io.github.ttbox.kodifin.ui.remote

import io.github.ttbox.kodifin.R
import io.github.ttbox.kodifin.model.MediaDetails
import io.github.ttbox.kodifin.text.UiText
import java.util.Locale
import io.github.ttbox.kodifin.model.PlaybackState
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
        val normal = UiText.Res(R.string.remote_speed_normal)
        fun factor(text: String) = UiText.Res(R.string.remote_speed_factor, text)
        assertEquals(normal, speedText(null))
        assertEquals(UiText.Res(R.string.remote_speed_paused), speedText(MediaDetails(speed = 0, playbackRate = 1.5)))
        assertEquals(factor("4"), speedText(MediaDetails(speed = 4)))
        assertEquals(normal, speedText(MediaDetails(playbackRate = 1.0)))
        assertEquals(factor("1,25"), speedText(MediaDetails(playbackRate = 1.25), Locale.FRANCE))
        assertEquals(factor("0,5"), speedText(MediaDetails(playbackRate = 0.5), Locale.FRANCE))
        assertEquals(factor("1.25"), speedText(MediaDetails(playbackRate = 1.25), Locale.US))
        assertEquals(factor("2"), speedText(MediaDetails(playbackRate = 2.0), Locale.FRANCE))
    }
}
