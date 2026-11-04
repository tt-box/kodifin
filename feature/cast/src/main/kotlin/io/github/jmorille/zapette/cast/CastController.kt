package io.github.jmorille.zapette.cast

import android.content.Context
import io.github.jmorille.zapette.model.Capability
import io.github.jmorille.zapette.model.Device
import io.github.jmorille.zapette.model.PlaybackState
import io.github.jmorille.zapette.model.RemoteController
import io.github.jmorille.zapette.model.RemoteException
import io.github.jmorille.zapette.model.RemoteKey
import io.github.jmorille.zapette.model.requireKey
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
) : RemoteController {

    constructor(device: Device, context: Context, settings: suspend () -> CastSettings = { CastSettings() }) :
        this(device, SdkCastPlatform(context), Dispatchers.Main, SESSION_TIMEOUT_MS, settings)

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
        val target = if (waitCurrent) null else route() ?: throw RemoteException("${device.name} n'est plus visible sur le réseau")
        val expectedId = target?.deviceId ?: current?.deviceId

        val started = CompletableDeferred<CastSessionPort>()
        val listening = platform.listen(object : CastSessionEvents {
            override fun started(session: CastSessionPort) {
                // Une session ouverte entre-temps vers un autre appareil ne doit pas recevoir nos commandes.
                if (concerns(session.deviceId, expectedId)) started.complete(session)
            }

            override fun failed(deviceId: String?, message: String) {
                // L'échec de la session d'un autre Chromecast (autre écran) n'interrompt pas notre attente.
                if (concerns(deviceId, expectedId)) started.completeExceptionally(RemoteException(message))
            }

            override fun ended(deviceId: String?) {
                // Fin de l'éventuelle session précédente d'un autre appareil : sans effet ; la nôtre
                // qui se termine avant d'avoir démarré est un échec.
                if (expectedId != null && deviceId == expectedId) {
                    started.completeExceptionally(RemoteException("Session Cast terminée par ${device.name}"))
                }
            }
        })
        try {
            target?.let(platform::selectRoute)
            try {
                return withTimeout(sessionTimeoutMs) { started.await() }
            } catch (e: TimeoutCancellationException) {
                runCatching { platform.endCurrentSession() }
                throw RemoteException("${device.name} ne répond pas (délai de connexion Cast dépassé)", e)
            }
        } finally {
            runCatching { listening.close() }
        }
    }

    /** Traduit les erreurs du SDK en [RemoteException]. */
    private inline fun <T> translating(block: () -> T): T = try {
        block()
    } catch (e: IOException) {
        throw RemoteException("Commande Cast impossible", e)
    } catch (e: IllegalStateException) {
        throw RemoteException("Session Cast interrompue", e)
    }

    /** Exécute [block] sur le thread principal et traduit les erreurs du SDK en [RemoteException]. */
    private suspend fun <T> onMain(block: suspend (CastSessionPort) -> T): T {
        val s = session()
        return withContext(main) { translating { block(s) } }
    }

    private fun CastSessionPort.media(): CastMediaPort =
        media ?: throw RemoteException("Aucun lecteur actif sur ${device.name}")

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
                override fun failed(deviceId: String?, message: String) = Unit
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
            if (ended.isCompleted || !s.isConnected) throw RemoteException("Session Cast terminée")
            val media = s.media
            emit(
                translating {
                    PlaybackState(
                        title = media?.title,
                        isPlaying = media?.isPlaying == true,
                        positionMs = media?.positionMs ?: 0,
                        durationMs = media?.durationMs ?: 0,
                        volumePercent = (s.volume * 100).toInt(),
                        muted = s.isMute,
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

    companion object {
        private const val SEEK_STEP_MS = 10_000L
        private const val VOLUME_STEP = 0.05
        private const val SESSION_TIMEOUT_MS = 15_000L
        private const val POLL_INTERVAL_MS = 1_000L
    }
}
