package io.github.jmorille.zapette.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.jmorille.zapette.cast.CastMaxBitrate
import io.github.jmorille.zapette.cast.SubtitleBackground
import io.github.jmorille.zapette.cast.SubtitleColor
import io.github.jmorille.zapette.cast.SubtitleSize
import io.github.jmorille.zapette.data.AppSettings
import io.github.jmorille.zapette.data.SettingsStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.IOException

/** Écran des réglages globaux : lecture en continu et écritures atomiques. */
class SettingsViewModel(private val store: SettingsStore) : ViewModel() {

    /** `null` tant que les réglages n'ont pas été lus (évite d'afficher des valeurs par défaut fugaces). */
    val settings: StateFlow<AppSettings?> = store.settings.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val _error = MutableStateFlow<String?>(null)

    /** Dernière erreur d'enregistrement, effacée à la prochaine écriture réussie. */
    val error: StateFlow<String?> = _error.asStateFlow()

    fun setKeepScreenOn(enabled: Boolean) = update { it.copy(keepScreenOn = enabled) }

    fun setMaxBitrate(value: CastMaxBitrate) = update { it.copy(cast = it.cast.copy(maxBitrate = value)) }

    fun setSubtitleSize(value: SubtitleSize) =
        update { it.copy(cast = it.cast.copy(subtitles = it.cast.subtitles.copy(size = value))) }

    fun setSubtitleColor(value: SubtitleColor) =
        update { it.copy(cast = it.cast.copy(subtitles = it.cast.subtitles.copy(textColor = value))) }

    fun setSubtitleBackground(value: SubtitleBackground) =
        update { it.copy(cast = it.cast.copy(subtitles = it.cast.subtitles.copy(background = value))) }

    fun setCastAutoDisconnect(enabled: Boolean) = update { it.copy(cast = it.cast.copy(autoDisconnect = enabled)) }

    private fun update(transform: (AppSettings) -> AppSettings) {
        viewModelScope.launch {
            try {
                store.update(transform)
                _error.value = null
            } catch (_: IOException) {
                _error.value = "Réglage non enregistré (stockage indisponible)"
            }
        }
    }
}
