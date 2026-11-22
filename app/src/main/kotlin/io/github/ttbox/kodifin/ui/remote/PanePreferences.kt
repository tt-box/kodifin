package io.github.ttbox.kodifin.ui.remote

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import io.github.ttbox.kodifin.data.AppSettings
import io.github.ttbox.kodifin.data.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.IOException

/**
 * Choix de disposition de la télécommande sur tablette, communs à tous les appareils (passer
 * de l'un à l'autre les garde) et conservés d'un lancement à l'autre via [bind].
 */
internal object PanePreferences {
    private var hiddenState by mutableStateOf(false)
    private var detailsInPaneState by mutableStateOf(false)

    /** Choix modifiés avant la fin de la lecture du disque : la valeur enregistrée ne les écrase pas. */
    @Volatile private var hiddenChanged = false
    @Volatile private var detailsInPaneChanged = false

    /** Volet de droite masqué (la télécommande reprend la place). */
    var hidden: Boolean
        get() = hiddenState
        set(value) {
            hiddenChanged = true
            hiddenState = value
        }

    /** Paysage : détails de lecture déplacés de la colonne de gauche vers le volet de droite. */
    var detailsInPane: Boolean
        get() = detailsInPaneState
        set(value) {
            detailsInPaneChanged = true
            detailsInPaneState = value
        }

    /**
     * Charge les choix enregistrés dans [store], puis y enregistre chaque changement. Lancé au
     * démarrage de l'application : la lecture est faite avant l'ouverture de la télécommande.
     * Une erreur d'entrée/sortie n'interrompt pas l'application : le choix reste en mémoire.
     */
    fun bind(store: SettingsStore, scope: CoroutineScope): Job = scope.launch {
        val saved = try {
            store.settings.first()
        } catch (_: IOException) {
            AppSettings()
        }
        if (!hiddenChanged) hiddenState = saved.paneHidden
        if (!detailsInPaneChanged) detailsInPaneState = saved.detailsInPane
        snapshotFlow { hiddenState to detailsInPaneState }.collect { (paneHidden, inPane) ->
            try {
                store.update { s ->
                    if (s.paneHidden == paneHidden && s.detailsInPane == inPane) s
                    else s.copy(paneHidden = paneHidden, detailsInPane = inPane)
                }
            } catch (_: IOException) {
                // Écriture impossible (stockage plein…) : le choix vaut pour cette session.
            }
        }
    }

    /** Remet l'état initial (tests). */
    internal fun reset() {
        hiddenState = false
        detailsInPaneState = false
        hiddenChanged = false
        detailsInPaneChanged = false
    }
}
