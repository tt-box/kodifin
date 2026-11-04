package io.github.jmorille.zapette.kodi

import io.github.jmorille.zapette.model.Chapter
import io.github.jmorille.zapette.model.Capability
import io.github.jmorille.zapette.model.DescribesServer
import io.github.jmorille.zapette.model.Device
import io.github.jmorille.zapette.model.MediaDetails
import io.github.jmorille.zapette.model.MediaEntry
import io.github.jmorille.zapette.model.MediaKind
import io.github.jmorille.zapette.model.MediaSection
import io.github.jmorille.zapette.model.MediaTrack
import io.github.jmorille.zapette.model.PlaybackState
import io.github.jmorille.zapette.model.PowerAction
import io.github.jmorille.zapette.model.RepeatMode
import io.github.jmorille.zapette.model.RemoteController
import io.github.jmorille.zapette.model.RemoteException
import io.github.jmorille.zapette.model.RemoteKey
import io.github.jmorille.zapette.model.SeriesContext
import io.github.jmorille.zapette.model.SupportsChapters
import io.github.jmorille.zapette.model.SupportsImages
import io.github.jmorille.zapette.model.SupportsLibrary
import io.github.jmorille.zapette.model.SupportsLibraryMaintenance
import io.github.jmorille.zapette.model.SupportsNotify
import io.github.jmorille.zapette.model.SupportsPlayMode
import io.github.jmorille.zapette.model.SupportsPower
import io.github.jmorille.zapette.model.SupportsQueue
import io.github.jmorille.zapette.model.SupportsSearch
import io.github.jmorille.zapette.model.SupportsSeekStep
import io.github.jmorille.zapette.model.SupportsSeries
import io.github.jmorille.zapette.model.SupportsSpeed
import io.github.jmorille.zapette.model.SupportsSyncOffset
import io.github.jmorille.zapette.model.SupportsTracks
import io.github.jmorille.zapette.model.SupportsUiCommands
import io.github.jmorille.zapette.model.UiCommand
import io.github.jmorille.zapette.model.UnsupportedActionException
import io.github.jmorille.zapette.model.requireKey
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlin.math.abs

/**
 * Télécommande Kodi. Les commandes passent par HTTP (`/jsonrpc`) ; l'état peut
 * aussi être alimenté par le WebSocket (port 9090) quand il est disponible.
 *
 * Avec un [eventServer] (option `kodi_eventserver`), les touches de navigation et de lecture
 * passent par l'Event Server UDP, plus réactif ; en cas d'échec d'envoi, repli sur JSON-RPC.
 * Volume, texte, lecture d'URL et état restent en JSON-RPC.
 *
 * Avec un transport qui pousse les notifications (WebSocket), l'état est relu à chaque
 * notification utile ([STATE_NOTIFICATIONS]) et, en secours, toutes les
 * [fallbackPollIntervalMs] (position de lecture) ; sinon il est interrogé toutes les [pollIntervalMs].
 *
 * Images ([SupportsImages]) : `imageFetcher` (clé `image://…` → octets), sinon celui du transport
 * HTTP ([KodiHttpTransport.images]) ; avec le WebSocket, l'appelant fournit un [KodiImageFetcher]
 * pointant vers le serveur web de l'appareil. Sans source d'images, [image] renvoie `null`.
 */
