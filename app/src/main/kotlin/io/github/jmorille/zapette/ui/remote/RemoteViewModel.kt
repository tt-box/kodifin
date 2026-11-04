package io.github.jmorille.zapette.ui.remote

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.jmorille.zapette.AppContainer
import io.github.jmorille.zapette.connection.DeviceOptions
import io.github.jmorille.zapette.connection.NetworkCheck
import io.github.jmorille.zapette.connection.NetworkGuard
import io.github.jmorille.zapette.connection.NetworkInfo
import io.github.jmorille.zapette.connection.WakeOnLan
import io.github.jmorille.zapette.model.Capability
import io.github.jmorille.zapette.model.Device
import io.github.jmorille.zapette.model.MediaDetails
import io.github.jmorille.zapette.model.MediaEntry
import io.github.jmorille.zapette.model.MediaSection
import io.github.jmorille.zapette.model.PersistsCredentials
import io.github.jmorille.zapette.model.PlaybackState
import io.github.jmorille.zapette.model.PowerAction
import io.github.jmorille.zapette.model.RemoteController
import io.github.jmorille.zapette.model.RemoteKey
import io.github.jmorille.zapette.model.RepeatMode
import io.github.jmorille.zapette.model.SupportsChapters
import io.github.jmorille.zapette.model.SupportsLibrary
import io.github.jmorille.zapette.model.SupportsLibraryMaintenance
import io.github.jmorille.zapette.model.SupportsNotify
import io.github.jmorille.zapette.model.SupportsPlayMode
import io.github.jmorille.zapette.model.SupportsPower
import io.github.jmorille.zapette.model.SupportsQueue
import io.github.jmorille.zapette.model.SupportsSeekStep
import io.github.jmorille.zapette.model.SupportsSpeed
import io.github.jmorille.zapette.model.SupportsSyncOffset
import io.github.jmorille.zapette.model.SupportsTargets
import io.github.jmorille.zapette.model.SupportsTracks
import io.github.jmorille.zapette.model.SupportsUiCommands
import io.github.jmorille.zapette.model.UiCommand
import io.github.jmorille.zapette.model.UnsupportedActionException
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
    /** Connexion non tentée : réseau non autorisé pour cet appareil (Wi-Fi requis, SSID). */
    val networkBlocked: String? = null,
    /** Contrôles avancés disponibles (interfaces `Supports*` du contrôleur). */
    val features: RemoteFeatures = RemoteFeatures(),
    /** Pistes et modes du média en cours ; chargé seulement quand le volet est affiché. */
    val mediaDetails: MediaDetails? = null,
    val queue: QueueState = QueueState(),
    val library: LibraryState = LibraryState(),
    val targets: TargetsState = TargetsState(),
)

/** Dépendances du ViewModel, isolées pour les tests JVM (pas de Context Android). */
interface RemoteDependencies {
    suspend fun loadDevice(id: String): Device?
    suspend fun createController(device: Device): RemoteController
    suspend fun saveDevice(device: Device)

    /** Envoie le signal Wake-on-LAN de l'appareil (sans effet s'il n'en a pas). */
    suspend fun wake(device: Device) {}

    /** État du réseau pour la restriction Wi-Fi / SSID ; `null` : pas de vérification. */
    val network: NetworkInfo? get() = null

    /** Portée qui survit au ViewModel : sert à fermer la connexion après sa destruction. */
    val backgroundScope: CoroutineScope
}

fun AppContainer.remoteDependencies(networkInfo: NetworkInfo? = null): RemoteDependencies = object : RemoteDependencies {
    override val network: NetworkInfo? get() = networkInfo
    override suspend fun loadDevice(id: String) = devices.get(id)
    override suspend fun createController(device: Device) = controllers.create(device)
    override suspend fun saveDevice(device: Device) = devices.save(device)
    override suspend fun wake(device: Device) {
        DeviceOptions.wakeTarget(device)?.let { WakeOnLan.send(it) }
    }
    override val backgroundScope: CoroutineScope get() = appScope
}

