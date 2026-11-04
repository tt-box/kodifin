package io.github.jmorille.zapette.ui.remote

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.jmorille.zapette.AppContainer
import io.github.jmorille.zapette.model.Capability
import io.github.jmorille.zapette.model.Device
import io.github.jmorille.zapette.model.PersistsCredentials
import io.github.jmorille.zapette.model.PlaybackState
import io.github.jmorille.zapette.model.RemoteController
import io.github.jmorille.zapette.model.RemoteKey
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.cancellation.CancellationException

data class RemoteUiState(
    val device: Device? = null,
    val connecting: Boolean = true,
    val connected: Boolean = false,
    val supportedKeys: Set<RemoteKey> = emptySet(),
    val capabilities: Set<Capability> = emptySet(),
    val playback: PlaybackState? = null,
)

/** Dépendances du ViewModel, isolées pour les tests JVM (pas de Context Android). */
interface RemoteDependencies {
    suspend fun loadDevice(id: String): Device?
    suspend fun createController(device: Device): RemoteController
    suspend fun saveDevice(device: Device)

    /** Portée qui survit au ViewModel : sert à fermer la connexion après sa destruction. */
    val backgroundScope: CoroutineScope
}

fun AppContainer.remoteDependencies(): RemoteDependencies = object : RemoteDependencies {
    override suspend fun loadDevice(id: String) = devices.get(id)
    override suspend fun createController(device: Device) = controllers.create(device)
    override suspend fun saveDevice(device: Device) = devices.save(device)
    override val backgroundScope: CoroutineScope get() = appScope
}

class RemoteViewModel(
    private val deps: RemoteDependencies,
    private val deviceId: String,
    private val sharedUrl: String?,
    private val savedState: SavedStateHandle,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {

    private val _state = MutableStateFlow(RemoteUiState())
    val state: StateFlow<RemoteUiState> = _state.asStateFlow()

    // Canal (et non SharedFlow) : un message émis pendant une rotation, quand l'écran
    // n'écoute pas encore, est conservé au lieu d'être perdu.
    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages: Flow<String> = _messages.receiveAsFlow()

    private var controller: RemoteController? = null
    private var connectJob: Job? = null
    private var playbackJob: Job? = null

    init {
        connect()
    }

    /** Relance la connexion ; ignoré si une tentative est déjà en cours (double appui). */
    fun retry() {
        if (connectJob?.isActive == true) return
        connect()
    }

    private fun connect() {
        connectJob = viewModelScope.launch {
            // Une seule connexion à la fois : on libère la précédente et sa boucle d'observation.
            playbackJob?.cancel()
            playbackJob = null
            controller?.let { old ->
                controller = null
                release(old)
            }
            _state.update { it.copy(connecting = true, connected = false, playback = null) }

            val device = deps.loadDevice(deviceId)
            if (device == null) {
                _state.update { it.copy(connecting = false) }
                _messages.trySend("Appareil introuvable")
                return@launch
            }
            try {
                val c = deps.createController(device)
                controller = c
                _state.update {
                    it.copy(device = device, supportedKeys = c.supportedKeys, capabilities = c.capabilities)
                }
                withContext(io) { c.connect() }
                _state.update { it.copy(connecting = false, connected = true) }
                persistCredentials(device, c)
                consumeSharedUrl()?.let(::playUrl)
                if (Capability.NOW_PLAYING in c.capabilities) playbackJob = observePlayback(c)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(connecting = false, connected = false) }
                _messages.trySend(e.message ?: "Connexion impossible")
            }
        }
    }

    /** Enregistre le jeton obtenu (sans mot de passe), uniquement s'il a changé. */
    private suspend fun persistCredentials(device: Device, c: RemoteController) {
        if (c !is PersistsCredentials) return
        val extras = c.persistableExtras()
        if (extras == device.extras) return
        try {
            deps.saveDevice(device.copy(extras = extras))
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Échec d'écriture locale : la connexion reste utilisable, le jeton sera redemandé.
        }
    }

    /**
     * Le lien partagé n'est envoyé qu'une fois : ni une reconnexion, ni la recréation du
     * ViewModel après la mort du processus (la route le contient toujours) ne le rejouent.
     */
    private fun consumeSharedUrl(): String? {
        if (sharedUrl == null || savedState.get<Boolean>(KEY_URL_SENT) == true) return null
        savedState[KEY_URL_SENT] = true
        return sharedUrl
    }

    private fun observePlayback(c: RemoteController) = viewModelScope.launch(io) {
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
        val c = controller
        if (c == null || !_state.value.connected) {
            _messages.trySend("Appareil non connecté")
            return
        }
        viewModelScope.launch {
            try {
                withContext(io) { action(c) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _messages.trySend(e.message ?: "Commande refusée")
            }
        }
    }

    fun press(key: RemoteKey) = run { it.send(key) }
    fun playUrl(url: String) = run { it.playUrl(url.trim()) }
    fun sendText(text: String) = run { it.sendText(text) }
    fun seekTo(ms: Long) = run { it.seekTo(ms) }
    fun setVolume(percent: Int) = run { it.setVolume(percent.coerceIn(0, 100)) }

    /** Fermeture dans une portée applicative : celle du ViewModel est déjà annulée ici. */
    private fun release(c: RemoteController) {
        deps.backgroundScope.launch {
            withTimeoutOrNull(DISCONNECT_TIMEOUT_MS) { runCatching { c.disconnect() } }
        }
    }

    override fun onCleared() {
        controller?.let(::release)
        controller = null
        _messages.close()
    }

    private companion object {
        const val KEY_URL_SENT = "shared_url_sent"
        const val DISCONNECT_TIMEOUT_MS = 5_000L
    }
}
