package io.github.ttbox.kodifin.discovery

import io.github.ttbox.kodifin.model.Device

/**
 * État d'une découverte DNS-SD par nom de service (logique pure, sans API Android) :
 * annoncé (`found`), résolu (`resolved`) ou disparu (`lost`). Les rappels NSD arrivent sur
 * des threads du système : chaque transition est atomique.
 *
 * Une résolution tardive ne ressuscite pas un service disparu, et un observateur (suivi
 * Android 14+) rattaché à un service déjà perdu ou après [closeAll] est fermé immédiatement.
 */
internal class NsdTracker {
    private val present = HashSet<String>()
    private val devices = LinkedHashMap<String, Device>()
    private val watchers = HashMap<String, AutoCloseable>()
    private var closed = false

    /** Appareils résolus, dans l'ordre d'apparition. */
    @get:Synchronized
    val snapshot: List<Device> get() = devices.values.toList()

    /** Enregistre l'annonce ; `true` si le service est nouveau (il faut alors le résoudre). */
    @Synchronized
    fun found(name: String): Boolean = !closed && present.add(name)

    /**
     * Résultat d'une résolution : [device] `null` (aucun hôte exploitable) retire le service.
     * Renvoie la nouvelle liste à publier, ou `null` si le service n'est plus annoncé.
     */
    @Synchronized
    fun resolved(name: String, device: Device?): List<Device>? {
        if (name !in present) return null
        if (device != null) devices[name] = device else devices.remove(name)
        return snapshot
    }

    /** Disparition : renvoie la liste à publier, ou `null` si le service était inconnu. */
    fun lost(name: String): List<Device>? {
        val watcher: AutoCloseable?
        val result = synchronized(this) {
            val known = present.remove(name)
            val hadDevice = devices.remove(name) != null
            watcher = watchers.remove(name)
            if (known || hadDevice) snapshot else null
        }
        watcher?.closeQuietly()
        return result
    }

    /** Rattache l'observateur d'un service ; `false` (et observateur fermé) s'il n'est plus annoncé. */
    fun attach(name: String, watcher: AutoCloseable): Boolean {
        // Fermeture hors verrou : un observateur peut rappeler le traqueur en se fermant.
        val (accepted, toClose) = synchronized(this) {
            if (closed || name !in present) false to watcher else true to watchers.put(name, watcher)
        }
        toClose?.closeQuietly()
        return accepted
    }

    /** Fin de la découverte : ferme tous les observateurs, ignore les rappels ultérieurs. */
    fun closeAll() {
        val toClose = synchronized(this) {
            closed = true
            present.clear()
            watchers.values.toList().also { watchers.clear() }
        }
        toClose.forEach { it.closeQuietly() }
    }

    private fun AutoCloseable.closeQuietly() {
        runCatching { close() }
    }
}