class KodiController(
    override val device: Device,
    private val client: KodiClient,
    private val pollIntervalMs: Long = 1_000,
    private val eventServer: KodiEventServer? = null,
    private val fallbackPollIntervalMs: Long = 10_000,
    imageFetcher: (suspend (String) -> ByteArray?)? = null,
) : RemoteController,
    DescribesServer,
    SupportsTracks,
    SupportsSeekStep,
    SupportsSpeed,
    SupportsSyncOffset,
    SupportsPlayMode,
    SupportsChapters,
    SupportsUiCommands,
    SupportsPower,
    SupportsNotify,
    SupportsLibraryMaintenance,
    SupportsLibrary,
    SupportsQueue,
    SupportsSeries,
    SupportsSearch,
    SupportsImages {

    private val library = KodiLibrary(client)

    private val images: (suspend (String) -> ByteArray?)? = imageFetcher ?: client.httpImages?.let { it::fetch }

    /** PVR détecté (à la connexion ou à la lecture des rubriques) : ajoute les chaînes à la recherche. */
    @Volatile
    private var pvrAvailable = false

    override val capabilities = Capability.entries.toSet()
    override val supportedKeys = RemoteKey.entries.toSet()

    override suspend fun connect() {
        // Un serveur qui répond 200 sans être Kodi (ou une réponse inattendue) ne doit
        // pas être considéré comme connecté.
        if (!client.ping()) throw RemoteException("L'appareil ne répond pas comme un serveur Kodi")
        // Version JSON-RPC lue une fois : la forme de Player.Seek a changé en v12 (Kodi 19).
        apiMajor = try {
            client.apiVersion().substringBefore('.').toIntOrNull()
        } catch (_: RemoteException) {
            null
        } catch (_: UnsupportedActionException) {
            null
        }
        // Chaînes TV proposées à la recherche seulement si le PVR répond (sans bloquer la connexion).
        pvrAvailable = try {
            library.hasPvr()
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }
    }

    /** Version majeure JSON-RPC lue à la connexion ; `null` (inconnue) = API récente supposée. */
    @Volatile
    internal var apiMajor: Int? = null

    /** JSON-RPC < v12 (Kodi ≤ 18) : `Player.Seek` sans `{step}`, `{seconds}` ni `{time}` imbriqué. */
    private val legacySeek: Boolean get() = apiMajor?.let { it < MODERN_SEEK_API } ?: false

    private suspend fun seekStep(playerId: Int, step: String) {
        if (legacySeek) client.seekStepLegacy(playerId, step) else client.seekStep(playerId, step)
    }

    /** « Kodi 21.2 (JSON-RPC 13.5.0) » ; sans `Application.GetProperties`, la version JSON-RPC seule. */
    override suspend fun serverDescription(): String {
        val api = client.apiVersion()
        val app = try {
            client.appInfo()
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
        val product = app?.let { listOfNotNull(it.name, it.version).joinToString(" ") } ?: "Kodi"
        return "$product (JSON-RPC $api)"
    }

    override suspend fun send(key: RemoteKey) {
        requireKey(key)
        val events = eventServer
        val button = EVENT_BUTTONS[key]
        if (events != null && button != null) {
            try {
                events.sendButton(button)
                return
            } catch (_: RemoteException) {
                // Échec UDP : repli sur JSON-RPC ci-dessous.
            }
        }
        sendJsonRpc(key)
    }

    private suspend fun sendJsonRpc(key: RemoteKey) {
        when (key) {
            RemoteKey.UP -> client.input(KodiMethod.INPUT_UP)
            RemoteKey.DOWN -> client.input(KodiMethod.INPUT_DOWN)
            RemoteKey.LEFT -> client.input(KodiMethod.INPUT_LEFT)
            RemoteKey.RIGHT -> client.input(KodiMethod.INPUT_RIGHT)
            RemoteKey.SELECT -> client.input(KodiMethod.INPUT_SELECT)
            RemoteKey.BACK -> client.input(KodiMethod.INPUT_BACK)
            RemoteKey.HOME -> client.input(KodiMethod.INPUT_HOME)
            RemoteKey.MENU -> client.input(KodiMethod.INPUT_CONTEXT_MENU)
            RemoteKey.INFO -> client.input(KodiMethod.INPUT_INFO)
            RemoteKey.VOLUME_UP -> client.stepVolume("increment")
            RemoteKey.VOLUME_DOWN -> client.stepVolume("decrement")
            RemoteKey.MUTE -> client.toggleMute()
            RemoteKey.PLAY_PAUSE -> withPlayer { client.playPause(it) }
            RemoteKey.STOP -> withPlayer { client.stop(it) }
            RemoteKey.NEXT -> withPlayer { client.goTo(it, next = true) }
            RemoteKey.PREVIOUS -> withPlayer { client.goTo(it, next = false) }
            RemoteKey.REWIND -> withPlayer { seekStep(it, "smallbackward") }
            RemoteKey.FAST_FORWARD -> withPlayer { seekStep(it, "smallforward") }
        }
    }

    override suspend fun playUrl(url: String, title: String?) {
        client.openUrl(url)
    }

    override suspend fun setVolume(percent: Int) {
        client.setVolume(percent)
    }

    override suspend fun seekTo(positionMs: Long) {
        withPlayer { client.seekTo(it, positionMs, legacy = legacySeek) }
    }

    override suspend fun sendText(text: String) {
        client.sendText(text)
    }

    override fun playbackState(): Flow<PlaybackState> =
        if (client.pushesNotifications) pushedPlaybackState() else polledPlaybackState()

    /** WebSocket : relecture à chaque notification d'état, interrogation lente de secours. */
    private fun pushedPlaybackState(): Flow<PlaybackState> = channelFlow {
        val triggers = Channel<Unit>(Channel.CONFLATED)
        // Abonnement immédiat (UNDISPATCHED) : aucune notification perdue avant le premier état.
        launch(start = CoroutineStart.UNDISPATCHED) {
            client.notifications.collect { if (it.method in STATE_NOTIFICATIONS) triggers.trySend(Unit) }
        }
        while (true) {
            send(snapshot())
            if (fallbackPollIntervalMs > 0) {
                withTimeoutOrNull(fallbackPollIntervalMs) { triggers.receive() }
            } else {
                triggers.receive()
            }
        }
    }

    private fun polledPlaybackState(): Flow<PlaybackState> = flow {
        while (true) {
            emit(snapshot())
            // delay(0) rend la main sans suspendre : yield() évite une boucle active non annulable.
            if (pollIntervalMs > 0) delay(pollIntervalMs) else yield()
        }
    }

    internal suspend fun snapshot(): PlaybackState {
        val app = client.appProperties()
        val player = client.activePlayers().firstOrNull()
            ?: return PlaybackState(volumePercent = app.volume, muted = app.muted)
        // Le lecteur peut s'arrêter entre deux appels : Kodi répond alors par une erreur
        // JSON-RPC, que l'on traduit en état « à l'arrêt » plutôt qu'en échec.
        val (props, item) = try {
            client.playerProperties(player.id) to client.playerItem(player.id)
        } catch (_: KodiRpcException) {
            return PlaybackState(volumePercent = app.volume, muted = app.muted)
        }
        // Épisode : série, saison et numéro lus dans le même Player.GetItem que le titre.
        val episode = item.type == EPISODE_TYPE
        return PlaybackState(
            title = item.label.ifBlank { null },
            isPlaying = props.speed != 0,
            positionMs = props.timeMs,
            durationMs = props.totalTimeMs,
            volumePercent = app.volume,
            muted = app.muted,
            seriesTitle = if (episode) item.showTitle else null,
            season = if (episode) item.season else null,
            episode = if (episode) item.episode else null,
            artist = item.artist,
            album = item.album,
            artworkKey = item.artwork,
            backdropKey = item.fanart,
        )
    }

    override suspend fun disconnect() {
        // Sans effet pour HTTP ; ferme la connexion et libère les requêtes en attente
        // pour un transport WebSocket. L'Event Server reçoit BYE.
        try {
            eventServer?.stop()
        } finally {
            client.close()
        }
    }

    // --- Pistes ----------------------------------------------------------

    override suspend fun mediaDetails(): MediaDetails {
        val player = client.activePlayers().firstOrNull() ?: return MediaDetails(speed = 0)
        val o = try {
            client.playerPropertiesOf(player.id, DETAIL_PROPERTIES)
        } catch (_: KodiRpcException) {
            return MediaDetails(speed = 0) // lecteur arrêté entre les deux appels
        }
        val subtitlesOn = o["subtitleenabled"].primitive()?.booleanOrNull ?: false
        val chapterInfo = chapterInfo(o["totaltime"]?.let(KodiClient::kodiTimeToMs) ?: 0)
        return MediaDetails(
            audioTracks = KodiLibrary.objects(o["audiostreams"]).mapNotNull { track(it, "Piste audio") },
            currentAudio = (o["currentaudiostream"] as? JsonObject)?.get("index").primitive()?.intOrNull,
            subtitles = KodiLibrary.objects(o["subtitles"]).mapNotNull { track(it, "Sous-titres") },
            currentSubtitle = if (subtitlesOn) (o["currentsubtitle"] as? JsonObject)?.get("index").primitive()?.intOrNull else null,
            repeat = when (o["repeat"].primitive()?.contentOrNull) {
                "off" -> RepeatMode.OFF
                "one" -> RepeatMode.ONE
                "all" -> RepeatMode.ALL
                else -> null
            },
            shuffle = o["shuffled"].primitive()?.booleanOrNull,
            speed = o["speed"].primitive()?.intOrNull ?: 0,
            chapterCount = chapterInfo.first,
            chapters = chapterInfo.second,
        )
    }

    /**
     * Kodi n'expose pas les chapitres dans `Player.GetProperties` : InfoLabels `Player.ChapterCount`
     * et `Player.Chapters` (débuts en pourcentage de la durée, séparés par des virgules ; Kodi 19+).
     */
    private suspend fun chapterInfo(durationMs: Long): Pair<Int, List<Chapter>> = try {
        val labels = client.infoLabels(listOf(CHAPTER_COUNT_LABEL, CHAPTERS_LABEL))
        val count = labels[CHAPTER_COUNT_LABEL]?.trim()?.toIntOrNull() ?: 0
        count to parseChapterPercents(labels[CHAPTERS_LABEL], durationMs)
    } catch (_: RemoteException) {
        0 to emptyList()
    } catch (_: UnsupportedActionException) {
        0 to emptyList()
    }

    private fun track(o: JsonObject, fallback: String): MediaTrack? {
        val index = o["index"].primitive()?.intOrNull ?: return null
        val name = o["name"].primitive()?.contentOrNull?.trim().orEmpty()
        val language = o["language"].primitive()?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
        val label = name.ifEmpty { language ?: "$fallback ${index + 1}" }
        return MediaTrack(index, label, language)
    }

    override suspend fun setAudioTrack(id: Int) = withPlayer { client.setAudioStream(it, JsonPrimitive(id)) }

    override suspend fun setSubtitle(id: Int?) = withPlayer {
        if (id == null) client.setSubtitle(it, JsonPrimitive("off")) else client.setSubtitle(it, JsonPrimitive(id), enable = true)
    }

    override suspend fun nextSubtitle() = withPlayer { client.setSubtitle(it, JsonPrimitive("next"), enable = true) }

    override suspend fun nextAudioTrack() = withPlayer { client.setAudioStream(it, JsonPrimitive("next")) }

    // --- Saut, vitesse, synchronisation, modes, chapitres ----------------

    override suspend fun seekBy(deltaMs: Long) {
        val seconds = deltaMs / 1000
        if (seconds == 0L) return
        // Repli sur les sauts prédéfinis (petit ≈ 30 s, grand ≈ 10 min par défaut dans Kodi).
        val step = (if (abs(seconds) >= BIG_STEP_SECONDS) "big" else "small") +
            (if (seconds > 0) "forward" else "backward")
        withPlayer { player ->
            if (legacySeek) {
                client.seekStepLegacy(player, step)
                return@withPlayer
            }
            try {
                client.seekSeconds(player, seconds)
            } catch (e: KodiRpcException) {
                // Serveur refusant `seconds` (paramètres invalides) : saut prédéfini.
                if (e.code != KodiClient.INVALID_PARAMS) throw e
                client.seekStep(player, step)
            }
        }
    }

    override suspend fun faster() = withPlayer { client.setSpeed(it, "increment") }

    override suspend fun slower() = withPlayer { client.setSpeed(it, "decrement") }

    override suspend fun subtitleDelay(later: Boolean) =
        client.executeAction(if (later) "subtitledelayplus" else "subtitledelayminus")

    override suspend fun audioDelay(later: Boolean) =
        client.executeAction(if (later) "audiodelayplus" else "audiodelayminus")

    override suspend fun setRepeat(mode: RepeatMode) = withPlayer {
        client.setRepeat(
            it,
            when (mode) {
                RepeatMode.OFF -> "off"
                RepeatMode.ONE -> "one"
                RepeatMode.ALL -> "all"
            },
        )
    }

    override suspend fun setShuffle(enabled: Boolean) = withPlayer { client.setShuffle(it, enabled) }

    override suspend fun nextChapter() = client.executeAction("chapterorbigstepforward")

    override suspend fun previousChapter() = client.executeAction("chapterorbigstepback")

    // --- Interface --------------------------------------------------------

    /** Kodi n'a pas de fenêtre de recherche globale publique : SEARCH n'est pas proposé. */
    override val uiCommands: Set<UiCommand> = UiCommand.entries.toSet() - UiCommand.SEARCH

    override suspend fun uiCommand(command: UiCommand) {
        when (command) {
            UiCommand.OSD -> client.input(KodiMethod.INPUT_SHOW_OSD)
            UiCommand.CONTEXT_MENU -> client.input(KodiMethod.INPUT_CONTEXT_MENU)
            UiCommand.CODEC_INFO -> try {
                client.input(KodiMethod.INPUT_SHOW_CODEC)
            } catch (_: UnsupportedActionException) {
                client.executeAction("playerprocessinfo")
            }
            UiCommand.FULLSCREEN -> client.setFullscreen("toggle")
            UiCommand.HOME_SCREEN -> client.activateWindow("home")
            UiCommand.SETTINGS -> client.activateWindow("settings")
            UiCommand.PAGE_UP -> client.executeAction("pageup")
            UiCommand.PAGE_DOWN -> client.executeAction("pagedown")
            UiCommand.CHANNEL_UP -> client.executeAction("channelup")
            UiCommand.CHANNEL_DOWN -> client.executeAction("channeldown")
            UiCommand.GUIDE -> client.activateWindow("tvguide")
            UiCommand.SEARCH -> throw UnsupportedActionException("Recherche non prise en charge par Kodi")
        }
    }

    // --- Alimentation, notification, maintenance ------------------------

    override suspend fun availablePowerActions(): Set<PowerAction> {
        val props = client.systemProperties(POWER_PROPERTIES.values.toList())
        return POWER_PROPERTIES.filterValues { props[it] == true }.keys + PowerAction.QUIT_APP
    }

    override suspend fun power(action: PowerAction) {
        client.systemAction(
            when (action) {
                PowerAction.SUSPEND -> KodiMethod.SYSTEM_SUSPEND
                PowerAction.HIBERNATE -> KodiMethod.SYSTEM_HIBERNATE
                PowerAction.SHUTDOWN -> KodiMethod.SYSTEM_SHUTDOWN
                PowerAction.REBOOT -> KodiMethod.SYSTEM_REBOOT
                PowerAction.QUIT_APP -> KodiMethod.APP_QUIT
            },
        )
    }

    override suspend fun showMessage(title: String, text: String) {
        client.showNotification(title, text)
    }

    override suspend fun scanLibrary() {
        client.scanVideoLibrary()
        client.scanAudioLibrary()
    }

    override suspend fun cleanLibrary() {
        client.cleanVideoLibrary()
        client.cleanAudioLibrary()
    }

    // --- Bibliothèque -----------------------------------------------------

    override suspend fun sections(): List<MediaSection> =
        library.sections().also { list -> pvrAvailable = list.any { it.id == KodiLibrary.SECTION_TV } }

    override suspend fun browse(parentId: String, offset: Int, limit: Int): List<MediaEntry> =
        library.browse(parentId, offset, limit)

    override suspend fun play(entryId: String) = library.play(entryId)

    override suspend fun enqueue(entryId: String, next: Boolean) {
        val (item, target) = library.playlistItem(entryId)
        val active = activePlaylist()
        val playlist = target ?: active.playlistId
        val position = active.position
        if (next && active.playerId != null && active.playlistId == playlist && position != null) {
            client.playlistInsert(playlist, position + 1, item)
        } else {
            client.playlistAdd(playlist, item)
        }
    }

    // --- File d'attente ---------------------------------------------------

    /** Playlist du lecteur actif (vidéo par défaut sans lecteur) et position en cours. */
    private data class ActivePlaylist(val playerId: Int?, val playlistId: Int, val position: Int?)

    private suspend fun activePlaylist(): ActivePlaylist {
        val player = client.activePlayers().firstOrNull()
            ?: return ActivePlaylist(null, KodiLibrary.VIDEO_PLAYLIST, null)
        val byType = if (player.type == "audio") KodiLibrary.AUDIO_PLAYLIST else KodiLibrary.VIDEO_PLAYLIST
        val o = try {
            client.playerPropertiesOf(player.id, listOf("playlistid", "position"))
        } catch (_: KodiRpcException) {
            return ActivePlaylist(null, byType, null)
        }
        return ActivePlaylist(
            player.id,
            o["playlistid"].primitive()?.intOrNull?.takeIf { it >= 0 } ?: byType,
            o["position"].primitive()?.intOrNull?.takeIf { it >= 0 },
        )
    }

    override suspend fun queue(): List<MediaEntry> {
        val playlist = activePlaylist().playlistId
        return client.playlistItems(playlist, KodiLibrary.QUEUE_PROPERTIES).mapIndexedNotNull { index, element ->
            (element as? JsonObject)?.let { KodiLibrary.queueEntry(it, index) }
        }
    }

    override suspend fun queuePosition(): Int? = activePlaylist().position

    override suspend fun playQueueItem(index: Int) {
        val active = activePlaylist()
        if (active.playerId != null) {
            client.goToPosition(active.playerId, index)
        } else {
            client.open(buildJsonObject { put("playlistid", active.playlistId); put("position", index) })
        }
    }

    override suspend fun removeQueueItem(index: Int) {
        client.playlistRemove(activePlaylist().playlistId, index)
    }

    override suspend fun clearQueue() {
        client.playlistClear(activePlaylist().playlistId)
    }

    // --- Série en cours, recherche, images ---------------------------------

    /**
     * Série de l'épisode en cours : `Player.GetItem` (type `episode`, `tvshowid`, `season`, `id`)
     * puis `VideoLibrary.GetSeasons`. Les épisodes d'une saison se lisent par [browse] (`season:<série>:<n>`).
     */
    override suspend fun currentSeries(): SeriesContext? {
        val player = client.activePlayers().firstOrNull { it.type == "video" } ?: return null
        val item = try {
            client.playerItem(player.id)
        } catch (_: KodiRpcException) {
            return null // lecteur arrêté entre les deux appels
        }
        if (item.type != EPISODE_TYPE) return null
        val show = item.tvShowId ?: return null
        val seasons = library.seasons(show, 0, MAX_SEASONS)
        return SeriesContext(
            seriesId = KodiEntryId("tvshowid", show.toString()).toString(),
            title = item.showTitle ?: seasons.firstNotNullOfOrNull { it.subtitle } ?: item.label,
            seasons = seasons,
            currentSeasonId = item.season?.let { KodiEntryId("season", "$show:$it").toString() },
            currentEpisodeId = item.id?.let { KodiEntryId("episodeid", it.toString()).toString() },
        )
    }

    /** Films, séries, épisodes, artistes, albums, titres ; chaînes TV si le PVR est disponible. */
    override val searchKinds: Set<MediaKind>
        get() = if (pvrAvailable) SEARCH_KINDS + MediaKind.CHANNEL else SEARCH_KINDS

    override suspend fun search(query: String, kinds: Set<MediaKind>, limit: Int): List<MediaEntry> {
        val available = searchKinds
        return library.search(query, if (kinds.isEmpty()) available else kinds intersect available, limit)
    }

    /**
     * [imageKey] : chemin d'image Kodi (`image://…`), téléchargé sur le serveur web de l'appareil.
     * [maxWidth] est ignoré : `/image/` ne redimensionne pas de façon garantie (l'image est celle
     * du cache de Kodi) ; la mise à l'échelle se fait à l'affichage.
     */
    override suspend fun image(imageKey: String, maxWidth: Int): ByteArray? {
        if (!imageKey.startsWith(KodiImageFetcher.IMAGE_SCHEME)) return null
        return images?.invoke(imageKey)
    }

    private suspend fun withPlayer(action: suspend (Int) -> Unit) {
        val player = client.activePlayers().firstOrNull() ?: return
        action(player.id)
    }

    companion object {
        const val EXTRA_USERNAME = "username"
        const val EXTRA_PASSWORD = "password"

        internal val DETAIL_PROPERTIES = listOf(
            "speed", "repeat", "shuffled",
            "audiostreams", "currentaudiostream",
            "subtitles", "currentsubtitle", "subtitleenabled", "totaltime",
        )
        internal const val CHAPTER_COUNT_LABEL = "Player.ChapterCount"
        internal const val CHAPTERS_LABEL = "Player.Chapters"

        /** `"0.00,12.5,40"` → débuts en ms ; vide si la durée est inconnue ou la valeur illisible. */
        internal fun parseChapterPercents(raw: String?, durationMs: Long): List<Chapter> {
            if (raw.isNullOrBlank() || durationMs <= 0) return emptyList()
            return raw.split(',')
                .mapNotNull { it.trim().toDoubleOrNull() }
                .filter { it.isFinite() && it in 0.0..100.0 }
                .map { Chapter((durationMs * it / 100.0).toLong()) }
                .distinctBy { it.startMs }
                .sortedBy { it.startMs }
        }
        internal const val EPISODE_TYPE = "episode"

        /** Saisons lues au plus pour [currentSeries]. */
        internal const val MAX_SEASONS = 500

        /** Natures toujours interrogeables (CHANNEL s'y ajoute si le PVR répond). */
        internal val SEARCH_KINDS = setOf(
            MediaKind.MOVIE, MediaKind.SERIES, MediaKind.EPISODE, MediaKind.ARTIST, MediaKind.ALBUM, MediaKind.SONG,
        )

        /** Première version JSON-RPC (Kodi 19) acceptant `Player.Seek {value:{step|seconds|time}}`. */
        internal const val MODERN_SEEK_API = 12

        /** Notifications Kodi qui déclenchent une relecture de l'état (WebSocket). */
        internal val STATE_NOTIFICATIONS = setOf(
            "Player.OnPlay", "Player.OnPause", "Player.OnResume", "Player.OnStop",
            "Player.OnSeek", "Player.OnSpeedChanged", "Player.OnAVChange", "Player.OnAVStart",
            "Player.OnPropertyChanged",
            "Application.OnVolumeChanged",
            "Playlist.OnAdd", "Playlist.OnRemove", "Playlist.OnClear",
            KodiWebSocketTransport.RECONNECTED,
        )

        /** Seuil de repli vers le grand saut (`bigforward`/`bigbackward`). */
        private const val BIG_STEP_SECONDS = 300L

        /** Action d'alimentation → propriété `System.GetProperties` qui l'autorise. */
        internal val POWER_PROPERTIES = linkedMapOf(
            PowerAction.SHUTDOWN to "canshutdown",
            PowerAction.SUSPEND to "cansuspend",
            PowerAction.HIBERNATE to "canhibernate",
            PowerAction.REBOOT to "canreboot",
        )

        /**
         * Touches envoyées par l'Event Server, désignées par leur nom dans la table clavier
         * (`KB`) de Kodi, dont les actions par défaut (keyboard.xml) correspondent aux appels
         * JSON-RPC équivalents. HOME reste en JSON-RPC (pas de touche clavier dédiée par défaut).
         */
        internal val EVENT_BUTTONS: Map<RemoteKey, String> = mapOf(
            RemoteKey.UP to "up",
            RemoteKey.DOWN to "down",
            RemoteKey.LEFT to "left",
            RemoteKey.RIGHT to "right",
            RemoteKey.SELECT to "enter",
            RemoteKey.BACK to "backspace",
            RemoteKey.MENU to "c",
            RemoteKey.INFO to "i",
            RemoteKey.PLAY_PAUSE to "space",
            RemoteKey.STOP to "x",
            RemoteKey.NEXT to "period",
            RemoteKey.PREVIOUS to "comma",
            RemoteKey.REWIND to "r",
            RemoteKey.FAST_FORWARD to "f",
        )
    }
}
