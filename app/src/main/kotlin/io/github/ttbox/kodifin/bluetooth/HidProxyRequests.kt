package io.github.ttbox.kodifin.bluetooth

/**
 * Suivi des demandes de proxy du profil HID (`getProfileProxy`), logique pure testée en JVM.
 *
 * Chaque demande reçoit un numéro de génération ; seul le proxy de la DERNIÈRE demande encore
 * active est accepté. Un `stop()` suivi d'un `start()` rapide laisse deux demandes en attente :
 * le proxy de la première, arrivé après coup, est refusé (l'appelant le rend aussitôt) au lieu
 * d'écraser le second et d'enregistrer le clavier deux fois.
 */
internal class HidProxyRequests {
    private var generation = 0
    private var active = false

    /** Nouvelle demande ; `null` si une demande est déjà active (rien à faire). */
    @Synchronized
    fun begin(): Int? {
        if (active) return null
        active = true
        return ++generation
    }

    /** La demande [id] n'a pas pu être lancée : elle n'est plus active. */
    @Synchronized
    fun failed(id: Int) {
        if (id == generation) active = false
    }

    /** Arrêt : toute demande en attente sera refusée à son arrivée. */
    @Synchronized
    fun cancel() {
        active = false
    }

    /** Le proxy de la demande [id] est-il à garder ? Sinon il doit être rendu. */
    @Synchronized
    fun accept(id: Int): Boolean = active && id == generation

    /** [id] est-elle la demande courante (événements d'un ancien proxy ignorés) ? */
    @Synchronized
    fun isCurrent(id: Int): Boolean = id == generation
}
