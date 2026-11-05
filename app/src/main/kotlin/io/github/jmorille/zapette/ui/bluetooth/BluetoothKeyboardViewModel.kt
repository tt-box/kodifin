package io.github.jmorille.zapette.ui.bluetooth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.jmorille.zapette.bluetooth.HidAvailability
import io.github.jmorille.zapette.bluetooth.HidHost
import io.github.jmorille.zapette.bluetooth.HidKey
import io.github.jmorille.zapette.bluetooth.HidKeyboardPort
import io.github.jmorille.zapette.bluetooth.HidLinkState
import io.github.jmorille.zapette.bluetooth.HidReports
import io.github.jmorille.zapette.bluetooth.KeyboardLayout
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
    val feedback: String? = null,
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

    fun press(label: String, key: HidKey) {
        viewModelScope.launch {
            val ok = sending.withLock { tap(key) }
            local.update { it.copy(feedback = if (ok) "Envoyé : $label" else NOT_SENT) }
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
                typed.skipped.isNotEmpty() -> "Texte envoyé ; ignorés : ${typed.skipped.joinToString(" ")}"
                else -> "Texte envoyé ($sent touche${if (sent > 1) "s" else ""})"
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
        const val NOT_SENT = "Non envoyé : aucune TV connectée"
    }
}
