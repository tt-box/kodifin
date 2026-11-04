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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.IOException
import kotlin.coroutines.CoroutineContext

/**
 * Chromecast / Google TV via le récepteur multimédia par défaut. Tous les appels SDK passent par
 * [main] (le thread principal en production) ; le SDK est atteint via [CastPlatform].
 */
class CastController internal constructor(
    override val device: Device,
    private val platform: CastPlatform,
    private val main: CoroutineContext,
    private val sessionTimeoutMs: Long = SESSION_TIMEOUT_MS,
) : RemoteController {

    constructor(device: Device, context: Context) : this(device, SdkCastPlatform(context), Dispatchers.Main)

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
                if (expectedId == null || session.deviceId == null || session.deviceId == expectedId) started.complete(session)
            }

            override fun failed(message: String) {
                started.completeExceptionally(RemoteException(message))
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
        onMain { s -> s.media().load(url, CastMedia.contentType(url), title ?: url.substringAfterLast('/')) }
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
        while (true) {
            // Le SDK lève IllegalStateException sur volume/isMute une fois la session fermée.
            if (!s.isConnected) throw RemoteException("Session Cast terminée")
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
            delay(1_000)
        }
    }.flowOn(main)

    /** Ne ferme que la session de cet appareil (pas celle qu'un autre écran aurait ouverte). */
    override suspend fun disconnect() {
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
    }
}