class RemoteViewModel(
    private val deps: RemoteDependencies,
    private val deviceId: String,
    private val sharedUrl: String?,
    private val savedState: SavedStateHandle,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    /** Pause avant de se réabonner à l'état de lecture après une erreur. */
    private val playbackRetryDelayMs: Long = PLAYBACK_RETRY_DELAY_MS,
    /** Délai au-delà duquel on abandonne une déconnexion qui ne rend pas la main. */
    private val disconnectTimeoutMs: Long = DISCONNECT_TIMEOUT_MS,
    /** Pause entre deux tentatives de connexion pendant le démarrage d'un appareil réveillé. */
    private val wakeRetryDelayMs: Long = WAKE_RETRY_DELAY_MS,
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
    private var cleared = false

    /** Volets affichés à l'écran : seuls ceux-ci sont chargés (pas d'interrogation en tâche de fond). */
    private var visiblePanels: Set<RemotePanel> = emptySet()
    private var detailsJob: Job? = null
    private var queueJob: Job? = null
    private var libraryJob: Job? = null

    /** Dernier titre reçu, pour ne recharger pistes et file qu'au changement de média. */
    @Volatile private var lastTitle: String? = null
    @Volatile private var titleSeen = false

    init {
        connect()
    }

    /** Relance la connexion ; ignoré si une tentative est déjà en cours (double appui). */
    fun retry() {
        if (connectJob?.isActive == true) return
        connect()
    }

    /**
     * Envoie le signal Wake-on-LAN puis, si l'appareil n'est pas connecté, relance la connexion
     * avec plusieurs tentatives le temps qu'il démarre.
     */
    fun wake() {
        val device = _state.value.device ?: return
        viewModelScope.launch {
            if (!sendWake(device)) return@launch
            _messages.trySend("Signal de réveil envoyé à ${device.name}")
            if (!_state.value.connected) {
                connectJob?.cancel()
                connect(afterWake = true)
            }
        }
    }

    private suspend fun sendWake(device: Device): Boolean = try {
        withContext(io) { deps.wake(device) }
        true
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        _messages.trySend("Réveil impossible : ${e.message.orEmpty()}")
        false
    }

    private fun connect(afterWake: Boolean = false) {
        connectJob = viewModelScope.launch {
            // Une seule connexion à la fois : on libère la précédente et sa boucle d'observation.
            playbackJob?.cancel()
            playbackJob = null
            controller?.let { old ->
                controller = null
                release(old)
            }
            detailsJob?.cancel()
            queueJob?.cancel()
            libraryJob?.cancel()
            titleSeen = false
            _state.update {
                it.copy(
                    connecting = true, connected = false, playback = null, networkBlocked = null,
                    mediaDetails = null, queue = QueueState(), library = LibraryState(), targets = TargetsState(),
                )
            }

            val device = deps.loadDevice(deviceId)
            if (device == null) {
                _state.update { it.copy(connecting = false) }
                _messages.trySend("Appareil introuvable")
                return@launch
            }
            // Restriction réseau : rien n'est envoyé (ni réveil, ni connexion) hors des réseaux autorisés.
            when (val check = NetworkGuard.check(DeviceOptions.networkPolicy(device), deps.network)) {
                is NetworkCheck.Blocked -> {
                    _state.update { it.copy(device = device, connecting = false, networkBlocked = check.message) }
                    _messages.trySend(check.message)
                    return@launch
                }
                is NetworkCheck.Allowed -> check.warning?.let { _messages.trySend(it) }
            }
            // Réveil automatique : signal envoyé avant de se connecter, puis tentatives répétées.
            val autoWake = DeviceOptions.autoWake(device)
            if (autoWake) sendWake(device)
            val attempts = if (autoWake || afterWake) WAKE_ATTEMPTS else 1
            try {
                val c = deps.createController(device)
                // Création non interruptible terminée après onCleared : personne d'autre ne
                // fermerait ce contrôleur.
                if (cleared) {
                    release(c)
                    return@launch
                }
                controller = c
                _state.update {
                    it.copy(
                        device = device, supportedKeys = c.supportedKeys, capabilities = c.capabilities,
                        features = RemoteFeatures.of(c),
                    )
                }
                connectWithRetry(c, attempts)
                _state.update { it.copy(connecting = false, connected = true) }
                persistCredentials(device, c)
                consumeSharedUrl()?.let(::playUrl)
                if (Capability.NOW_PLAYING in c.capabilities) playbackJob = observePlayback(c)
                loadPowerActions(c)
                refreshTargets()
                visiblePanels.forEach(::refreshPanel)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(connecting = false, connected = false) }
                _messages.trySend(e.message ?: "Connexion impossible")
            }
        }
    }

    private suspend fun connectWithRetry(c: RemoteController, attempts: Int) {
        repeat(attempts) { attempt ->
            try {
                withContext(io) { c.connect() }
                return
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (attempt == attempts - 1) throw e
                delay(wakeRetryDelayMs)
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
                c.playbackState().collect { s ->
                    _state.update { it.copy(playback = s) }
                    onTitle(s.title)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // L'appareil peut être momentanément indisponible : on réessaie plus tard.
            }
            delay(playbackRetryDelayMs)
        }
    }

    /**
     * Exécute une commande sur l'appareil connecté ; [after] n'est appelé qu'en cas de
     * succès, par exemple pour recharger un volet.
     */
    private fun run(after: (() -> Unit)? = null, action: suspend (RemoteController) -> Unit) {
        val c = controller
        if (c == null || !_state.value.connected) {
            _messages.trySend("Appareil non connecté")
            return
        }
        viewModelScope.launch {
            try {
                withContext(io) { action(c) }
                after?.invoke()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _messages.trySend(errorMessage(e))
            }
        }
    }

    /** Commande d'un contrôle avancé : refusée proprement si le contrôleur ne l'implémente pas. */
    private inline fun <reified T : Any> runOn(
        noinline after: (() -> Unit)? = null,
        crossinline action: suspend (T) -> Unit,
    ) = run(after) { c -> action(c as? T ?: throw UnsupportedActionException(NOT_SUPPORTED)) }

    /** Lecture d'une donnée de l'appareil ; `null` (et message court) en cas d'échec. */
    private suspend fun <R> load(block: suspend () -> R): R? = try {
        withContext(io) { block() }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        _messages.trySend(errorMessage(e))
        null
    }

    private fun errorMessage(e: Exception): String = when (e) {
        is UnsupportedActionException -> e.message?.takeIf { it.isNotBlank() } ?: NOT_SUPPORTED
        else -> e.message ?: "Commande refusée"
    }

    private inline fun <reified T : Any> connectedAs(): T? =
        controller?.takeIf { _state.value.connected } as? T

    fun press(key: RemoteKey) = run { it.send(key) }
    fun playUrl(url: String) = run { it.playUrl(url.trim()) }
    fun sendText(text: String) = run { it.sendText(text) }
    fun seekTo(ms: Long) = run { it.seekTo(ms) }
    fun setVolume(percent: Int) = run { it.setVolume(percent.coerceIn(0, 100)) }

    /**
     * Touche de volume physique du téléphone : pilote l'appareil plutôt que le volume local.
     * Renvoie `false` si l'appareil ne sait pas régler le volume (le système garde la main).
     */
    fun onVolumeKey(up: Boolean): Boolean {
        val s = _state.value
        if (!s.connected) return false
        val key = if (up) RemoteKey.VOLUME_UP else RemoteKey.VOLUME_DOWN
        if (key in s.supportedKeys) {
            press(key)
            return true
        }
        val volume = s.playback?.volumePercent
        if (Capability.VOLUME in s.capabilities && volume != null) {
            val target = (volume + if (up) VOLUME_STEP else -VOLUME_STEP).coerceIn(0, 100)
            // Mise à jour locale : des appuis rapprochés s'enchaînent sans attendre l'appareil.
            _state.update { it.copy(playback = it.playback?.copy(volumePercent = target)) }
            setVolume(target)
            return true
        }
        return false
    }

    // --- Volets affichés -------------------------------------------------------------------

    /**
     * L'écran signale les volets visibles : un volet qui apparaît est chargé, les autres ne
     * sont rechargés qu'au changement de média (pas d'interrogation périodique).
     */
    fun setVisiblePanels(panels: Set<RemotePanel>) {
        val appeared = panels - visiblePanels
        visiblePanels = panels
        if (_state.value.connected) appeared.forEach(::refreshPanel)
    }

    private fun refreshPanel(panel: RemotePanel) {
        when (panel) {
            RemotePanel.DETAILS -> refreshDetails()
            RemotePanel.QUEUE -> refreshQueue()
            RemotePanel.LIBRARY -> openLibrary()
        }
    }

    /** Changement de média : recharge les volets visibles qui en dépendent. */
    private fun onTitle(title: String?) {
        if (titleSeen && title == lastTitle) return
        val changed = titleSeen
        titleSeen = true
        lastTitle = title
        viewModelScope.launch {
            // Pistes et chapitres : nécessaires à la barre d'avancement même volet fermé.
            if (title != null) refreshDetails()
            if (changed && RemotePanel.QUEUE in visiblePanels) refreshQueue()
        }
    }

    // --- Pistes, vitesse, synchronisation, modes, chapitres ---------------------------------

    fun refreshDetails() {
        val c = connectedAs<SupportsTracks>() ?: return
        detailsJob?.cancel()
        detailsJob = viewModelScope.launch {
            load { c.mediaDetails() }?.let { d -> _state.update { it.copy(mediaDetails = d) } }
        }
    }

    private val afterDetails: () -> Unit = { refreshDetails() }

    fun setAudioTrack(id: Int) = runOn<SupportsTracks>(afterDetails) { it.setAudioTrack(id) }
    fun setSubtitle(id: Int?) = runOn<SupportsTracks>(afterDetails) { it.setSubtitle(id) }
    fun nextAudioTrack() = runOn<SupportsTracks>(afterDetails) { it.nextAudioTrack() }
    fun nextSubtitle() = runOn<SupportsTracks>(afterDetails) { it.nextSubtitle() }
    fun seekBy(deltaMs: Long) = runOn<SupportsSeekStep> { it.seekBy(deltaMs) }
    fun faster() = runOn<SupportsSpeed>(afterDetails) { it.faster() }
    fun slower() = runOn<SupportsSpeed>(afterDetails) { it.slower() }
    fun subtitleDelay(later: Boolean) = runOn<SupportsSyncOffset> { it.subtitleDelay(later) }
    fun audioDelay(later: Boolean) = runOn<SupportsSyncOffset> { it.audioDelay(later) }
    fun setRepeat(mode: RepeatMode) = runOn<SupportsPlayMode>(afterDetails) { it.setRepeat(mode) }
    fun setShuffle(enabled: Boolean) = runOn<SupportsPlayMode>(afterDetails) { it.setShuffle(enabled) }
    fun nextChapter() = runOn<SupportsChapters> { it.nextChapter() }
    fun previousChapter() = runOn<SupportsChapters> { it.previousChapter() }

    // --- Interface de l'appareil, alimentation, message, maintenance -----------------------

    fun uiCommand(command: UiCommand) = runOn<SupportsUiCommands> { it.uiCommand(command) }

    private fun loadPowerActions(c: RemoteController) {
        if (c !is SupportsPower) return
        viewModelScope.launch {
            val actions = try {
                withContext(io) { c.availablePowerActions() }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Liste facultative : le menu garde au moins le réveil.
                emptySet()
            }
            _state.update { it.copy(features = it.features.copy(powerActions = actions)) }
        }
    }

    /** Action d'alimentation, déjà confirmée par l'utilisateur. */
    fun power(action: PowerAction) = runOn<SupportsPower>(
        after = { _messages.trySend("${action.label} : demande envoyée") },
    ) { it.power(action) }

    fun showMessage(title: String, text: String) = runOn<SupportsNotify>(
        after = { _messages.trySend("Message affiché sur l'appareil") },
    ) { it.showMessage(title.trim(), text.trim()) }

    fun scanLibrary() = runOn<SupportsLibraryMaintenance>(
        after = { _messages.trySend("Mise à jour de la bibliothèque lancée") },
    ) { it.scanLibrary() }

    fun cleanLibrary() = runOn<SupportsLibraryMaintenance>(
        after = { _messages.trySend("Nettoyage de la bibliothèque lancé") },
    ) { it.cleanLibrary() }

    // --- File d'attente --------------------------------------------------------------------

    fun refreshQueue() {
        val c = connectedAs<SupportsQueue>() ?: return
        queueJob?.cancel()
        _state.update { it.copy(queue = it.queue.copy(loading = true)) }
        queueJob = viewModelScope.launch {
            val result = load { c.queue() to c.queuePosition() }
            _state.update {
                if (result == null) {
                    it.copy(queue = it.queue.copy(loading = false))
                } else {
                    it.copy(queue = QueueState(result.first, result.second, loading = false, loaded = true))
                }
            }
        }
    }

    private val afterQueue: () -> Unit = { refreshQueue() }

    fun playQueueItem(index: Int) = runOn<SupportsQueue>(afterQueue) { it.playQueueItem(index) }
    fun removeQueueItem(index: Int) = runOn<SupportsQueue>(afterQueue) { it.removeQueueItem(index) }
    fun clearQueue() = runOn<SupportsQueue>(afterQueue) { it.clearQueue() }

    // --- Bibliothèque ----------------------------------------------------------------------

    /** Charge les rubriques racines une seule fois (à la première ouverture du volet). */
    fun openLibrary() {
        val c = connectedAs<SupportsLibrary>() ?: return
        val lib = _state.value.library
        if (lib.sectionsLoaded || lib.loading) return
        libraryJob?.cancel()
        _state.update { it.copy(library = it.library.copy(loading = true)) }
        libraryJob = viewModelScope.launch {
            val sections = load { c.sections() }
            _state.update {
                it.copy(
                    library = it.library.copy(
                        sections = sections ?: it.library.sections,
                        sectionsLoaded = sections != null,
                        loading = false,
                    ),
                )
            }
        }
    }

    fun openSection(section: MediaSection) = pushFolder(section.id, section.title)

    /** Ouvre un dossier, ou lance la lecture d'un élément jouable. */
    fun openEntry(entry: MediaEntry) {
        if (entry.browsable) pushFolder(entry.id, entry.title) else playEntry(entry)
    }

    private fun pushFolder(id: String, title: String) {
        connectedAs<SupportsLibrary>() ?: return
        libraryJob?.cancel()
        _state.update {
            it.copy(library = it.library.copy(path = it.library.path + LibraryLevel(id, title), loading = false))
        }
        loadLibraryPage()
    }

    /** Page suivante du dossier courant (défilement en bas de liste). */
    fun loadMoreLibrary() {
        val lib = _state.value.library
        val level = lib.current ?: return
        if (lib.loading || level.endReached) return
        loadLibraryPage()
    }

    private fun loadLibraryPage() {
        val c = connectedAs<SupportsLibrary>() ?: return
        val lib = _state.value.library
        val level = lib.current ?: return
        val depth = lib.path.size
        _state.update { it.copy(library = it.library.copy(loading = true)) }
        libraryJob = viewModelScope.launch {
            val page = load { c.browse(level.id, level.entries.size, LIBRARY_PAGE_SIZE) }
            _state.update { s ->
                val path = s.library.path
                // Navigation entre-temps (retour) : la page reçue ne concerne plus le dossier affiché.
                if (path.size != depth || path.last().id != level.id) {
                    return@update s.copy(library = s.library.copy(loading = false))
                }
                val updated = if (page == null) {
                    // Échec : on arrête le chargement automatique (évite une boucle d'erreurs).
                    path.last().copy(endReached = true)
                } else {
                    path.last().copy(entries = path.last().entries + page, endReached = page.size < LIBRARY_PAGE_SIZE)
                }
                s.copy(library = s.library.copy(path = path.dropLast(1) + updated, loading = false))
            }
        }
    }

    /** Remonte d'un niveau ; `false` si l'on est déjà à la racine (le retour système s'applique). */
    fun libraryBack(): Boolean {
        if (_state.value.library.path.isEmpty()) return false
        libraryJob?.cancel()
        _state.update { it.copy(library = it.library.copy(path = it.library.path.dropLast(1), loading = false)) }
        return true
    }

    fun playEntry(entry: MediaEntry) = runOn<SupportsLibrary>(
        after = { if (RemotePanel.QUEUE in visiblePanels) refreshQueue() },
    ) { it.play(entry.id) }

    fun enqueue(entry: MediaEntry, next: Boolean = false) = runOn<SupportsLibrary>(
        after = {
            _messages.trySend(if (next) "Lu juste après : ${entry.title}" else "Ajouté à la file d'attente : ${entry.title}")
            if (RemotePanel.QUEUE in visiblePanels) refreshQueue()
        },
    ) { it.enqueue(entry.id, next) }

    // --- Cibles de lecture -----------------------------------------------------------------

    fun refreshTargets() {
        val c = connectedAs<SupportsTargets>() ?: return
        viewModelScope.launch {
            val targets = load { c.targets() } ?: return@launch
            _state.update { it.copy(targets = TargetsState(targets, c.currentTargetId)) }
        }
    }

    fun selectTarget(id: String) = runOn<SupportsTargets>(
        after = {
            refreshTargets()
            visiblePanels.forEach(::refreshPanel)
        },
    ) { it.selectTarget(id) }

    /** Fermeture dans une portée applicative : celle du ViewModel est déjà annulée ici. */
    private fun release(c: RemoteController) {
        deps.backgroundScope.launch {
            withTimeoutOrNull(disconnectTimeoutMs) { runCatching { c.disconnect() } }
        }
    }

    override fun onCleared() {
        cleared = true
        controller?.let(::release)
        controller = null
        _messages.close()
    }

    private companion object {
        const val KEY_URL_SENT = "shared_url_sent"
        const val DISCONNECT_TIMEOUT_MS = 5_000L
        const val PLAYBACK_RETRY_DELAY_MS = 3_000L
        const val NOT_SUPPORTED = "Action non prise en charge par cet appareil"
        const val VOLUME_STEP = 5
        const val LIBRARY_PAGE_SIZE = 100

        // Réveil : jusqu'à 12 tentatives espacées de 5 s (environ une minute de démarrage).
        const val WAKE_ATTEMPTS = 12
        const val WAKE_RETRY_DELAY_MS = 5_000L
    }
}
