package io.github.jmorille.zapette.ui.remote

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.jmorille.zapette.AppContainer
import io.github.jmorille.zapette.model.Capability
import io.github.jmorille.zapette.model.Device
import io.github.jmorille.zapette.model.PersistsCredentials
import io.github.jmorille.zapette.model.PlaybackState
import io.github.jmorille.zapette.model.RemoteController
import io.github.jmorille.zapette.model.RemoteKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException

data class RemoteUiState(
    val device: Device? = null,
    val connecting: Boolean = true,
    val connected: Boolean = false,
    val supportedKeys: Set<RemoteKey> = emptySet(),
    val capabilities: Set<Capability> = emptySet(),
    val playback: PlaybackState? = null,
)

class RemoteViewModel(
    private val container: AppContainer,
    private val deviceId: String,
    private val sharedUrl: String?,
) : ViewModel() {

    private val _state = MutableStateFlow(RemoteUiState())
    val state: StateFlow<RemoteUiState> = _state.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    private var controller: RemoteController? = null

    init {
        viewModelScope.launch { connect() }
    }

    fun retry() {
        viewModelScope.launch { connect() }
    }

    private suspend fun connect() {
        _state.update { it.copy(connecting = true) }
        val device = container.devices.get(deviceId)
        if (device == null) {
            _messages.emit("Appareil introuvable")
            _state.update { it.copy(connecting = false) }
            return
        }
        val c = container.controllers.create(device)
        controller = c
        _state.update {
            it.copy(device = device, supportedKeys = c.supportedKeys, capabilities = c.capabilities)
        }
        try {
            withContext(Dispatchers.IO) { c.connect() }
            if (c is PersistsCredentials) container.devices.save(device.copy(extras = c.persistableExtras()))
            _state.update { it.copy(connecting = false, connected = true) }
            sharedUrl?.let { playUrl(it) }
            if (Capability.NOW_PLAYING in c.capabilities) observePlayback(c)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.update { it.copy(connecting = false, connected = false) }
            _messages.emit(e.message ?: "Connexion impossible")
        }
    }

    private fun observePlayback(c: RemoteController) = viewModelScope.launch(Dispatchers.IO) {
        while (true) {
            try {
                c.playbackState().collect { s -> _state.update { it.copy(playback = s) } }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // L'appareil peut être momentanément indisponible : on réessaie plus tard.
            }
            delay(3_000)
        }
    }

    private fun run(action: suspend (RemoteController) -> Unit) {
        val c = controller ?: return
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { action(c) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _messages.emit(e.message ?: "Commande refusée")
            }
        }
    }

    fun press(key: RemoteKey) = run { it.send(key) }
    fun playUrl(url: String) = run { it.playUrl(url.trim()) }
    fun sendText(text: String) = run { it.sendText(text) }
    fun seekTo(ms: Long) = run { it.seekTo(ms) }
    fun setVolume(percent: Int) = run { it.setVolume(percent) }

    override fun onCleared() {
        val c = controller ?: return
        // Fermeture hors du scope du ViewModel, déjà annulé à ce stade.
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            withContext(NonCancellable) { runCatching { c.disconnect() } }
        }
    }
}
