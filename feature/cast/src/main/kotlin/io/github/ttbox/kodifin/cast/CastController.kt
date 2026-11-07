package io.github.ttbox.kodifin.cast

import io.github.ttbox.kodifin.model.TransferableMedia
import io.github.ttbox.kodifin.model.SupportsTransferOut
import io.github.ttbox.kodifin.model.UnsupportedAction
import io.github.ttbox.kodifin.model.UnsupportedActionException
import io.github.ttbox.kodifin.model.SupportsTracks
import io.github.ttbox.kodifin.model.SupportsSpeed
import io.github.ttbox.kodifin.model.SupportsSeekStep
import io.github.ttbox.kodifin.model.SupportsQueue
import io.github.ttbox.kodifin.model.SupportsPower
import io.github.ttbox.kodifin.model.SupportsPlayMode
import io.github.ttbox.kodifin.model.SupportsImages
import io.github.ttbox.kodifin.model.RepeatMode
import io.github.ttbox.kodifin.model.PowerAction
import io.github.ttbox.kodifin.model.MediaTrack
import io.github.ttbox.kodifin.model.MediaEntry
import io.github.ttbox.kodifin.model.MediaDetails
import android.content.Context
import io.github.ttbox.kodifin.model.Capability
import io.github.ttbox.kodifin.model.Device
import io.github.ttbox.kodifin.model.PlaybackState
import io.github.ttbox.kodifin.model.RemoteController
import io.github.ttbox.kodifin.model.RemoteError
import io.github.ttbox.kodifin.model.RemoteException
import io.github.ttbox.kodifin.model.RemoteKey
import io.github.ttbox.kodifin.model.requireKey
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.cancellation.CancellationException

/**
 * Chromecast / Google TV via le récepteur multimédia par défaut. Tous les appels SDK passent par
 * [main] (le thread principal en production) ; le SDK est atteint via [CastPlatform].
 * [settings] est relu à chaque usage : un réglage modifié s'applique sans recréer le contrôleur.
 */
