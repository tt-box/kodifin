package io.github.jmorille.zapette.ui.remote

import io.github.jmorille.zapette.model.Device
import io.github.jmorille.zapette.model.DeviceKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Sélecteur des télécommandes enregistrées dans la barre du haut. */
class RemoteSwitcherTest {
    private val thor = Device("j", "thor", DeviceKind.JELLYFIN, "192.168.30.30")
    private val ko = Device("k", "ko", DeviceKind.KODI, "bragi.home.loc")
    private val salon = Device("c", "Salon", DeviceKind.CHROMECAST, "192.168.30.54")

    @Test
    fun `saved devices keep their order and mark the current one`() {
        val entries = RemoteSwitcher.entries(listOf(thor, ko, salon, ko), "k")
        assertEquals(listOf("thor", "ko", "Salon"), entries.map { it.device.name })
        assertEquals(listOf(false, true, false), entries.map { it.current })
        assertEquals("Jellyfin · 192.168.30.30", entries[0].detail)
        // Chromecast sans adresse enregistrée : pas de séparateur orphelin.
        assertEquals("Chromecast", RemoteSwitcher.entries(listOf(thor, salon.copy(host = "")), "j")[1].detail)
    }

    @Test
    fun `no menu without another device`() {
        assertTrue(RemoteSwitcher.entries(emptyList(), "k").isEmpty())
        assertTrue(RemoteSwitcher.entries(listOf(ko), "k").isEmpty())
        // Appareil ouvert non enregistré (détecté sur le réseau) : les enregistrés restent proposés.
        assertEquals(1, RemoteSwitcher.entries(listOf(thor), "x").size)
    }
}
