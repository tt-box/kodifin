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
import androidx.annotation.StringRes
import io.github.ttbox.kodifin.connection.DeviceOptions
import io.github.ttbox.kodifin.data.DeviceSecrets
import io.github.ttbox.kodifin.connection.Diagnosis
import io.github.ttbox.kodifin.connection.Problem
import io.github.ttbox.kodifin.connection.diagnose
import io.github.ttbox.kodifin.model.RemoteError
import io.github.ttbox.kodifin.model.RemoteException
import kotlinx.coroutines.NonCancellable
import kotlin.coroutines.ContinuationInterceptor
import kotlinx.coroutines.isActive
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
    /**
     * Dernier échec de connexion (ou connexion perdue) : titre et conseil, affichés en carte
     * jusqu'à la tentative suivante. `null` : aucun échec en cours.
     */
    val connectionError: Diagnosis? = null,
    /** Tentative en cours (à partir de 1) quand plusieurs sont prévues (réveil) ; 0 sinon. */
    val connectAttempt: Int = 0,
    /** Nombre de tentatives prévues (réveil) ; 0 ou 1 : tentative unique, rien à afficher. */
    val connectAttempts: Int = 0,
    /** Fiche du média en cours (résumé, distribution), si le contrôleur en fournit. */
    val nowPlayingInfo: MediaItemInfo? = null,
    /** Fiche du média en cours en cours de chargement. */
    val nowPlayingInfoLoading: Boolean = false,
    /** Échec du chargement de la fiche du média en cours. */
    val nowPlayingInfoError: UiText? = null,
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

    /**
     * Fusionne dans l'appareil ENREGISTRÉ (relu au moment de l'écriture) les extras [changed] et
     * retire [removed], sans toucher au reste : une modification faite entre-temps est conservée.
     * [connected] : l'appareil tel que chargé pour la connexion ; si sa cible (hôte, port, schéma)
     * a changé depuis, les secrets obtenus ne sont pas rattachés à la nouvelle cible.
     */
    suspend fun mergeExtras(connected: Device, changed: Map<String, String>, removed: Set<String>) {
        val current = loadDevice(connected.id) ?: return
        val sameTarget = DeviceSecrets.sameTarget(current, connected)
        val secret = { key: String -> !sameTarget && key in DeviceSecrets.SECRET_KEYS }
        saveDevice(current.copy(extras = current.extras - removed.filterNot(secret).toSet() + changed.filterKeys { !secret(it) }))
    }

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
    override suspend fun mergeExtras(connected: Device, changed: Map<String, String>, removed: Set<String>) =
        devices.mergeExtras(connected, changed, removed)
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
    private var infoJob: Job? = null

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
        _messages.trySend(UiText.Res(R.string.remote_wake_failed, e.toUiText(UiText.Res(R.string.remote_wake_error_generic))))
        false
    }

    /**
     * Abandonne la connexion en cours (tentatives répétées après un réveil, appareil lent) : le
     * contrôleur éventuellement créé est libéré ; « Reconnecter » permet de recommencer.
     */
    fun cancelConnect() {
        val job = connectJob ?: return
        if (!job.isActive) return
        job.cancel()
        playbackJob?.cancel()
        playbackJob = null
        controller?.let { c ->
            controller = null
            release(c)
        }
        _state.update { it.copy(connecting = false, connected = false, connectAttempt = 0, connectAttempts = 0) }
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
            infoJob?.cancel()
            queue.cancel()
            library.cancel()
            series.cancel()
            search.cancel()
            images.clear()
            titleSeen = false
            _state.update {
                it.copy(
                    connecting = true, connected = false, playback = null, networkBlocked = null,
                    connectionError = null, connectAttempt = 0, connectAttempts = 0,
                    mediaDetails = null, queue = QueueState(), library = LibraryState(), targets = TargetsState(),
                    series = SeriesState(), search = SearchState(),
                    nowPlayingInfo = null, nowPlayingInfoLoading = false, nowPlayingInfoError = null,
                )
            }

            var created: RemoteController? = null
            try {
                val device = deps.loadDevice(deviceId)
                if (device == null) {
                    _state.update {
                        it.copy(connecting = false, connectionError = Diagnosis(UiText.Res(R.string.remote_device_not_found), null))
                    }
                    return@launch
                }
                // Nom et réveil visibles même si la suite échoue (création du contrôleur, connexion).
                _state.update { it.copy(device = device) }
                // Restriction réseau : rien n'est envoyé (ni réveil, ni connexion) hors des réseaux autorisés.
                when (val check = NetworkGuard.check(DeviceOptions.networkPolicy(device), deps.network)) {
                    is NetworkCheck.Blocked -> {
                        _state.update { it.copy(connecting = false, networkBlocked = check.message) }
                        _messages.trySend(check.message)
                        return@launch
                    }
                    is NetworkCheck.Allowed -> check.warning?.let { _messages.trySend(it) }
                }
                // Réveil automatique : signal envoyé avant de se connecter, puis tentatives répétées.
                val autoWake = DeviceOptions.autoWake(device)
                if (autoWake) sendWake(device)
                val attempts = if (autoWake || afterWake) WAKE_ATTEMPTS else 1
                // Déchiffrement des secrets (Keystore) hors du thread principal. Création non
                // interruptible : un contrôleur obtenu après une annulation (dont le résultat de
                // withContext serait perdu) est retenu ici puis libéré.
                var fresh: RemoteController? = null
                try {
                    withContext(io + NonCancellable) { fresh = deps.createController(device) }
                } catch (e: CancellationException) {
                    fresh?.let(::release)
                    throw e
                }
                val c = checkNotNull(fresh)
                if (cleared || !isActive) {
                    release(c)
                    return@launch
                }
                created = c
                controller = c
                _state.update {
                    it.copy(supportedKeys = c.supportedKeys, capabilities = c.capabilities, features = RemoteFeatures.of(c))
                }
                connectWithRetry(c, attempts)
                _state.update { it.copy(connecting = false, connected = true, connectAttempt = 0, connectAttempts = 0) }
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
                // Contrôleur créé mais connexion ratée : libéré, sinon il continuerait à se
                // reconnecter seul (WebSocket Kodi) en tâche de fond.
                created?.let { c ->
                    if (controller === c) controller = null
                    release(c)
                }
                _state.update {
                    it.copy(
                        connecting = false, connected = false, connectAttempt = 0, connectAttempts = 0,
                        connectionError = connectionDiagnosis(e, R.string.remote_connection_failed),
                    )
                }
            }
        }
    }

    /**
     * Tentatives de connexion espacées (le temps qu'un appareil réveillé démarre) ; une erreur
     * définitive (identifiants refusés, adresse invalide, autre service) arrête tout de suite.
     */
    private suspend fun connectWithRetry(c: RemoteController, attempts: Int) {
        repeat(attempts) { attempt ->
            if (attempts > 1) _state.update { it.copy(connectAttempt = attempt + 1, connectAttempts = attempts) }
            try {
                withContext(io) { c.connect() }
                return
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (attempt == attempts - 1 || isDefinitiveConnectError(e)) throw e
                delay(wakeRetryDelayMs)
            }
        }
    }

    /** Enregistre le jeton obtenu (sans mot de passe), uniquement s'il a changé. */
    private suspend fun persistCredentials(device: Device, c: RemoteController) {
        if (c !is PersistsCredentials) return
        val extras = c.persistableExtras()
        if (extras == device.extras) return
        // Seuls les extras modifiés par la connexion sont fusionnés dans l'appareil relu au moment
        // de l'écriture : une modification faite entre-temps (nom, options) n'est pas écrasée.
        val changed = extras.filter { (key, value) -> device.extras[key] != value }
        val removed = device.extras.keys - extras.keys
        try {
            withContext(io) { deps.mergeExtras(device, changed, removed) }
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

    /**
     * Suit l'état de lecture : le flux est lu sur [io], les mises à jour d'état (contrôleur
     * courant, tâches de chargement) restent sur le thread principal. Après
     * [PLAYBACK_MAX_FAILURES] échecs de transport consécutifs ([countsAsLostConnection]) sans
     * aucune donnée reçue, la connexion est considérée perdue : le contrôleur est libéré et l'erreur affichée (« Réessayer »).
     */
    private fun observePlayback(c: RemoteController) = viewModelScope.launch {
        // Pas de flowOn : un état émis juste avant une erreur serait perdu (annulation du
        // collecteur par l'échec du producteur) ; chaque état revient sur le thread principal.
        val main = coroutineContext[ContinuationInterceptor] ?: Dispatchers.Main
        var failures = 0
        while (true) {
            try {
                withContext(io) {
                    c.playbackState().collect { s ->
                        withContext(main) {
                            failures = 0
                            _state.update { it.copy(playback = s) }
                            refreshAbilities(c)
                            onTitle(s.title)
                            refreshFullscreen()
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // L'appareil peut être momentanément indisponible : on réessaie plus tard. Seules
                // les pertes de liaison (et les refus définitifs) comptent ; une erreur d'état
                // (rien en lecture, client Jellyfin fermé…) prouve que l'appareil répond.
                when {
                    countsAsLostConnection(e) -> if (++failures >= PLAYBACK_MAX_FAILURES) {
                        connectionLost(c, e)
                        return@launch
                    }
                    else -> failures = 0
                }
            }
            delay(playbackRetryDelayMs)
        }
    }

    /** Connexion perdue pendant l'utilisation : contrôleur libéré, erreur affichée en carte. */
    private fun connectionLost(c: RemoteController, e: Exception) {
        if (c !== controller) return
        controller = null
        playbackJob = null
        release(c)
        _state.update {
            it.copy(
                connecting = false, connected = false, playback = null,
                connectionError = connectionDiagnosis(e, R.string.remote_connection_lost),
            )
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

    /**
     * Fiche du média en cours, rechargée au changement de titre : chargement, échec et absence
     * de fiche sont distingués (sans message éphémère en cas d'échec).
     */
    private fun refreshNowPlayingInfo(title: String?) {
        val c = connectedAs<SupportsItemInfo>()
        infoJob?.cancel()
        if (c == null || title == null) {
            _state.update { it.copy(nowPlayingInfo = null, nowPlayingInfoLoading = false, nowPlayingInfoError = null) }
            return
        }
        // La fiche du média précédent ne doit pas rester affichée pendant le chargement du suivant.
        _state.update { it.copy(nowPlayingInfo = null, nowPlayingInfoLoading = true, nowPlayingInfoError = null) }
        infoJob = viewModelScope.launch {
            try {
                val info = withContext(io) { c.nowPlayingInfo() }
                _state.update { it.copy(nowPlayingInfo = info, nowPlayingInfoLoading = false, nowPlayingInfoError = null) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update {
                    it.copy(nowPlayingInfo = null, nowPlayingInfoLoading = false, nowPlayingInfoError = session.errorMessage(e))
                }
            }
        }
    }

    /** Recharge la fiche du média en cours (bouton « Réessayer » de l'onglet Fiche). */
    fun retryNowPlayingInfo() {
        if (titleSeen) refreshNowPlayingInfo(lastTitle)
    }

    /** Ouvre la fiche d'un épisode (ou d'un film) de la liste. */
    fun openItemInfo(entry: MediaEntry) {
        val c = connectedAs<SupportsItemInfo>()
        if (c == null) {
            _messages.trySend(NOT_SUPPORTED)
            return
        }
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

        /** Échecs consécutifs du suivi de lecture au-delà desquels la connexion est perdue. */
        const val PLAYBACK_MAX_FAILURES = 3
        val NOT_SUPPORTED = RemoteSession.NOT_SUPPORTED
        const val VOLUME_STEP = 5
        const val SEARCH_DEBOUNCE_MS = SearchFeature.DEFAULT_DEBOUNCE_MS

        // Réveil : jusqu'à 12 tentatives espacées de 5 s (environ une minute de démarrage).
        const val WAKE_ATTEMPTS = 12
        const val WAKE_RETRY_DELAY_MS = 5_000L
    }
}

/**
 * Diagnostic affiché dans la carte d'erreur de connexion : celui de [diagnose] (titre + conseil),
 * avec [fallback] comme titre pour une exception non typée (pas de message système brut).
 */
internal fun connectionDiagnosis(e: Throwable, @StringRes fallback: Int): Diagnosis {
    val diagnosis = diagnose(e)
    val typed = generateSequence(e) { it.cause }.take(MAX_CAUSE_DEPTH)
        .any { it is RemoteException || it is UnsupportedActionException || it is AppException }
    return if (diagnosis.problem == Problem.OTHER && !typed) diagnosis.copy(title = UiText.Res(fallback)) else diagnosis
}

/**
 * Erreur qui ne disparaîtra pas en réessayant (identifiants refusés, adresse invalide, autre
 * service que celui attendu) : les tentatives répétées après un réveil s'arrêtent aussitôt.
 */
internal fun isDefinitiveConnectError(e: Throwable): Boolean {
    if (diagnose(e).problem in DEFINITIVE_PROBLEMS) return true
    return generateSequence(e) { it.cause }.take(MAX_CAUSE_DEPTH).any { cause ->
        when ((cause as? RemoteException)?.reason) {
            is RemoteError.InvalidAddress, is RemoteError.UnexpectedResponse,
            RemoteError.CredentialsRejected, RemoteError.PasswordRejected, RemoteError.PasswordRequired,
            RemoteError.SessionExpired, RemoteError.StoredSecretUnreadable,
            -> true
            else -> false
        }
    }
}

/**
 * Échec du suivi de lecture qui compte comme une perte de connexion : erreur de transport
 * (appareil injoignable, délai dépassé, échange coupé, [java.io.IOException] non typée) ou
 * erreur définitive ([isDefinitiveConnectError] : session expirée, identifiants refusés…).
 * Une erreur d'état (rien en lecture, aucun client contrôlable, statut HTTP…) n'en est pas une :
 * l'appareil a répondu, le suivi continue.
 */
internal fun countsAsLostConnection(e: Throwable): Boolean {
    if (isDefinitiveConnectError(e)) return true
    val chain = generateSequence(e) { it.cause }.take(MAX_CAUSE_DEPTH).toList()
    chain.firstNotNullOfOrNull { (it as? RemoteException)?.reason }?.let { reason ->
        return reason is RemoteError.Unreachable || reason is RemoteError.Interrupted || reason == RemoteError.Timeout
    }
    if (chain.any { it is UnsupportedActionException }) return false
    return chain.any { it is java.io.IOException }
}

private val DEFINITIVE_PROBLEMS = setOf(
    Problem.CREDENTIALS, Problem.UNEXPECTED_RESPONSE, Problem.CERTIFICATE, Problem.CERTIFICATE_CHANGED, Problem.REDIRECT,
)

private const val MAX_CAUSE_DEPTH = 20

/** Intervalle minimal entre deux lectures de l'état du plein écran pendant la lecture. */
const val FULLSCREEN_REFRESH_MS = 5_000L
