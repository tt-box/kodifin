package io.github.ttbox.kodifin.ui.bluetooth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.ttbox.kodifin.R
import io.github.ttbox.kodifin.bluetooth.HidAvailability
import io.github.ttbox.kodifin.bluetooth.HidHost
import io.github.ttbox.kodifin.bluetooth.HidKey
import io.github.ttbox.kodifin.bluetooth.HidKeyboardPort
import io.github.ttbox.kodifin.bluetooth.HidLinkState
import io.github.ttbox.kodifin.bluetooth.HidReports
import io.github.ttbox.kodifin.bluetooth.KeyboardLayout
import io.github.ttbox.kodifin.text.UiText
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class BluetoothKeyboardUiState(
    val availability: HidAvailability = HidAvailability.UNSUPPORTED,
    val link: HidLinkState = HidLinkState(),
    /** Appareils déjà appairés avec la tablette (la TV y figure après le premier appairage). */
    val hosts: List<HidHost> = emptyList(),
    val layout: KeyboardLayout = KeyboardLayout.AZERTY_FR,
    /** Retour de la dernière touche : « Envoyé : Haut » ou l'échec. */
    val feedback: UiText? = null,
) {
    val canSend: Boolean get() = link.connected != null
}

/** Écran de test du clavier Bluetooth : déclaration, connexion à la TV, envoi de touches et de texte. */
class BluetoothKeyboardViewModel(
    private val port: HidKeyboardPort,
    /** Durée d'appui simulée entre l'appui et le relâchement. */
    private val holdMs: Long = KEY_HOLD_MS,
) : ViewModel() {
    private val local = MutableStateFlow(BluetoothKeyboardUiState())

    val state: StateFlow<BluetoothKeyboardUiState> = combine(local, port.link) { s, link -> s.copy(link = link) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, BluetoothKeyboardUiState())

    /** Une séquence de touches à la fois : deux saisies ne s'entremêlent pas. */
    private val sending = Mutex()

    /** Relit la disponibilité (permission, Bluetooth activé) et déclare le clavier si possible. */
    fun refresh() {
        val availability = port.availability()
        val ready = availability == HidAvailability.READY
        local.update { it.copy(availability = availability, hosts = if (ready) port.bondedHosts() else emptyList()) }
        if (ready) port.start()
    }

    fun connect(host: HidHost) = port.connect(host.address)

    fun disconnect() = port.disconnect()

    fun setLayout(layout: KeyboardLayout) = local.update { it.copy(layout = layout) }

    fun press(label: UiText, key: HidKey) {
        viewModelScope.launch {
            val ok = sending.withLock { tap(key) }
            local.update { it.copy(feedback = if (ok) UiText.Res(R.string.bt_keyboard_feedback_sent, label) else NOT_SENT) }
        }
    }

    fun type(text: String) {
        if (text.isEmpty()) return
        val typed = HidReports.type(text, local.value.layout)
        viewModelScope.launch {
            var sent = 0
            sending.withLock {
                for (key in typed.keys) {
                    if (!tap(key)) break
                    sent++
                }
            }
            val feedback = when {
                sent < typed.keys.size -> NOT_SENT
                typed.skipped.isNotEmpty() ->
                    UiText.Res(R.string.bt_keyboard_feedback_text_skipped, typed.skipped.joinToString(" "))
                else -> UiText.Plural(R.plurals.bt_keyboard_feedback_text_sent, sent)
            }
            local.update { it.copy(feedback = feedback) }
        }
    }

    private suspend fun tap(key: HidKey): Boolean {
        if (!port.send(HidReports.press(key))) return false
        delay(holdMs)
        port.send(HidReports.release(key))
        delay(holdMs)
        return true
    }

    override fun onCleared() = port.stop()

    companion object {
        const val KEY_HOLD_MS = 40L
        val NOT_SENT: UiText = UiText.Res(R.string.bt_keyboard_feedback_not_sent)
    }
}
