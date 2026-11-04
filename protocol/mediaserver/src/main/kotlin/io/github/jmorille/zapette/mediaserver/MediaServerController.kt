package io.github.jmorille.zapette.mediaserver

import io.github.jmorille.zapette.model.Chapter
import io.github.jmorille.zapette.model.Capability
import io.github.jmorille.zapette.model.DescribesServer
import io.github.jmorille.zapette.model.Device
import io.github.jmorille.zapette.model.DeviceKind
import io.github.jmorille.zapette.model.HttpStatusException
import io.github.jmorille.zapette.model.MediaDetails
import io.github.jmorille.zapette.model.MediaEntry
import io.github.jmorille.zapette.model.MediaKind
import io.github.jmorille.zapette.model.MediaSection
import io.github.jmorille.zapette.model.SeriesContext
import io.github.jmorille.zapette.model.SupportsImages
import io.github.jmorille.zapette.model.SupportsSearch
import io.github.jmorille.zapette.model.SupportsSeries
import io.github.jmorille.zapette.model.MediaTrack
import io.github.jmorille.zapette.model.PersistsCredentials
import io.github.jmorille.zapette.model.PlaybackState
import io.github.jmorille.zapette.model.PlaybackTarget
import io.github.jmorille.zapette.model.RemoteController
import io.github.jmorille.zapette.model.RemoteException
import io.github.jmorille.zapette.model.RemoteKey
import io.github.jmorille.zapette.model.RepeatMode
import io.github.jmorille.zapette.model.SupportsChapters
import io.github.jmorille.zapette.model.SupportsLibrary
import io.github.jmorille.zapette.model.SupportsLibraryMaintenance
import io.github.jmorille.zapette.model.SupportsNotify
import io.github.jmorille.zapette.model.SupportsPlayMode
import io.github.jmorille.zapette.model.SupportsQueue
import io.github.jmorille.zapette.model.SupportsSeekStep
import io.github.jmorille.zapette.model.SupportsSpeed
import io.github.jmorille.zapette.model.SupportsTargets
import io.github.jmorille.zapette.model.SupportsTracks
import io.github.jmorille.zapette.model.SupportsUiCommands
import io.github.jmorille.zapette.model.UiCommand
import io.github.jmorille.zapette.model.UnsupportedActionException
import io.github.jmorille.zapette.model.requireKey
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient

/**
 * Pilote une session cliente (TV, navigateur, application) d'un serveur Jellyfin/Emby.
 * Le [Device] représente le serveur ; la session cible est `extras[sessionId]`
 * ou, à défaut, la session contrôlable la plus pertinente (hors Zapette elle-même).
 *
 * Une session disparue (404) entraîne une nouvelle sélection ; un jeton refusé (401)
 * entraîne une nouvelle authentification si les identifiants sont encore en mémoire.
 *
 * Les touches ([supportedKeys]) et commandes d'interface ([uiCommands]) sont filtrées selon
 * les `SupportedCommands` déclarées par la session cible (liste absente ou vide : tout est permis).
 * L'API de contrôle à distance ne permet ni de retirer un élément de la file d'une autre
 * session ni de la vider : [removeQueueItem] et [clearQueue] lèvent [UnsupportedActionException].
 */
