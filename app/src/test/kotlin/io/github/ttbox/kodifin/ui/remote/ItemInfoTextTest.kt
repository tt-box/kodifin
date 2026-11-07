package io.github.ttbox.kodifin.ui.remote

import io.github.ttbox.kodifin.R
import io.github.ttbox.kodifin.model.Capability
import io.github.ttbox.kodifin.model.ControlFeature
import io.github.ttbox.kodifin.model.DeclaresAvailability
import io.github.ttbox.kodifin.model.Device
import io.github.ttbox.kodifin.model.DeviceKind
import io.github.ttbox.kodifin.model.MediaItemInfo
import io.github.ttbox.kodifin.model.RemoteController
import io.github.ttbox.kodifin.model.RemoteKey
import io.github.ttbox.kodifin.model.SupportsLibraryMaintenance
import io.github.ttbox.kodifin.model.SupportsNotify
import io.github.ttbox.kodifin.model.SupportsQueue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import io.github.ttbox.kodifin.text.UiText
import java.util.Locale

/** Libellés des fiches et fonctions masquées selon la disponibilité déclarée. */
class ItemInfoTextTest {
    @Test
    fun `facts line for an episode and a movie`() {
        val episode = MediaItemInfo("e", "I'm Home", season = 2, episode = 2, airDate = "2017-04-08", runtimeMs = 1_440_000, rating = 8.24, officialRating = "TV-14")
        assertEquals(
            listOf(
                UiText.Raw("S02E02"),
                UiText.Res(R.string.remote_info_aired, "08/04/2017"),
                UiText.Res(R.string.remote_duration_minutes, 24),
                UiText.Raw("★ 8,2"),
                UiText.Raw("TV-14"),
            ),
            infoFacts(episode, Locale.FRANCE),
        )
        assertEquals(
            listOf(UiText.Res(R.string.remote_info_released, "25/05/1979"), UiText.Res(R.string.remote_duration_hours, 1, 57)),
            infoFacts(MediaItemInfo("m", "Alien", airDate = "1979-05-25", runtimeMs = 7_020_000), Locale.FRANCE),
        )
        assertEquals(listOf(UiText.Raw("E03"), UiText.Raw("2008")), infoFacts(MediaItemInfo("x", "x", episode = 3, year = 2008)))
        assertTrue(infoFacts(MediaItemInfo("x", "x")).isEmpty())
        assertEquals("2017", localDate("2017"))
        assertEquals("08/04/2017", localDate("2017-04-08", Locale.FRANCE))
    }

    @Test
    fun `fullscreen label shows the known state`() {
        assertEquals(UiText.Res(R.string.remote_fullscreen_on), fullscreenLabel(true))
        assertEquals(UiText.Res(R.string.remote_fullscreen_off), fullscreenLabel(false))
        assertEquals(UiText.Res(R.string.remote_fullscreen_unknown), fullscreenLabel(null))
    }

    @Test
    fun `cast summary is shortened`() {
        assertNull(castSummary(emptyList()))
        assertEquals(UiText.Raw("A, B"), castSummary(listOf("A", "B")))
        assertEquals(
            UiText.Plural(R.plurals.remote_cast_more, 2, listOf("A, B", 2)),
            castSummary(listOf("A", "B", "C", "D"), max = 2),
        )
    }

    private class Limited(private val unavailable: Set<ControlFeature>) :
        RemoteController, SupportsNotify, SupportsLibraryMaintenance, SupportsQueue, DeclaresAvailability {
        override val device = Device("j", "Thor", DeviceKind.JELLYFIN, "h")
        override val capabilities = setOf(Capability.PLAYBACK)
        override val supportedKeys = emptySet<RemoteKey>()
        override suspend fun send(key: RemoteKey) {}
        override suspend fun showMessage(title: String, text: String) {}
        override suspend fun scanLibrary() {}
        override suspend fun cleanLibrary() {}
        override suspend fun queue() = emptyList<io.github.ttbox.kodifin.model.MediaEntry>()
        override suspend fun queuePosition(): Int? = null
        override suspend fun playQueueItem(index: Int) {}
        override suspend fun removeQueueItem(index: Int) {}
        override suspend fun clearQueue() {}
        override fun isAvailable(feature: ControlFeature) = feature !in unavailable
    }

    @Test
    fun `features hide what the controller declares impossible`() {
        val all = RemoteFeatures.of(Limited(emptySet()))
        assertTrue(all.notify && all.libraryMaintenance && all.cleanLibrary && all.queueEdit && all.queue)
        val jellyLike = RemoteFeatures.of(Limited(setOf(ControlFeature.NOTIFY, ControlFeature.CLEAN_LIBRARY, ControlFeature.QUEUE_EDIT)))
        assertFalse(jellyLike.notify)
        assertFalse(jellyLike.cleanLibrary)
        assertFalse(jellyLike.queueEdit)
        assertTrue(jellyLike.queue)
        assertTrue(jellyLike.libraryMaintenance)
        assertFalse(jellyLike.itemInfo)
    }
}