class CastController internal constructor(
    override val device: Device,
    private val platform: CastPlatform,
    private val main: CoroutineContext,
    private val sessionTimeoutMs: Long = SESSION_TIMEOUT_MS,
    private val settings: suspend () -> CastSettings = { CastSettings() },
    /** Téléchargement des pochettes (adresses publiées par l'application émettrice) ; `null` : sans image. */
    private val imageFetcher: (suspend (String) -> ByteArray?)? = null,
) : RemoteController, SupportsTracks, SupportsSeekStep, SupportsSpeed, SupportsPlayMode, SupportsQueue,
    SupportsImages, SupportsPower, SupportsTransferOut {

    constructor(
        device: Device,
        context: Context,
        settings: suspend () -> CastSettings = { CastSettings() },
        imageFetcher: (suspend (String) -> ByteArray?)? = null,
    ) : this(device, SdkCastPlatform(context), Dispatchers.Main, SESSION_TIMEOUT_MS, settings, imageFetcher)

    /** Réglages courants ; une lecture impossible (stockage illisible) retombe sur les valeurs par défaut. */
    private suspend fun currentSettings(): CastSettings = try {
        settings()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        CastSettings()
    }

    override val capabilities = setOf(
        Capability.PLAYBACK, Capability.VOLUME, Capability.SEEK, Capability.PLAY_URL, Capability.NOW_PLAYING,
    )
    override val supportedKeys = setOf(
        RemoteKey.PLAY_PAUSE, RemoteKey.STOP, RemoteKey.NEXT, RemoteKey.PREVIOUS,
        RemoteKey.REWIND, RemoteKey.FAST_FORWARD, RemoteKey.VOLUME_UP, RemoteKey.VOLUME_DOWN, RemoteKey.MUTE,
    )

    /** Évite que deux appels simultanés (état de lecture + touche) sélectionnent la route deux fois. */
    private val sessionLock = Mutex()

    /** Route de l'appareil : par identifiant (découverte) ou, pour un appareil saisi à la main, par adresse IP. */
    internal fun route(): CastRoute? = platform.routes().let { routes ->
        routes.firstOrNull { it.id == device.id }
            ?: routes.firstOrNull { CastMedia.sameHost(it.host, device.host) }
    }

    /** La session vise-t-elle cet appareil (et non un autre Chromecast) ? */
    internal fun isOurs(session: CastSessionPort?): Boolean {
        val id = session?.deviceId ?: return false
        return id == route()?.deviceId
    }

    /**
     * Un événement de l'appareil [eventDeviceId] concerne-t-il la session attendue ([expectedId]) ?
     * Identifiant inconnu d'un côté ou de l'autre : on ne peut pas trancher, l'événement est retenu
     * (comportement du SDK pour une session encore sans appareil).
     */
    internal fun concerns(eventDeviceId: String?, expectedId: String?): Boolean =
        expectedId == null || eventDeviceId == null || eventDeviceId == expectedId

    private suspend fun session(): CastSessionPort = sessionLock.withLock {
        withContext(main) {
            translating {
                platform.ready()
                platform.currentSession()?.takeIf { it.isConnected && isOurs(it) }?.let { return@translating it }
                openSession()
            }
        }
    }

    private suspend fun openSession(): CastSessionPort {
        val current = platform.currentSession()
        // Une session vers cet appareil déjà en cours d'ouverture : on l'attend sans resélectionner.
        val waitCurrent = current != null && current.isConnecting && isOurs(current)
        val target = if (waitCurrent) null else route() ?: throw RemoteException(RemoteError.Unreachable(device.host.ifBlank { null }))
        val expectedId = target?.deviceId ?: current?.deviceId

        val started = CompletableDeferred<CastSessionPort>()
        val listening = platform.listen(object : CastSessionEvents {
            override fun started(session: CastSessionPort) {
                // Une session ouverte entre-temps vers un autre appareil ne doit pas recevoir nos commandes.
                if (concerns(session.deviceId, expectedId)) started.complete(session)
            }

            override fun failed(deviceId: String?, code: Int, detail: String?) {
                // L'échec de la session d'un autre Chromecast (autre écran) n'interrompt pas notre attente.
                if (concerns(deviceId, expectedId)) {
                    started.completeExceptionally(RemoteException(RemoteError.DeviceRefused(detail, code.toString())))
                }
            }

            override fun ended(deviceId: String?) {
                // Fin de l'éventuelle session précédente d'un autre appareil : sans effet ; la nôtre
                // qui se termine avant d'avoir démarré est un échec.
                if (expectedId != null && deviceId == expectedId) {
                    started.completeExceptionally(RemoteException(RemoteError.SessionEnded))
                }
            }
        })
        try {
            target?.let(platform::selectRoute)
            try {
                return withTimeout(sessionTimeoutMs) { started.await() }
            } catch (e: TimeoutCancellationException) {
                runCatching { platform.endCurrentSession() }
                throw RemoteException(RemoteError.Timeout, e)
            }
        } finally {
            runCatching { listening.close() }
        }
    }

    /** Traduit les erreurs du SDK en [RemoteException]. */
    private inline fun <T> translating(block: () -> T): T = try {
        block()
    } catch (e: IOException) {
        throw RemoteException(RemoteError.Interrupted(device.host.ifBlank { null }), e)
    } catch (e: IllegalStateException) {
        throw RemoteException(RemoteError.Interrupted(device.host.ifBlank { null }), e)
    }

    /** Exécute [block] sur le thread principal et traduit les erreurs du SDK en [RemoteException]. */
    private suspend fun <T> onMain(block: suspend (CastSessionPort) -> T): T {
        val s = session()
        return withContext(main) { translating { block(s) } }
    }

    private fun CastSessionPort.media(): CastMediaPort =
        media ?: throw RemoteException(RemoteError.NothingPlaying)

    override suspend fun connect() {
        session()
        // Un média déjà en cours (lancé par une autre application) reçoit le style choisi ;
        // un échec ici n'empêche pas la télécommande de fonctionner.
        try {
            applySubtitleStyle()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Style non appliqué : sans conséquence sur les commandes.
        }
    }

    /** Applique le style de sous-titres des réglages au média en cours, s'il y en a un. */
    suspend fun applySubtitleStyle() {
        val style = currentSettings().subtitles
        onMain { s -> s.media?.setTextTrackStyle(style) }
    }

    override suspend fun send(key: RemoteKey) {
        requireKey(key)
        onMain { s ->
            when (key) {
                RemoteKey.PLAY_PAUSE -> s.media().togglePlayback()
                RemoteKey.STOP -> s.media().stop()
                RemoteKey.NEXT -> s.media().next()
                RemoteKey.PREVIOUS -> s.media().previous()
                RemoteKey.REWIND, RemoteKey.FAST_FORWARD -> s.media().let {
                    val delta = if (key == RemoteKey.REWIND) -SEEK_STEP_MS else SEEK_STEP_MS
                    it.seek(CastMedia.clampSeek(it.positionMs + delta, it.durationMs))
                }
                RemoteKey.VOLUME_UP -> s.volume = CastMedia.stepVolume(s.volume, VOLUME_STEP)
                RemoteKey.VOLUME_DOWN -> s.volume = CastMedia.stepVolume(s.volume, -VOLUME_STEP)
                RemoteKey.MUTE -> s.isMute = !s.isMute
                else -> Unit
            }
        }
    }

    override suspend fun playUrl(url: String, title: String?) {
        val options = CastLoadOptions.from(currentSettings())
        onMain { s -> s.media().load(url, CastMedia.contentType(url), title ?: url.substringAfterLast('/'), options) }
    }

    override suspend fun setVolume(percent: Int) {
        onMain { it.volume = percent.coerceIn(0, 100) / 100.0 }
    }

    override suspend fun seekTo(positionMs: Long) {
        onMain { s ->
            val media = s.media()
            media.seek(CastMedia.clampSeek(positionMs, media.durationMs))
        }
    }

    override fun playbackState(): Flow<PlaybackState> = flow {
        val s = session()
        // Fin de NOTRE session signalée par le SDK : l'état est mis à jour sans attendre le
        // prochain relevé (la fin de la session d'un autre Chromecast est ignorée).
        val ended = CompletableDeferred<Unit>()
        val expectedId = s.deviceId
        val listening = withContext(main) {
            platform.listen(object : CastSessionEvents {
                override fun started(session: CastSessionPort) = Unit
                override fun failed(deviceId: String?, code: Int, detail: String?) = Unit
                override fun ended(deviceId: String?) {
                    if (expectedId != null && deviceId == expectedId) ended.complete(Unit)
                }
            })
        }
        try {
            pollPlayback(s, ended)
        } finally {
            withContext(main) { runCatching { listening.close() } }
        }
    }.flowOn(main)

    private suspend fun FlowCollector<PlaybackState>.pollPlayback(
        s: CastSessionPort,
        ended: CompletableDeferred<Unit>,
    ) {
        while (true) {
            // Le SDK lève IllegalStateException sur volume/isMute une fois la session fermée.
            if (ended.isCompleted || !s.isConnected) throw RemoteException(RemoteError.SessionEnded)
            val media = s.media
            emit(
                translating {
                    val meta = media?.nowPlaying
                    PlaybackState(
                        title = meta?.title ?: media?.title,
                        isPlaying = media?.isPlaying == true,
                        positionMs = media?.positionMs ?: 0,
                        durationMs = media?.durationMs ?: 0,
                        volumePercent = (s.volume * 100).toInt(),
                        muted = s.isMute,
                        seriesTitle = meta?.seriesTitle,
                        season = meta?.season,
                        episode = meta?.episode,
                        artist = meta?.artist ?: meta?.subtitle?.takeIf { meta.seriesTitle == null },
                        album = meta?.album,
                        appName = s.appName,
                        artworkKey = meta?.imageUrl?.takeIf { imageFetcher != null },
                        backdropKey = meta?.backdropUrl?.takeIf { imageFetcher != null },
                    )
                },
            )
            // Attente du prochain relevé, écourtée par la fin de la session.
            withTimeoutOrNull(POLL_INTERVAL_MS) { ended.await() }
        }
    }

    /**
     * Appelé par l'application en quittant la télécommande : ferme la session de cet appareil
     * si le réglage « déconnexion automatique » est actif, sinon la laisse ouverte (la lecture
     * continue sur le Chromecast).
     */
    override suspend fun disconnect() {
        if (currentSettings().autoDisconnect) endSession()
    }

    /** Ne ferme que la session de cet appareil (pas celle qu'un autre écran aurait ouverte). */
    suspend fun endSession() {
        withContext(main) {
            runCatching {
                platform.ready()
                if (isOurs(platform.currentSession())) platform.endCurrentSession()
            }
        }
    }

    // --- Pistes, vitesse, modes ---------------------------------------------------------------

    override suspend fun mediaDetails(): MediaDetails = onMain { s ->
        val media = s.media ?: return@onMain MediaDetails(speed = 0)
        val tracks = media.tracks
        val active = media.activeTrackIds
        fun list(type: CastTrackType) = tracks.filter { it.type == type }.mapIndexed { i, t ->
            MediaTrack(t.id.toInt(), t.name?.takeIf { it.isNotBlank() } ?: t.language ?: "Piste ${i + 1}", t.language)
        }
        val audio = list(CastTrackType.AUDIO)
        val text = list(CastTrackType.TEXT)
        MediaDetails(
            audioTracks = audio,
            currentAudio = audio.firstOrNull { it.id.toLong() in active }?.id,
            subtitles = text,
            currentSubtitle = text.firstOrNull { it.id.toLong() in active }?.id,
            repeat = when (media.repeat) {
                CastRepeat.OFF -> RepeatMode.OFF
                CastRepeat.SINGLE -> RepeatMode.ONE
                CastRepeat.ALL, CastRepeat.ALL_AND_SHUFFLE -> RepeatMode.ALL
            },
            shuffle = media.repeat == CastRepeat.ALL_AND_SHUFFLE,
            speed = if (media.isPlaying) 1 else 0,
            playbackRate = media.playbackRate,
        )
    }

    /** Remplace, parmi les pistes actives, celles de nature [type] par [id] (aucune si `null`). */
    private suspend fun selectTrack(type: CastTrackType, id: Long?) = onMain { s ->
        val media = s.media()
        val ofType = media.tracks.filter { it.type == type }.map { it.id }.toSet()
        media.setActiveTracks(media.activeTrackIds - ofType + setOfNotNull(id))
    }

    /** Piste suivante de nature [type] ; pour les sous-titres, « désactivés » fait partie du cycle. */
    private suspend fun cycleTrack(type: CastTrackType, allowNone: Boolean) {
        val (ids, current) = onMain { s ->
            val media = s.media()
            val ids = media.tracks.filter { it.type == type }.map { it.id }
            ids to ids.firstOrNull { it in media.activeTrackIds }
        }
        if (ids.isEmpty()) return
        val cycle: List<Long?> = if (allowNone) listOf<Long?>(null) + ids else ids
        selectTrack(type, cycle[(cycle.indexOf(current) + 1) % cycle.size])
    }

    override suspend fun setAudioTrack(id: Int) = selectTrack(CastTrackType.AUDIO, id.toLong())
    override suspend fun setSubtitle(id: Int?) = selectTrack(CastTrackType.TEXT, id?.toLong())
    override suspend fun nextSubtitle() = cycleTrack(CastTrackType.TEXT, allowNone = true)
    override suspend fun nextAudioTrack() = cycleTrack(CastTrackType.AUDIO, allowNone = false)

    override suspend fun seekBy(deltaMs: Long) = onMain { s ->
        val media = s.media()
        media.seek(CastMedia.clampSeek(media.positionMs + deltaMs, media.durationMs))
    }

    override suspend fun faster() = stepRate(+1)
    override suspend fun slower() = stepRate(-1)

    private suspend fun stepRate(direction: Int) = onMain { s ->
        val media = s.media()
        media.setPlaybackRate(nextRate(media.playbackRate, direction))
    }

    override suspend fun setRepeat(mode: RepeatMode) = onMain { s ->
        val media = s.media()
        val shuffle = media.repeat == CastRepeat.ALL_AND_SHUFFLE
        media.setRepeat(
            when (mode) {
                RepeatMode.OFF -> CastRepeat.OFF
                RepeatMode.ONE -> CastRepeat.SINGLE
                RepeatMode.ALL -> if (shuffle) CastRepeat.ALL_AND_SHUFFLE else CastRepeat.ALL
            },
        )
    }

    /** Cast ne connaît l'aléatoire qu'avec la répétition de toute la file. */
    override suspend fun setShuffle(enabled: Boolean) = onMain { s ->
        s.media().setRepeat(if (enabled) CastRepeat.ALL_AND_SHUFFLE else CastRepeat.ALL)
    }

    // --- File du récepteur --------------------------------------------------------------------

    override suspend fun queue(): List<MediaEntry> = onMain { s ->
        s.media?.queueItems.orEmpty().map { item ->
            MediaEntry(
                id = item.itemId.toString(),
                title = item.title?.takeIf { it.isNotBlank() } ?: "Élément ${item.itemId}",
                subtitle = item.subtitle,
                imageKey = item.imageUrl.takeIf { imageFetcher != null },
            )
        }
    }

    override suspend fun queuePosition(): Int? = onMain { s ->
        val media = s.media ?: return@onMain null
        media.queueItems.indexOfFirst { it.itemId == media.currentItemId }.takeIf { it >= 0 }
    }

    private suspend fun queueItemId(index: Int): Int = onMain { s ->
        s.media().queueItems.getOrNull(index)?.itemId ?: throw RemoteException(RemoteError.ItemNotFound)
    }

    override suspend fun playQueueItem(index: Int) {
        val id = queueItemId(index)
        onMain { it.media().jumpTo(id) }
    }

    override suspend fun removeQueueItem(index: Int) {
        val id = queueItemId(index)
        onMain { it.media().removeItems(listOf(id)) }
    }

    /** Vide la file en gardant l'élément en cours de lecture. */
    override suspend fun clearQueue() = onMain { s ->
        val media = s.media()
        media.removeItems(media.queueItems.map { it.itemId }.filter { it != media.currentItemId })
    }

    /** Média en cours s'il est désigné par une adresse HTTP (pas un identifiant propre à l'application). */
    override suspend fun currentMedia(): TransferableMedia? = onMain { s ->
        val media = s.media ?: return@onMain null
        val url = media.contentUrl?.takeIf { it.startsWith("http://") || it.startsWith("https://") } ?: return@onMain null
        TransferableMedia(url, media.nowPlaying?.title ?: media.title, media.contentType, media.positionMs)
    }

    // --- Images et fin de diffusion -----------------------------------------------------------

    override suspend fun image(imageKey: String, maxWidth: Int): ByteArray? = imageFetcher?.invoke(imageKey)

    override suspend fun availablePowerActions(): Set<PowerAction> = setOf(PowerAction.QUIT_APP)

    /** « Quitter l'application » : arrête la diffusion (ferme la session de cet appareil). */
    override suspend fun power(action: PowerAction) {
        if (action != PowerAction.QUIT_APP) throw UnsupportedActionException(UnsupportedAction.Power(action), device.kind)
        endSession()
    }

    companion object {
        /** Vitesses proposées par le récepteur multimédia par défaut (0,5 à 2). */
        internal val RATES = listOf(0.5, 0.75, 1.0, 1.25, 1.5, 2.0)

        /** Vitesse voisine de [rate] dans [RATES] ([direction] = +1 plus vite, -1 plus lent). */
        internal fun nextRate(rate: Double, direction: Int): Double {
            val index = RATES.indexOfFirst { it >= rate - 0.01 }.let { if (it < 0) RATES.lastIndex else it }
            val exact = kotlin.math.abs(RATES[index] - rate) < 0.01
            val target = if (direction > 0) (if (exact) index + 1 else index) else index - 1
            return RATES[target.coerceIn(0, RATES.lastIndex)]
        }

        private const val SEEK_STEP_MS = 10_000L
        private const val VOLUME_STEP = 0.05
        private const val SESSION_TIMEOUT_MS = 15_000L
        private const val POLL_INTERVAL_MS = 1_000L
    }
}
