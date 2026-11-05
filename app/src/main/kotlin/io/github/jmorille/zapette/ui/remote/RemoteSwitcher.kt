package io.github.jmorille.zapette.ui.remote

import io.github.jmorille.zapette.model.Device

/** Ligne du sélecteur de télécommandes : appareil enregistré, détail affiché, appareil actuel. */
data class SwitchEntry(val device: Device, val detail: String, val current: Boolean)

/** Passage direct d'une télécommande enregistrée à une autre (logique pure, testée sur JVM). */
object RemoteSwitcher {
    /**
     * Appareils enregistrés dans l'ordre de l'accueil, l'actuel marqué. Les doublons d'identifiant
     * sont ignorés ; liste vide s'il n'y a pas d'autre appareil vers lequel basculer.
     */
    fun entries(saved: List<Device>, currentId: String): List<SwitchEntry> {
        val unique = saved.distinctBy { it.id }
        if (unique.none { it.id != currentId }) return emptyList()
        return unique.map { device ->
            val detail = listOf(device.kind.label, device.host).filter { it.isNotBlank() }.joinToString(" · ")
            SwitchEntry(device, detail, current = device.id == currentId)
        }
    }
}