class MediaServerController(
    override val device: Device,
    private val client: MediaServerClient,
    private val pollIntervalMs: Long = 2_000,
    private val ownDeviceId: String? = null,
) : RemoteController, PersistsCredentials, DescribesServer,
    SupportsTargets, SupportsTracks, SupportsSeekStep, SupportsSpeed, SupportsPlayMode, SupportsChapters,
    SupportsUiCommands, SupportsNotify, SupportsLibraryMaintenance, SupportsLibrary, SupportsQueue,
    SupportsSeries, SupportsSearch, SupportsImages {

    override fun persistableExtras(): Map<String, String> = buildMap {
        putAll(device.extras - EXTRA_PASSWORD - EXTRA_SESSION_ID - EXTRA_TOKEN - EXTRA_USER_ID)
        client.accessToken?.let { put(EXTRA_TOKEN, it) }
        userId?.let { put(EXTRA_USER_ID, it) }
        // Une session imposée mais disparue n'est pas reconduite.
        pinnedSessionId?.let { put(EXTRA_SESSION_ID, it) }
        detectedBasePath?.let { put(EXTRA_BASE_PATH, it) }
    }

    /** Chemin de base trouvé automatiquement à la connexion (ex. `/jellyfin`), à enregistrer. */
    @Volatile private var detectedBasePath: String? = null

    override val capabilities = setOf(
        Capability.NAVIGATION, Capability.PLAYBACK, Capability.VOLUME, Capability.SEEK,
        Capability.TEXT_INPUT, Capability.NOW_PLAYING,
    )

    /** Dernier état connu de la session cible (capacités, lecture en cours) ; `null` : inconnu. */
    @Volatile private var lastSession: SessionDto? = null

    /** Type de média (`Video`, `Audio`…) des éléments déjà parcourus, pour [play]. */
    private val knownMediaTypes = ConcurrentHashMap<String, String>()

    /** Pistes et chapitres du dernier élément consulté via l'API de bibliothèque (repli). */
    @Volatile private var itemDetailsCache: Pair<String, ItemDto>? = null

    /** Commandes générales acceptées par la session cible, en minuscules ; vide : inconnues. */
    private fun knownCommands(): Set<String> = lastSession?.commands.orEmpty().mapTo(HashSet()) { it.lowercase() }

    private fun accepts(commands: Set<String>, command: String) = commands.isEmpty() || command.lowercase() in commands

    override val supportedKeys: Set<RemoteKey>
        get() {
            val commands = knownCommands()
            return RemoteKey.entries.filterTo(LinkedHashSet()) { key ->
                KEY_COMMANDS[key]?.let { accepts(commands, it) } ?: true
            }
        }

    override val uiCommands: Set<UiCommand>
        get() {
            val commands = knownCommands()
            return UiCommand.entries.filterTo(LinkedHashSet()) { accepts(commands, UI_COMMANDS.getValue(it)) }
        }

    /** Lève [UnsupportedActionException] si la session déclare ne pas accepter [command]. */
    private fun requireCommand(command: String, label: String) {
        if (!accepts(knownCommands(), command)) {
            throw UnsupportedActionException("$label non pris en charge par ce client ${device.kind.label}")
        }
    }

    /** Protège l'authentification et la sélection de session contre les appels concurrents. */
    private val mutex = Mutex()

    @Volatile private var userId: String? = device.extras[EXTRA_USER_ID]

    init {
        if (client.userId == null) client.userId = userId
    }

    @Volatile private var pinnedSessionId: String? = device.extras[EXTRA_SESSION_ID]

    @Volatile private var sessionId: String? = pinnedSessionId

    /** « Jellyfin Server 10.10.3 – thor » (Emby ne publie pas `ProductName`). */
    override suspend fun serverDescription(): String = describe(device.kind, client.publicInfo())

    override suspend fun connect() {
        try {
            mutex.withLock { if (client.accessToken == null) authenticateLocked() }
        } catch (e: Exception) {
            // Réponse non JSON (redirection vers l'interface web) ou 404 : le serveur est
            // probablement publié sous un chemin de base (« URL de base » de Jellyfin/Emby).
            if (!looksLikeWrongPath(e) || !detectBasePath()) throw e
            mutex.withLock { if (client.accessToken == null) authenticateLocked() }
        }
        session()
    }

    private fun looksLikeWrongPath(e: Exception): Boolean =
        device.extras[EXTRA_BASE_PATH].isNullOrEmpty() &&
            ((e is RemoteException && e.cause is kotlinx.serialization.SerializationException) ||
                (e is HttpStatusException && e.code == 404))

    /** Essaie les chemins de base usuels ; `true` (et client recalé) si l'un expose l'API. */
    private suspend fun detectBasePath(): Boolean {
        if (client.baseUrl == null) return false
        return when (val path = client.locateBasePath(BASE_PATH_CANDIDATES)) {
            "" -> false
            null -> throw RemoteException(
                "${device.name} ne répond pas comme un serveur ${device.kind.label} à cette adresse : " +
                    "vérifiez le port et le chemin de base (ex. /jellyfin) dans les options avancées",
            )
            else -> {
                detectedBasePath = path
                true
            }
        }
    }

    private suspend fun authenticateLocked() {
        val user = device.extras[EXTRA_USERNAME]
            ?: throw RemoteException("Jeton absent ou expiré sur ${device.name} : reconnectez-vous")
        // Jellyfin et Emby acceptent les comptes sans mot de passe.
        userId = try {
            client.authenticate(user, device.extras[EXTRA_PASSWORD].orEmpty()).user.id
        } catch (e: HttpStatusException) {
            if (e.code != 401) throw e
            throw RemoteException("Identifiants refusés par ${device.name} : reconnectez-vous", e)
        }
    }

    private suspend fun session(): String {
        sessionId?.let { return it }
        return mutex.withLock {
            sessionId ?: client.sessions(userId).let { all ->
                selectSession(all).also { id ->
                    sessionId = id
                    lastSession = all.firstOrNull { it.id == id }
                }
            }
        }
    }

    /** Choisit une session contrôlable autre que la nôtre, de préférence en cours de lecture. */
    internal fun selectSession(sessions: List<SessionDto>): String {
        val candidates = sessions.filter { it.supportsRemoteControl && (ownDeviceId == null || it.deviceId != ownDeviceId) }
        val target = candidates.firstOrNull { it.nowPlaying != null } ?: candidates.firstOrNull()
            ?: throw RemoteException("Aucun client contrôlable sur ${device.name}")
        return target.id
    }

    /** Oublie la session [stale] si c'est encore la session courante. */
    private suspend fun forgetSession(stale: String) = mutex.withLock {
        if (sessionId == stale) sessionId = null
        if (pinnedSessionId == stale) pinnedSessionId = null
        if (lastSession?.id == stale) lastSession = null
    }

    /**
     * Renouvelle le jeton [refused] s'il est encore le jeton courant : des requêtes parallèles
     * refusées avec le même jeton ne déclenchent qu'une seule authentification. Le jeton refusé
     * reste en place pendant l'authentification et n'est oublié qu'en cas d'échec.
     */
    private suspend fun renewToken(refused: String?) = mutex.withLock {
        if (client.accessToken == refused) {
            try {
                authenticateLocked()
            } catch (e: Throwable) {
                if (client.accessToken == refused) client.accessToken = null
                throw e
            }
        }
    }

    /**
     * Exécute [action] sur la session courante. Au plus une nouvelle authentification (401) et
     * une nouvelle sélection de session (404) : un second 401 ou un second 404 est remonté tel quel,
     * sans boucle.
     */
    private suspend fun <T> withSession(action: suspend (String) -> T): T {
        var renewed = false
        var reselected = false
        while (true) {
            val token = client.accessToken
            val id = session()
            try {
                return action(id)
            } catch (e: HttpStatusException) {
                when {
                    e.code == 401 && !renewed -> {
                        renewed = true
                        renewToken(token)
                    }
                    e.code == 404 && !reselected -> {
                        reselected = true
                        forgetSession(id)
                    }
                    else -> throw e
                }
            }
        }
    }

    override suspend fun send(key: RemoteKey) {
        requireKey(key)
        withSession { id ->
            when (key) {
                RemoteKey.UP -> client.generalCommand(id, "MoveUp")
                RemoteKey.DOWN -> client.generalCommand(id, "MoveDown")
                RemoteKey.LEFT -> client.generalCommand(id, "MoveLeft")
                RemoteKey.RIGHT -> client.generalCommand(id, "MoveRight")
                RemoteKey.SELECT -> client.generalCommand(id, "Select")
                RemoteKey.BACK -> client.generalCommand(id, "Back")
                RemoteKey.HOME -> client.generalCommand(id, "GoHome")
                RemoteKey.MENU -> client.generalCommand(id, "ToggleContextMenu")
                RemoteKey.INFO -> client.generalCommand(id, "ToggleOsdMenu")
                RemoteKey.VOLUME_UP -> client.generalCommand(id, "VolumeUp")
                RemoteKey.VOLUME_DOWN -> client.generalCommand(id, "VolumeDown")
                RemoteKey.MUTE -> client.generalCommand(id, "ToggleMute")
                RemoteKey.PLAY_PAUSE -> client.playstate(id, "PlayPause")
                RemoteKey.STOP -> client.playstate(id, "Stop")
                RemoteKey.NEXT -> client.playstate(id, "NextTrack")
                RemoteKey.PREVIOUS -> client.playstate(id, "PreviousTrack")
                RemoteKey.REWIND -> client.playstate(id, "Rewind")
                RemoteKey.FAST_FORWARD -> client.playstate(id, "FastForward")
            }
        }
    }

    override suspend fun setVolume(percent: Int) {
        withSession { client.generalCommand(it, "SetVolume", mapOf("Volume" to percent.coerceIn(0, 100).toString())) }
    }

    override suspend fun seekTo(positionMs: Long) {
        withSession { client.playstate(it, "Seek", seekPositionTicks = positionMs.coerceAtLeast(0) * TICKS_PER_MS) }
    }

    override suspend fun sendText(text: String) {
        withSession { client.generalCommand(it, "SendString", mapOf("String" to text)) }
    }

    override fun playbackState(): Flow<PlaybackState> = flow {
        while (true) {
            val s = withSession { id ->
                client.sessions(userId).firstOrNull { it.id == id }.also {
                    // Session disparue : nouvelle sélection au prochain tour.
                    if (it == null) forgetSession(id) else remember(it)
                }
            }
            val episode = s?.nowPlaying?.takeIf { it.isEpisode }
            emit(
                PlaybackState(
                    title = s?.nowPlaying?.name,
                    seriesTitle = episode?.seriesName?.takeIf { it.isNotBlank() },
                    season = episode?.parentIndexNumber,
                    episode = episode?.indexNumber,
                    isPlaying = s?.nowPlaying != null && s.playState?.isPaused == false,
                    positionMs = (s?.playState?.positionTicks ?: 0) / TICKS_PER_MS,
                    durationMs = (s?.nowPlaying?.runTimeTicks ?: 0) / TICKS_PER_MS,
                    volumePercent = s?.playState?.volumeLevel,
                    muted = s?.playState?.isMuted ?: false,
                ),
            )
            delay(pollIntervalMs)
        }
    }

    /** Mémorise l'état de [session] s'il s'agit toujours de la session cible. */
    private fun remember(session: SessionDto) {
        if (session.id == sessionId) lastSession = session
    }

    /** Exécute [action] hors session, avec au plus une nouvelle authentification (401). */
    private suspend fun <T> withAuth(action: suspend () -> T): T {
        val token = client.accessToken
        return try {
            action()
        } catch (e: HttpStatusException) {
            if (e.code != 401) throw e
            renewToken(token)
            action()
        }
    }

    /** Exécute [action] avec l'état frais de la session cible (une session disparue est resélectionnée). */
    private suspend fun <T> withCurrent(action: suspend (SessionDto) -> T): T = withSession { id ->
        val current = client.sessions(userId).firstOrNull { it.id == id }
            ?: throw HttpStatusException(404, "Session $id disparue")
        remember(current)
        action(current)
    }

    private fun requireUserId(): String =
        userId ?: throw RemoteException("Utilisateur inconnu sur ${device.name} : reconnectez-vous")

    /** Ni jeton ni extras (qui portent jeton et mot de passe) : sans risque dans un journal. */
    override fun toString() = "MediaServerController(device=${device.id}, kind=${device.kind}, session=$sessionId)"

    // --- Cibles (sessions) ---

    override val currentTargetId: String? get() = sessionId

    override suspend fun targets(): List<PlaybackTarget> {
        val sessions = withAuth { client.sessions(userId) }
        sessions.firstOrNull { it.id == sessionId }?.let(::remember)
        return sessions.filter { it.isControllable() }.map { s ->
            val name = listOf(s.client, s.deviceName).filter { it.isNotBlank() }.joinToString(" – ").ifEmpty { s.id }
            PlaybackTarget(s.id, name, s.userName?.takeIf { it.isNotBlank() })
        }
    }

    /**
     * Impose la session [id] : elle remplace la sélection automatique et est enregistrée
     * (extra [EXTRA_SESSION_ID] de [persistableExtras]).
     */
    override suspend fun selectTarget(id: String) {
        val target = withAuth { client.sessions(userId) }.firstOrNull { it.id == id && it.isControllable() }
            ?: throw RemoteException("Client introuvable ou non contrôlable sur ${device.name}")
        mutex.withLock {
            pinnedSessionId = id
            sessionId = id
            lastSession = target
        }
    }

    private fun SessionDto.isControllable() = supportsRemoteControl && (ownDeviceId == null || deviceId != ownDeviceId)

    // --- Pistes ---

    /** Pistes et chapitres de l'élément en cours ; repli sur l'API de bibliothèque s'ils manquent. */
    private suspend fun itemDetails(session: SessionDto): ItemDto? {
        val itemId = session.nowPlaying?.id ?: return null
        val uid = userId ?: return null
        itemDetailsCache?.let { (cachedId, item) -> if (cachedId == itemId) return item }
        val item = try {
            client.item(uid, itemId)
        } catch (e: HttpStatusException) {
            // Un 404 ici concerne l'élément, pas la session : il ne doit pas la faire oublier.
            if (e.code == 401) throw e
            return null
        }
        itemDetailsCache = itemId to item
        return item
    }

    private suspend fun streams(session: SessionDto): List<MediaStreamDto> {
        session.nowPlaying?.mediaStreams?.takeIf { it.isNotEmpty() }?.let { return it }
        val item = itemDetails(session) ?: return emptyList()
        val sources = item.mediaSources.orEmpty()
        val source = sources.firstOrNull { it.id != null && it.id == session.playState?.mediaSourceId } ?: sources.firstOrNull()
        return source?.mediaStreams?.takeIf { it.isNotEmpty() } ?: item.mediaStreams.orEmpty()
    }

    private suspend fun chapters(session: SessionDto): List<ChapterDto> {
        session.nowPlaying?.chapters?.takeIf { it.isNotEmpty() }?.let { return it }
        return itemDetails(session)?.chapters.orEmpty()
    }

    private suspend fun details(session: SessionDto): MediaDetails {
        val playing = session.nowPlaying ?: return MediaDetails(speed = 0)
        val state = session.playState
        val streams = streams(session)
        return MediaDetails(
            audioTracks = streams.filter { it.type.equals("Audio", true) }.map { it.toTrack() },
            currentAudio = state?.audioStreamIndex,
            subtitles = streams.filter { it.type.equals("Subtitle", true) }.map { it.toTrack() },
            currentSubtitle = state?.subtitleStreamIndex?.takeIf { it >= 0 },
            repeat = repeatMode(state?.repeatMode),
            shuffle = state?.playbackOrder?.let { it.equals("Shuffle", true) }
                ?: state?.shuffleMode?.let { it.equals("Shuffle", true) },
            speed = if (state?.isPaused == true) 0 else 1,
            chapterCount = chapters(session).size,
            chapters = chapters(session)
                .map { Chapter(it.startPositionTicks / 10_000, it.name?.trim()?.takeIf(String::isNotEmpty)) }
                .sortedBy { it.startMs },
        )
    }

    override suspend fun mediaDetails(): MediaDetails = withCurrent { details(it) }

    override suspend fun setAudioTrack(id: Int) {
        requireCommand("SetAudioStreamIndex", "Changement de piste audio")
        withSession { client.generalCommand(it, "SetAudioStreamIndex", mapOf("Index" to id.toString())) }
    }

    /** `null` désactive les sous-titres (index -1). */
    override suspend fun setSubtitle(id: Int?) {
        requireCommand("SetSubtitleStreamIndex", "Changement de sous-titres")
        withSession { client.generalCommand(it, "SetSubtitleStreamIndex", mapOf("Index" to (id ?: -1).toString())) }
    }

    /** Sous-titres suivants : désactivés → premier → … → dernier → désactivés. */
    override suspend fun nextSubtitle() {
        val d = mediaDetails()
        if (d.subtitles.isEmpty()) throw UnsupportedActionException("Aucun sous-titre pour ce média")
        setSubtitle(nextOf(d.subtitles, d.currentSubtitle, wrapToNull = true))
    }

    /** Piste audio suivante, en boucle. */
    override suspend fun nextAudioTrack() {
        val d = mediaDetails()
        if (d.audioTracks.isEmpty()) throw UnsupportedActionException("Aucune piste audio pour ce média")
        setAudioTrack(nextOf(d.audioTracks, d.currentAudio, wrapToNull = false)!!)
    }

    // --- Déplacement, vitesse, modes ---

    override suspend fun seekBy(deltaMs: Long) {
        withCurrent { s ->
            val state = s.playState
            if (s.nowPlaying == null || state == null) throw RemoteException("Aucune lecture en cours sur ce client")
            val runtime = s.nowPlaying.runTimeTicks
            val target = (state.positionTicks + deltaMs * TICKS_PER_MS)
                .coerceAtLeast(0)
                .let { if (runtime > 0) it.coerceAtMost(runtime) else it }
            client.playstate(s.id, "Seek", seekPositionTicks = target)
        }
    }

    override suspend fun faster() {
        withSession { client.playstate(it, "FastForward") }
    }

    override suspend fun slower() {
        withSession { client.playstate(it, "Rewind") }
    }

    override suspend fun setRepeat(mode: RepeatMode) {
        requireCommand("SetRepeatMode", "Mode de répétition")
        val value = when (mode) {
            RepeatMode.OFF -> "RepeatNone"
            RepeatMode.ONE -> "RepeatOne"
            RepeatMode.ALL -> "RepeatAll"
        }
        withSession { client.generalCommand(it, "SetRepeatMode", mapOf("RepeatMode" to value)) }
    }

    override suspend fun setShuffle(enabled: Boolean) {
        requireCommand("SetShuffleQueue", "Lecture aléatoire")
        withSession {
            client.generalCommand(it, "SetShuffleQueue", mapOf("ShuffleMode" to if (enabled) "Shuffle" else "Sorted"))
        }
    }

    // --- Chapitres ---

    override suspend fun nextChapter() = seekChapter(next = true)

    override suspend fun previousChapter() = seekChapter(next = false)

    /**
     * Saut au chapitre suivant (sans effet après le dernier) ou précédent ; dans les
     * [CHAPTER_GRACE_MS] premières millisecondes d'un chapitre, « précédent » remonte d'un cran.
     */
    private suspend fun seekChapter(next: Boolean) {
        withCurrent { s ->
            val starts = chapters(s).map { it.startPositionTicks }.sorted()
            if (starts.isEmpty()) throw UnsupportedActionException("Aucun chapitre pour ce média")
            val position = s.playState?.positionTicks ?: 0
            val target = if (next) {
                starts.firstOrNull { it > position } ?: return@withCurrent
            } else {
                starts.lastOrNull { it <= position - CHAPTER_GRACE_MS * TICKS_PER_MS } ?: 0
            }
            client.playstate(s.id, "Seek", seekPositionTicks = target)
        }
    }

    // --- Interface, messages ---

    override suspend fun uiCommand(command: UiCommand) {
        if (command !in uiCommands) {
            throw UnsupportedActionException("${command.label} non pris en charge par ce client ${device.kind.label}")
        }
        withSession { client.generalCommand(it, UI_COMMANDS.getValue(command)) }
    }

    override suspend fun showMessage(title: String, text: String) {
        requireCommand("DisplayMessage", "Affichage de message")
        withSession { client.sendMessage(it, title, text, MESSAGE_TIMEOUT_MS) }
    }

    // --- Maintenance ---

    override suspend fun scanLibrary() {
        try {
            withAuth { client.refreshLibrary() }
        } catch (e: HttpStatusException) {
            if (e.code != 401 && e.code != 403) throw e
            throw RemoteException("L'analyse de la bibliothèque est réservée à un administrateur de ${device.name}", e)
        }
    }

    override suspend fun cleanLibrary() {
        throw UnsupportedActionException(
            "Nettoyage de la bibliothèque non disponible : ${device.kind.label} retire les éléments disparus lors de l'analyse",
        )
    }

    // --- Bibliothèque ---

    override suspend fun sections(): List<MediaSection> {
        val uid = requireUserId()
        return withAuth { client.userViews(uid) }.filter { it.id.isNotBlank() }.map { MediaSection(it.id, it.name) }
    }

    /**
     * Contenu de [parentId]. Une saison déjà rencontrée (série en cours, parcours d'une série,
     * recherche) est listée par `GET /Shows/{seriesId}/Episodes?seasonId=…`, dans l'ordre des
     * épisodes ; tout autre dossier par la route générique (`ParentId`, tri par nom).
     */
    override suspend fun browse(parentId: String, offset: Int, limit: Int): List<MediaEntry> {
        val uid = requireUserId()
        val paging = mapOf(
            "StartIndex" to offset.coerceAtLeast(0).toString(),
            "Limit" to limit.coerceIn(1, MAX_PAGE).toString(),
        )
        seasonSeries[parentId]?.let { seriesId ->
            val episodes = try {
                withAuth { client.episodes(uid, seriesId, parentId, paging + ITEM_FIELDS) }.items
            } catch (e: HttpStatusException) {
                // Saison disparue ou route absente : repli sur le parcours générique.
                if (e.code != 404 && e.code != 400) throw e
                null
            }
            if (episodes != null) {
                val sorted = episodes.filter { it.id.isNotBlank() }
                    .sortedWith(
                        compareBy<ItemDto, Int?>(nullsLast()) { it.parentIndexNumber }
                            .thenBy(nullsLast<Int>()) { it.indexNumber },
                    )
                return learn(sorted).map { it.toEntry(withSeriesName = false) }
            }
        }
        val query = mapOf("ParentId" to parentId, "SortBy" to "SortName", "SortOrder" to "Ascending") + paging + ITEM_FIELDS
        return learn(withAuth { client.items(uid, query) }.items).map { it.toEntry() }
    }

    /** Saison → série, pour lister les épisodes d'une saison via `/Shows/{seriesId}/Episodes`. */
    private val seasonSeries = ConcurrentHashMap<String, String>()

    /** Retient type de média (pour [play]) et rattachement des saisons ; ignore les éléments sans id. */
    private fun learn(items: List<ItemDto>): List<ItemDto> {
        val valid = items.filter { it.id.isNotBlank() }
        if (knownMediaTypes.size > MAX_KNOWN_TYPES) knownMediaTypes.clear()
        if (seasonSeries.size > MAX_KNOWN_TYPES) seasonSeries.clear()
        valid.forEach { item ->
            item.mediaType?.takeIf { it.isNotBlank() }?.let { knownMediaTypes[item.id] = it }
            val series = item.seriesId?.takeIf { it.isNotBlank() }
            if (series != null && item.type == "Season") seasonSeries[item.id] = series
            // Un épisode fait connaître sa saison.
            val season = item.seasonId?.takeIf { it.isNotBlank() }
            if (series != null && season != null && item.type == "Episode") seasonSeries[season] = series
        }
        return valid
    }

    // --- Série en cours ---

    /**
     * Série de l'épisode en cours : saisons via `GET /Shows/{seriesId}/Seasons`. Si la session
     * ne publie pas `SeriesId` (anciens serveurs), il est lu sur la fiche de l'épisode.
     */
    override suspend fun currentSeries(): SeriesContext? {
        val playing = withCurrent { it.nowPlaying }?.takeIf { it.isEpisode } ?: return null
        val uid = requireUserId()
        var seriesId = playing.seriesId?.takeIf { it.isNotBlank() }
        var seasonId = playing.seasonId?.takeIf { it.isNotBlank() }
        var seriesName = playing.seriesName?.takeIf { it.isNotBlank() }
        if (seriesId == null) {
            val id = playing.id?.takeIf { it.isNotBlank() } ?: return null
            val item = try {
                withAuth { client.item(uid, id) }
            } catch (e: HttpStatusException) {
                if (e.code == 404) return null
                throw e
            }
            seriesId = item.seriesId?.takeIf { it.isNotBlank() } ?: return null
            seasonId = seasonId ?: item.seasonId?.takeIf { it.isNotBlank() }
            seriesName = seriesName ?: item.seriesName?.takeIf { it.isNotBlank() }
        }
        val seasons = learn(withAuth { client.seasons(uid, seriesId, ITEM_FIELDS) }.items)
        seasons.forEach { seasonSeries[it.id] = seriesId }
        seasonId?.let { seasonSeries[it] = seriesId }
        return SeriesContext(
            seriesId = seriesId,
            title = seriesName ?: playing.name.ifBlank { seriesId },
            seasons = seasons.map { it.toEntry() },
            currentSeasonId = seasonId,
            currentEpisodeId = playing.id?.takeIf { it.isNotBlank() },
        )
    }

    // --- Recherche ---

    override val searchKinds: Set<MediaKind> = SEARCH_TYPES.keys

    /**
     * Recherche native : `GET /Items?searchTerm=…&Recursive=true&IncludeItemTypes=…` (Emby :
     * `/Users/{id}/Items`), dans l'ordre de pertinence du serveur (aucun `SortBy`). Préférée à
     * `/Search/Hints`, dont les résultats n'ont ni `UserData` ni le même schéma que le parcours.
     * Les artistes passent par `GET /Artists?searchTerm=…` et sont placés en tête.
     */
    override suspend fun search(query: String, kinds: Set<MediaKind>, limit: Int): List<MediaEntry> {
        val term = query.trim()
        if (term.isEmpty()) return emptyList()
        val uid = requireUserId()
        val wanted = kinds.filter { it in SEARCH_TYPES }.ifEmpty { SEARCH_TYPES.keys }.toSet()
        val max = limit.coerceIn(1, MAX_PAGE)
        val base = mapOf("searchTerm" to term, "Limit" to max.toString()) + ITEM_FIELDS
        val artists = if (MediaKind.ARTIST in wanted) withAuth { client.artists(uid, base) }.items else emptyList()
        val types = (wanted - MediaKind.ARTIST).map { SEARCH_TYPES.getValue(it) }
        val items = if (types.isEmpty()) {
            emptyList()
        } else {
            val q = base + mapOf("Recursive" to "true", "IncludeItemTypes" to types.joinToString(","))
            withAuth { client.items(uid, q) }.items
        }
        return learn(artists + items).distinctBy { it.id }.take(max).map { it.toEntry() }
    }

    // --- Images ---

    /**
     * Image désignée par [imageKey] (`<itemId>/<type>[/<tag>]`, voir [imageKey]) : même client
     * HTTP et même authentification (en-tête) que le reste de l'API, même hôte uniquement,
     * au plus [MAX_IMAGE_BYTES] octets. Clé invalide ou image absente (404) : `null`.
     */
    override suspend fun image(imageKey: String, maxWidth: Int): ByteArray? {
        val (itemId, type, tag) = parseImageKey(imageKey) ?: return null
        return withAuth {
            client.image(itemId, type, tag, maxWidth.coerceIn(MIN_IMAGE_WIDTH, MAX_IMAGE_WIDTH), IMAGE_QUALITY, MAX_IMAGE_BYTES)
        }
    }

    override suspend fun play(entryId: String) {
        requirePlayable(entryId)
        withSession { client.play(it, "PlayNow", listOf(entryId)) }
    }

    override suspend fun enqueue(entryId: String, next: Boolean) {
        requirePlayable(entryId)
        withSession { client.play(it, if (next) "PlayNext" else "PlayLast", listOf(entryId)) }
    }

    /** Refuse un média dont le type est connu et absent des `PlayableMediaTypes` de la session. */
    private fun requirePlayable(entryId: String) {
        val playable = lastSession?.mediaTypes.orEmpty()
        val type = knownMediaTypes[entryId] ?: return
        if (playable.isNotEmpty() && playable.none { it.equals(type, true) }) {
            throw UnsupportedActionException("Ce client ne lit pas les médias de type $type")
        }
    }

    // --- File d'attente ---

    override suspend fun queue(): List<MediaEntry> = withCurrent { s ->
        val queue = s.nowPlayingQueue.orEmpty().filter { it.id.isNotBlank() }
        if (queue.isEmpty()) return@withCurrent emptyList()
        val uid = userId
        val titles = if (uid == null) {
            emptyMap()
        } else {
            val ids = queue.map { it.id }.distinct().joinToString(",")
            learn(client.items(uid, mapOf("Ids" to ids) + ITEM_FIELDS).items).associateBy { it.id }
        }
        queue.map { q -> titles[q.id]?.toEntry() ?: MediaEntry(q.id, q.id) }
    }

    override suspend fun queuePosition(): Int? = withCurrent { s ->
        val queue = s.nowPlayingQueue.orEmpty()
        s.playlistItemId?.let { pid -> queue.indexOfFirst { it.playlistItemId == pid } }?.takeIf { it >= 0 }
            ?: s.nowPlaying?.id?.let { id -> queue.indexOfFirst { it.id == id } }?.takeIf { it >= 0 }
    }

    /** Relance toute la file à partir de [index] (`PlayNow` + `startIndex`), sans la modifier. */
    override suspend fun playQueueItem(index: Int) {
        withCurrent { s ->
            val ids = s.nowPlayingQueue.orEmpty().map { it.id }.filter { it.isNotBlank() }
            if (index !in ids.indices) throw RemoteException("Élément absent de la file d'attente")
            client.play(s.id, "PlayNow", ids, startIndex = index)
        }
    }

    override suspend fun removeQueueItem(index: Int) {
        throw UnsupportedActionException(
            "${device.kind.label} ne permet pas de retirer un élément de la file d'un autre client",
        )
    }

    override suspend fun clearQueue() {
        throw UnsupportedActionException("${device.kind.label} ne permet pas de vider la file d'un autre client")
    }

    companion object {
        const val EXTRA_USERNAME = "username"
        const val EXTRA_PASSWORD = "password"
        const val EXTRA_USER_ID = "userId"
        const val EXTRA_SESSION_ID = "sessionId"
        const val EXTRA_TOKEN = "token"

        /** Chemin de base de l'API (« URL de base » du serveur), ex. `/jellyfin`. */
        const val EXTRA_BASE_PATH = "base_path"

        /** Durée d'affichage d'un message à l'écran. */
        const val MESSAGE_TIMEOUT_MS = 5_000L

        /** Dans un chapitre depuis moins longtemps, « chapitre précédent » remonte d'un cran. */
        const val CHAPTER_GRACE_MS = 3_000L

        private const val MAX_PAGE = 500
        private const val MAX_KNOWN_TYPES = 2_000

        /** Touches envoyées comme commandes générales (filtrables par `SupportedCommands`). */
        internal val KEY_COMMANDS = mapOf(
            RemoteKey.UP to "MoveUp",
            RemoteKey.DOWN to "MoveDown",
            RemoteKey.LEFT to "MoveLeft",
            RemoteKey.RIGHT to "MoveRight",
            RemoteKey.SELECT to "Select",
            RemoteKey.BACK to "Back",
            RemoteKey.HOME to "GoHome",
            RemoteKey.MENU to "ToggleContextMenu",
            RemoteKey.INFO to "ToggleOsdMenu",
            RemoteKey.VOLUME_UP to "VolumeUp",
            RemoteKey.VOLUME_DOWN to "VolumeDown",
            RemoteKey.MUTE to "ToggleMute",
        )

        /** Commande générale (`GeneralCommandType`) de chaque commande d'interface. */
        internal val UI_COMMANDS = mapOf(
            UiCommand.OSD to "ToggleOsdMenu",
            UiCommand.CONTEXT_MENU to "ToggleContextMenu",
            UiCommand.CODEC_INFO to "ToggleStats",
            UiCommand.FULLSCREEN to "ToggleFullscreen",
            UiCommand.HOME_SCREEN to "GoHome",
            UiCommand.SETTINGS to "GoToSettings",
            UiCommand.SEARCH to "GoToSearch",
            UiCommand.PAGE_UP to "PageUp",
            UiCommand.PAGE_DOWN to "PageDown",
            UiCommand.CHANNEL_UP to "ChannelUp",
            UiCommand.CHANNEL_DOWN to "ChannelDown",
            UiCommand.GUIDE to "Guide",
        )

        /** Types toujours navigables, même si `IsFolder` est absent. */
        private val BROWSABLE_TYPES = setOf(
            "Series", "Season", "MusicAlbum", "MusicArtist", "BoxSet", "Folder", "CollectionFolder",
            "UserView", "Playlist", "PhotoAlbum",
        )

        internal fun repeatMode(raw: String?): RepeatMode? = when (raw) {
            "RepeatNone" -> RepeatMode.OFF
            "RepeatOne" -> RepeatMode.ONE
            "RepeatAll" -> RepeatMode.ALL
            else -> null
        }

        /**
         * Piste suivant [current] dans [tracks] ; après la dernière : `null` si [wrapToNull]
         * (désactivation), sinon la première. Sans piste courante : la première.
         */
        internal fun nextOf(tracks: List<MediaTrack>, current: Int?, wrapToNull: Boolean): Int? {
            val position = tracks.indexOfFirst { it.id == current }
            return when {
                position < 0 -> tracks.firstOrNull()?.id
                position + 1 < tracks.size -> tracks[position + 1].id
                wrapToNull -> null
                else -> tracks.first().id
            }
        }

        internal fun MediaStreamDto.toTrack() = MediaTrack(
            id = index,
            label = displayTitle?.takeIf { it.isNotBlank() } ?: title?.takeIf { it.isNotBlank() }
                ?: language?.takeIf { it.isNotBlank() } ?: "Piste $index",
            language = language?.takeIf { it.isNotBlank() },
        )

        /**
         * Entrée de bibliothèque enrichie (nature, année, saison/épisode, vu, image). Sous-titre
         * d'un épisode : « Série · S01E03 · 24 min » ([withSeriesName]) ou « S01E03 · 24 min ».
         */
        internal fun ItemDto.toEntry(withSeriesName: Boolean = true): MediaEntry {
            val isEpisode = type == "Episode"
            val subtitle = if (isEpisode) {
                val code = when {
                    parentIndexNumber != null && indexNumber != null -> "S%02dE%02d".format(parentIndexNumber, indexNumber)
                    indexNumber != null -> "E%02d".format(indexNumber)
                    else -> null
                }
                val minutes = runTimeTicks?.takeIf { it > 0 }?.let { ((it + TICKS_PER_MINUTE / 2) / TICKS_PER_MINUTE).coerceAtLeast(1) }
                listOfNotNull(
                    seriesName?.takeIf { withSeriesName && it.isNotBlank() },
                    code,
                    minutes?.let { "$it min" },
                ).joinToString(" · ")
            } else {
                productionYear?.toString()
            }?.takeIf { it.isNotBlank() }
            return MediaEntry(
                id = id,
                title = name.ifBlank { id },
                subtitle = subtitle,
                browsable = isFolder || type in BROWSABLE_TYPES,
                kind = mediaKind(type),
                imageKey = imageKey(),
                year = productionYear?.takeIf { it > 0 },
                season = if (isEpisode) parentIndexNumber else null,
                episode = if (isEpisode) indexNumber else null,
                watched = userData?.played,
            )
        }

        /** Nature d'un élément selon son `Type` (`BaseItemKind`) ; `null` si le type est absent. */
        internal fun mediaKind(type: String?): MediaKind? = when (type) {
            null, "" -> null
            "Movie" -> MediaKind.MOVIE
            "Series" -> MediaKind.SERIES
            "Season" -> MediaKind.SEASON
            "Episode" -> MediaKind.EPISODE
            "MusicArtist" -> MediaKind.ARTIST
            "MusicAlbum" -> MediaKind.ALBUM
            "Audio" -> MediaKind.SONG
            "TvChannel", "LiveTvChannel" -> MediaKind.CHANNEL
            "Folder", "CollectionFolder", "UserView", "BoxSet", "Playlist", "PhotoAlbum", "AggregateFolder",
            "ManualPlaylistsFolder", "PlaylistsFolder", "UserRootFolder", "Channel", "ChannelFolderItem",
            -> MediaKind.FOLDER
            else -> MediaKind.OTHER
        }

        /**
         * Clé d'image `<itemId>/<type>/<tag>` : image `Primary` de l'élément, sinon `Thumb`,
         * sinon premier `Backdrop` ; à défaut, pour un épisode ou une saison, l'affiche de la
         * série (`<seriesId>/Primary/<SeriesPrimaryImageTag>`, ou `<seriesId>/Primary` sans
         * étiquette connue). `null` : aucune image annoncée.
         */
        internal fun ItemDto.imageKey(): String? {
            val tags = imageTags.orEmpty()
            fun key(item: String, type: String, tag: String?) =
                if (tag.isNullOrBlank()) "$item/$type" else "$item/$type/$tag"
            tags["Primary"]?.let { return key(id, "Primary", it) }
            tags["Thumb"]?.let { return key(id, "Thumb", it) }
            backdropImageTags?.firstOrNull()?.let { return key(id, "Backdrop", it) }
            val series = seriesId?.takeIf { it.isNotBlank() } ?: return null
            if (type != "Episode" && type != "Season") return null
            return key(series, "Primary", seriesPrimaryImageTag)
        }

        /** Décompose une clé d'image ; `null` si elle est mal formée ou désigne un type non autorisé. */
        internal fun parseImageKey(key: String): Triple<String, String, String?>? {
            val parts = key.split('/')
            if (parts.size !in 2..3) return null
            val (itemId, type) = parts
            val tag = parts.getOrNull(2)
            if (!IMAGE_ID.matches(itemId) || type !in IMAGE_TYPES) return null
            if (tag != null && !IMAGE_ID.matches(tag)) return null
            return Triple(itemId, type, tag)
        }

        /** Champs demandés en plus des champs par défaut (images, `UserData`, année, numéros). */
        internal val ITEM_FIELDS = mapOf("Fields" to "PrimaryImageAspectRatio", "EnableUserData" to "true")

        /** Natures interrogeables et `BaseItemKind` correspondants (ARTIST : route `/Artists`). */
        internal val SEARCH_TYPES = linkedMapOf(
            MediaKind.MOVIE to "Movie",
            MediaKind.SERIES to "Series",
            MediaKind.EPISODE to "Episode",
            MediaKind.ARTIST to "MusicArtist",
            MediaKind.ALBUM to "MusicAlbum",
            MediaKind.SONG to "Audio",
            MediaKind.CHANNEL to "TvChannel",
        )

        /** Taille maximale d'une image reçue (4 Mio). */
        const val MAX_IMAGE_BYTES = 4L * 1024 * 1024
        private const val IMAGE_QUALITY = 85
        private const val MIN_IMAGE_WIDTH = 16
        private const val MAX_IMAGE_WIDTH = 3_840
        private const val TICKS_PER_MINUTE = 60_000L * TICKS_PER_MS
        private val IMAGE_TYPES = setOf("Primary", "Thumb", "Backdrop", "Banner", "Logo", "Art", "Disc")
        private val IMAGE_ID = Regex("^[A-Za-z0-9-]{1,128}$")

        private val BASE_PATH_CANDIDATES = MediaServerClient.DEFAULT_BASE_PATHS
        private val BASE_PATH = Regex("^(/[A-Za-z0-9._~-]+)+$")

        /** Chemin de base normalisé (`/a/b`), ou `null` s'il est vide ou invalide. */
        fun normalizeBasePath(raw: String?): String? {
            val trimmed = raw?.trim()?.trim('/')?.takeIf { it.isNotEmpty() } ?: return null
            return "/$trimmed".takeIf(BASE_PATH::matches)
        }

        /**
         * URL du serveur : [Device.baseUrl] impose `http` ; le schéma `https` annoncé à la
         * découverte (extra [MediaServerDiscovery.EXTRA_SCHEME]) est respecté.
         */
        fun baseUrl(device: Device): String {
            val root = if (device.extras[MediaServerDiscovery.EXTRA_SCHEME] == "https") {
                "https://" + device.baseUrl.removePrefix("http://")
            } else {
                device.baseUrl
            }
            return root + normalizeBasePath(device.extras[EXTRA_BASE_PATH]).orEmpty()
        }

        /** Description lisible des informations publiques du serveur (logique pure). */
        fun describe(kind: DeviceKind, info: PublicInfoDto): String {
            val product = info.productName?.takeIf { it.isNotBlank() } ?: kind.label
            val head = listOfNotNull(product, info.version?.takeIf { it.isNotBlank() }).joinToString(" ")
            return info.serverName?.takeIf { it.isNotBlank() }?.let { "$head – $it" } ?: head
        }

        fun create(device: Device, http: OkHttpClient, deviceId: String): MediaServerController {
            val flavor = if (device.kind == DeviceKind.EMBY) MediaServerFlavor.EMBY else MediaServerFlavor.JELLYFIN
            val client = MediaServerClient(http, baseUrl(device), flavor, deviceId, device.extras[EXTRA_TOKEN])
            return MediaServerController(device, client, ownDeviceId = deviceId)
        }
    }
}
