package io.github.ttbox.kodifin.ui.remote

import io.github.ttbox.kodifin.model.SupportsFullscreen
import io.github.ttbox.kodifin.model.SupportsItemInfo
import io.github.ttbox.kodifin.model.MediaItemInfo
import kotlinx.coroutines.flow.first
import io.github.ttbox.kodifin.model.DeviceKind
import io.github.ttbox.kodifin.model.SupportsTransferOut
import io.github.ttbox.kodifin.data.LocalShare
import io.github.ttbox.kodifin.model.SupportsMediaDetails
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.compose.ui.graphics.ImageBitmap
import io.github.ttbox.kodifin.AppContainer
import io.github.ttbox.kodifin.connection.DeviceOptions
import io.github.ttbox.kodifin.connection.NetworkCheck
import io.github.ttbox.kodifin.connection.NetworkGuard
import io.github.ttbox.kodifin.connection.NetworkInfo
import io.github.ttbox.kodifin.connection.WakeOnLan
import io.github.ttbox.kodifin.model.Capability
import io.github.ttbox.kodifin.model.Device
import io.github.ttbox.kodifin.model.MediaDetails
import io.github.ttbox.kodifin.model.MediaEntry
import io.github.ttbox.kodifin.model.MediaKind
import io.github.ttbox.kodifin.model.MediaSection
import io.github.ttbox.kodifin.model.PersistsCredentials
import io.github.ttbox.kodifin.model.PlaybackState
import io.github.ttbox.kodifin.model.PowerAction
import io.github.ttbox.kodifin.model.RemoteController
import io.github.ttbox.kodifin.model.RemoteKey
import io.github.ttbox.kodifin.model.RepeatMode
import io.github.ttbox.kodifin.model.SupportsChapters
import io.github.ttbox.kodifin.model.SupportsLibrary
import io.github.ttbox.kodifin.model.SupportsLibraryMaintenance
import io.github.ttbox.kodifin.model.SupportsNotify
import io.github.ttbox.kodifin.model.SupportsPlayMode
import io.github.ttbox.kodifin.model.SupportsPower
import io.github.ttbox.kodifin.model.SupportsImages
import io.github.ttbox.kodifin.model.SupportsQueue
import io.github.ttbox.kodifin.model.SupportsSearch
import io.github.ttbox.kodifin.model.SupportsSeries
import io.github.ttbox.kodifin.model.SupportsSeekStep
import io.github.ttbox.kodifin.model.SupportsSpeed
import io.github.ttbox.kodifin.model.SupportsSyncOffset
import io.github.ttbox.kodifin.model.SupportsTargets
import io.github.ttbox.kodifin.model.SupportsTracks
import io.github.ttbox.kodifin.model.SupportsUiCommands
import io.github.ttbox.kodifin.model.UiCommand
import io.github.ttbox.kodifin.model.UnsupportedActionException
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
import io.github.ttbox.kodifin.R
import io.github.ttbox.kodifin.text.AppException
import io.github.ttbox.kodifin.text.UiText
import io.github.ttbox.kodifin.text.text
import io.github.ttbox.kodifin.text.toUiText

