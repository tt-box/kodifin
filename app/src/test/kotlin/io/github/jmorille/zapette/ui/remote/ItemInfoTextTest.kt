package io.github.jmorille.zapette.ui.remote

import io.github.jmorille.zapette.model.Capability
import io.github.jmorille.zapette.model.ControlFeature
import io.github.jmorille.zapette.model.DeclaresAvailability
import io.github.jmorille.zapette.model.Device
import io.github.jmorille.zapette.model.DeviceKind
import io.github.jmorille.zapette.model.MediaItemInfo
import io.github.jmorille.zapette.model.RemoteController
import io.github.jmorille.zapette.model.RemoteKey
import io.github.jmorille.zapette.model.SupportsLibraryMaintenance
import io.github.jmorille.zapette.model.SupportsNotify
import io.github.jmorille.zapette.model.SupportsQueue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Libellés des fiches et fonctions masquées selon la disponibilité déclarée. */
class ItemInfoTextTest {
    @Test
    fun `facts line for an episode and a movie`() {
        val episode = MediaItemInfo("e", "I'm Home", season = 2, episode = 2, airDate = "2017-04-08", runtimeMs = 1_440_000, rating = 8.24, officialRating = "TV-14")
        assertEquals("S02E02 · Diffusé le 08/04/2017 · 24 min · ★ 8,2 · TV-14", infoFacts(episode))
        assertEquals("Sorti le 25/05/1979 · 1 h 57", infoFacts(MediaItemInfo("m", "Alien", airDate = "1979-05-25", runtimeMs = 7_020_000)))
        assertEquals("E03 · 2008", infoFacts(MediaItemInfo("x", "x", episode = 3, year = 2008)))
        assertNull(infoFacts(MediaItemInfo("x", "x")))
        assertEquals("2017", frenchDate("2017"))
    }

    @Test
    fun `fullscreen label shows the known state`() {
        assertEquals("Plein écran (activé) :", fullscreenLabel(true))
        assertEquals("Plein écran (désactivé) :", fullscreenLabel(false))
        assertEquals("Plein écran :", fullscreenLabel(null))
    }

    @Test
    fun `cast summary is shortened`() {
        assertNull(castSummary(emptyList()))
        assertEquals("A, B", castSummary(listOf("A", "B")))
        assertEquals("A, B et 2 autres", castSummary(listOf("A", "B", "C", "D"), max = 2))
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
        override suspend fun queue() = emptyList<io.github.jmorille.zapette.model.MediaEntry>()
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
