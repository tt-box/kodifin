package io.github.jmorille.zapette.ui.remote

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlin.math.abs

/**
 * État d'un curseur dont la valeur de référence vient de l'appareil (position, volume).
 *
 * - Pendant le glissement, les valeurs reçues de l'appareil sont ignorées (sinon le curseur
 *   « saute » sous le doigt à chaque interrogation).
 * - Après le relâchement, la valeur choisie reste affichée jusqu'à ce que l'appareil la
 *   confirme (à [tolerance] près) ou jusqu'à l'expiration ([expirePending]) : sans cela,
 *   le curseur revient brièvement à l'ancienne valeur.
 */
@Stable
class RemoteSliderState(private val tolerance: Float) {
    var dragValue: Float? by mutableStateOf(null)
        private set
    var pending: Float? by mutableStateOf(null)
        private set

    fun displayed(remote: Float): Float = dragValue ?: pending ?: remote

    fun onDrag(value: Float) {
        dragValue = value
    }

    /**
     * Fin du geste : renvoie la valeur à envoyer, ou `null` si aucun glissement n'a eu lieu
     * (évite d'envoyer une valeur par défaut, par exemple une recherche à 0).
     */
    fun onDragFinished(): Float? {
        val value = dragValue ?: return null
        dragValue = null
        pending = value
        return value
    }

    fun onRemoteValue(remote: Float) {
        val p = pending ?: return
        if (abs(remote - p) <= tolerance) pending = null
    }

    fun expirePending() {
        pending = null
    }
}
