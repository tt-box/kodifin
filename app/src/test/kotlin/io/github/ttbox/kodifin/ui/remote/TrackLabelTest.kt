package io.github.ttbox.kodifin.ui.remote

import io.github.ttbox.kodifin.R
import io.github.ttbox.kodifin.model.MediaTrack
import io.github.ttbox.kodifin.text.UiText
import org.junit.Assert.assertEquals
import org.junit.Test

/** Replis localisés quand l'appareil ne nomme ni la piste ni l'élément de la file. */
class TrackLabelTest {
    @Test
    fun `track label combines name and language, else falls back to Piste N`() {
        assertEquals(UiText.Raw("Français · fr"), MediaTrack(1, "Français", "fr").displayText())
        assertEquals(UiText.Raw("fr"), MediaTrack(2, "", "fr").displayText())
        assertEquals(UiText.Raw("VO"), MediaTrack(3, "VO", "VO").displayText())
        assertEquals(UiText.Res(R.string.remote_track_number, 4), MediaTrack(4, " ", null).displayText())
    }

    @Test
    fun `queue item without title is numbered`() {
        assertEquals(UiText.Raw("Pilote"), queueItemTitle("Pilote", 0))
        assertEquals(UiText.Res(R.string.remote_queue_item_number, 3), queueItemTitle("", 2))
        // Titre vide d'un élément Cast : numéroté d'après sa position, pas d'après son identifiant.
        assertEquals(UiText.Res(R.string.remote_queue_item_number, 2), queueItemTitle("  ", 1))
    }
}