data class RemoteUiState(
    val device: Device? = null,
    val connecting: Boolean = true,
    val connected: Boolean = false,
    val supportedKeys: Set<RemoteKey> = emptySet(),
    val capabilities: Set<Capability> = emptySet(),
    val playback: PlaybackState? = null,
    /** Connexion non tentée : réseau non autorisé pour cet appareil (Wi-Fi requis, SSID). */
    val networkBlocked: UiText? = null,
    /** Contrôles avancés disponibles (interfaces `Supports*` du contrôleur). */
    val features: RemoteFeatures = RemoteFeatures(),
    /** Pistes et modes du média en cours ; chargé seulement quand le volet est affiché. */
    val mediaDetails: MediaDetails? = null,
    val queue: QueueState = QueueState(),
    val library: LibraryState = LibraryState(),
    val targets: TargetsState = TargetsState(),
    val series: SeriesState = SeriesState(),
    val search: SearchState = SearchState(),
    val transfer: TransferState = TransferState(),
    /** Fiche du média en cours (résumé, distribution), si le contrôleur en fournit. */
    val nowPlayingInfo: MediaItemInfo? = null,
    /** Fiche ouverte depuis la liste des épisodes. */
    val itemInfo: ItemInfoState = ItemInfoState(),
    /** Un fichier du téléphone est servi sur le réseau local. */
    val sharing: Boolean = false,
    /** Plein écran du lecteur (contrôleurs [SupportsFullscreen]) ; `null` : inconnu. */
    val fullscreen: Boolean? = null,
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

    /** Appareils enregistrés (destinations d'un transfert de lecture). */
    suspend fun allDevices(): List<Device> = emptyList()

    /** Partage des fichiers du téléphone ; `null` : indisponible. */
    val localShare: LocalShare? get() = null
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
    override suspend fun allDevices(): List<Device> = devices.saved.first()
    override val localShare: LocalShare get() = this@remoteDependencies.localShare
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
    /** Attente après la dernière frappe avant d'interroger le serveur. */
    private val searchDebounceMs: Long = SEARCH_DEBOUNCE_MS,
    /** Décodage des images (remplaçable dans les tests JVM). */
    imageDecoder: ImageDecoder<ImageBitmap> = BitmapImageDecoder,
) : ViewModel() {

    private val _state = MutableStateFlow(RemoteUiState())
    val state: StateFlow<RemoteUiState> = _state.asStateFlow()

    // Canal (et non SharedFlow) : un message émis pendant une rotation, quand l'écran
    // n'écoute pas encore, est conservé au lieu d'être perdu.
    private val _messages = Channel<UiText>(Channel.BUFFERED)
    val messages: Flow<UiText> = _messages.receiveAsFlow()

    private var controller: RemoteController? = null
    private var connectJob: Job? = null
    private var playbackJob: Job? = null
    private var cleared = false

    /** Volets affichés à l'écran : seuls ceux-ci sont chargés (pas d'interrogation en tâche de fond). */
    private var visiblePanels: Set<RemotePanel> = emptySet()
    private var detailsJob: Job? = null

    private val session = RemoteSession(_state, _messages, viewModelScope, io) { controller }

    /** Volets de l'écran, chacun avec sa propre logique de chargement. */
    val queue = QueueFeature(session)
    val library = LibraryFeature(session, ::playEntry)
    val series = SeriesFeature(session)
    val search = SearchFeature(session, searchDebounceMs)
    val transfer = TransferFeature(session, deps, deviceId)

    /** Affiches et vignettes, récupérées par le contrôleur courant et gardées en mémoire. */
    val images: ImageLoader<ImageBitmap> = ImageLoader(
        scope = viewModelScope,
        io = io,
        decoder = imageDecoder,
        maxCacheBytes = ImageLoader.defaultCacheBytes(),
    ) { key, width -> connectedAs<SupportsImages>()?.image(key, width) }

    /** Dernier titre reçu, pour ne recharger pistes et file qu'au changement de média. */
    @Volatile private var lastTitle: String? = null
    @Volatile private var titleSeen = false

    init {
        // Partage lancé depuis une autre télécommande : « Arrêter le partage » reste proposé.
        if (deps.localShare?.isSharing == true) _state.update { it.copy(sharing = true) }
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
            _messages.trySend(UiText.Res(R.string.remote_wake_sent, device.name))
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
        _messages.trySend(UiText.Res(R.string.remote_wake_failed, e.toUiText(UiText.Raw(""))))
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
            queue.cancel()
            library.cancel()
            series.cancel()
            search.cancel()
            images.clear()
            titleSeen = false
            _state.update {
                it.copy(
                    connecting = true, connected = false, playback = null, networkBlocked = null,
                    mediaDetails = null, queue = QueueState(), library = LibraryState(), targets = TargetsState(),
                    series = SeriesState(), search = SearchState(),
                )
            }

            val device = deps.loadDevice(deviceId)
            if (device == null) {
                _state.update { it.copy(connecting = false) }
                _messages.trySend(UiText.Res(R.string.remote_device_not_found))
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
                // Connu après la connexion seulement (client Jellyfin choisi, commandes acceptées).
                refreshAbilities(c)
                refreshFullscreen(force = true)
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
                _messages.trySend(e.toUiText(UiText.Res(R.string.remote_connection_failed)))
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
                    refreshAbilities(c)
                    onTitle(s.title)
                    refreshFullscreen()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // L'appareil peut être momentanément indisponible : on réessaie plus tard.
            }
            delay(playbackRetryDelayMs)
        }
    }

    private fun run(after: (() -> Unit)? = null, action: suspend (RemoteController) -> Unit) = session.run(after, action)

    private inline fun <reified T : Any> runOn(
        noinline after: (() -> Unit)? = null,
        crossinline action: suspend (T) -> Unit,
    ) = session.runOn<T>(after, action)

    private suspend fun <R> load(block: suspend () -> R): R? = session.load(block)

    private inline fun <reified T : Any> connectedAs(): T? = session.connectedAs<T>()

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
            RemotePanel.QUEUE -> {
                queue.refresh()
                series.refresh()
            }
            RemotePanel.LIBRARY -> library.open()
        }
    }

    /** Changement de média : recharge les volets visibles qui en dépendent. */
    /**
     * Fonctions, touches et capacités relues sur le contrôleur : elles peuvent changer avec le
     * client piloté (autre session Jellyfin) ; l'état n'est mis à jour que s'il y a une différence.
     */
    private fun refreshAbilities(c: RemoteController) {
        if (c !== controller) return
        val keys = c.supportedKeys
        val capabilities = c.capabilities
        _state.update { s ->
            val features = RemoteFeatures.of(c).copy(powerActions = s.features.powerActions)
            if (features == s.features && keys == s.supportedKeys && capabilities == s.capabilities) s
            else s.copy(features = features, supportedKeys = keys, capabilities = capabilities)
        }
    }

    /** Fiche du média en cours, rechargée au changement de titre (sans message en cas d'échec). */
    private fun refreshNowPlayingInfo(title: String?) {
        val c = connectedAs<SupportsItemInfo>()
        infoJob?.cancel()
        if (c == null || title == null) {
            _state.update { it.copy(nowPlayingInfo = null) }
            return
        }
        infoJob = viewModelScope.launch {
            val info = try {
                withContext(io) { c.nowPlayingInfo() }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            _state.update { it.copy(nowPlayingInfo = info) }
        }
    }

    private var infoJob: Job? = null

    /** Ouvre la fiche d'un épisode (ou d'un film) de la liste. */
    fun openItemInfo(entry: MediaEntry) {
        val c = connectedAs<SupportsItemInfo>() ?: return run { _messages.trySend(NOT_SUPPORTED) }
        _state.update { it.copy(itemInfo = ItemInfoState(entry = entry, loading = true)) }
        viewModelScope.launch {
            val info = load { c.itemInfo(entry.id) }
            _state.update { s ->
                if (s.itemInfo.entry?.id != entry.id) s else s.copy(itemInfo = s.itemInfo.copy(loading = false, info = info))
            }
        }
    }

    fun closeItemInfo() = _state.update { it.copy(itemInfo = ItemInfoState()) }

    private fun onTitle(title: String?) {
        if (titleSeen && title == lastTitle) return
        val changed = titleSeen
        titleSeen = true
        lastTitle = title
        refreshNowPlayingInfo(title)
        viewModelScope.launch {
            // Pistes et chapitres : nécessaires à la barre d'avancement même volet fermé.
            if (title != null) refreshDetails()
            if (changed && RemotePanel.QUEUE in visiblePanels) {
                queue.refresh()
                series.refresh()
            }
        }
    }

    // --- Pistes, vitesse, synchronisation, modes, chapitres ---------------------------------

    fun refreshDetails() {
        val c = connectedAs<SupportsMediaDetails>() ?: return
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

    /** Dernière lecture de l'état du plein écran (horloge monotone, ms). */
    @Volatile private var fullscreenCheckedAt = Long.MIN_VALUE / 2

    /**
     * Relit l'état du plein écran : à la connexion, après un changement, puis au plus toutes les
     * [FULLSCREEN_REFRESH_MS] pendant la lecture (l'état peut changer depuis la télécommande de la TV).
     */
    private fun refreshFullscreen(force: Boolean = false) {
        val c = connectedAs<SupportsFullscreen>() ?: return
        val now = System.nanoTime() / 1_000_000
        if (!force && now - fullscreenCheckedAt < FULLSCREEN_REFRESH_MS) return
        fullscreenCheckedAt = now
        viewModelScope.launch {
            val value = try {
                withContext(io) { c.isFullscreen() }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            _state.update { if (it.fullscreen == value) it else it.copy(fullscreen = value) }
        }
    }

    /** Entre ou sort du plein écran (action explicite, pas une bascule). */
    fun setFullscreen(enabled: Boolean) = runOn<SupportsFullscreen>(
        after = {
            _state.update { it.copy(fullscreen = enabled) }
            refreshFullscreen(force = true)
        },
    ) { it.setFullscreen(enabled) }

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
        after = { _messages.trySend(UiText.Res(R.string.remote_power_sent, action.text)) },
    ) { it.power(action) }

    fun showMessage(title: String, text: String) = runOn<SupportsNotify>(
        after = { _messages.trySend(UiText.Res(R.string.remote_notify_shown)) },
    ) { it.showMessage(title.trim(), text.trim()) }

    fun scanLibrary() = runOn<SupportsLibraryMaintenance>(
        after = { _messages.trySend(UiText.Res(R.string.remote_library_scan_started)) },
    ) { it.scanLibrary() }

    fun cleanLibrary() = runOn<SupportsLibraryMaintenance>(
        after = { _messages.trySend(UiText.Res(R.string.remote_library_clean_started)) },
    ) { it.cleanLibrary() }

    fun playEntry(entry: MediaEntry) = runOn<SupportsLibrary>(
        after = { if (RemotePanel.QUEUE in visiblePanels) queue.refresh() },
    ) { it.play(entry.id) }

    fun enqueue(entry: MediaEntry, next: Boolean = false) = runOn<SupportsLibrary>(
        after = {
            _messages.trySend(
                UiText.Res(if (next) R.string.remote_enqueued_next else R.string.remote_enqueued, entry.title),
            )
            if (RemotePanel.QUEUE in visiblePanels) queue.refresh()
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
        val NOT_SUPPORTED = RemoteSession.NOT_SUPPORTED
        const val VOLUME_STEP = 5
        const val SEARCH_DEBOUNCE_MS = SearchFeature.DEFAULT_DEBOUNCE_MS

        // Réveil : jusqu'à 12 tentatives espacées de 5 s (environ une minute de démarrage).
        const val WAKE_ATTEMPTS = 12
        const val WAKE_RETRY_DELAY_MS = 5_000L
    }
}

/** Intervalle minimal entre deux lectures de l'état du plein écran pendant la lecture. */
const val FULLSCREEN_REFRESH_MS = 5_000L
