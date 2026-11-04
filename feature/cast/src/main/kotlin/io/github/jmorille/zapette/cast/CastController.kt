package io.github.jmorille.zapette.cast

import android.annotation.SuppressLint
import android.content.Context
import androidx.mediarouter.media.MediaRouteSelector
import androidx.mediarouter.media.MediaRouter
import com.google.android.gms.cast.CastDevice
import com.google.android.gms.cast.CastMediaControlIntent
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaLoadRequestData
import com.google.android.gms.cast.MediaMetadata
import com.google.android.gms.cast.MediaSeekOptions
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.SessionManagerListener
import com.google.android.gms.cast.framework.media.RemoteMediaClient
import com.google.android.gms.common.api.PendingResult
import io.github.jmorille.zapette.model.Capability
import io.github.jmorille.zapette.model.Device
import io.github.jmorille.zapette.model.DeviceKind
import io.github.jmorille.zapette.model.PlaybackState
import io.github.jmorille.zapette.model.RemoteController
import io.github.jmorille.zapette.model.RemoteException
import io.github.jmorille.zapette.model.RemoteKey
import io.github.jmorille.zapette.model.requireKey
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.IOException
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Un seul thread d'initialisation pour tout le processus (auparavant : un nouveau thread, jamais arrêté, par appel). */
private val castInitExecutor: Executor by lazy {
    Executors.newSingleThreadExecutor { Thread(it, "zapette-cast-init").apply { isDaemon = true } }
}

/** Singleton du SDK, lié au contexte applicatif : aucune activité n'est retenue. */
@SuppressLint("StaticFieldLeak")
@Volatile
private var sharedCastContext: CastContext? = null

/**
 * Initialisation asynchrone du SDK Cast (API `getSharedInstance(Context, Executor)`), sur le
 * thread principal exigé par le SDK. Le contexte obtenu est mémorisé. Lève [RemoteException]
 * si les services Google Play sont absents ou trop anciens.
 */
suspend fun castContext(context: Context): CastContext {
    sharedCastContext?.let { return it }
    return withContext(Dispatchers.Main.immediate) {
        sharedCastContext ?: suspendCancellableCoroutine { cont ->
            val task = try {
                CastContext.getSharedInstance(context.applicationContext, castInitExecutor)
            } catch (e: RuntimeException) {
                // Services Google Play absents : certaines versions lèvent au lieu d'échouer la tâche.
                cont.resumeWithException(RemoteException("Google Cast indisponible (services Google Play absents ?)", e))
                return@suspendCancellableCoroutine
            }
            task.addOnSuccessListener {
                sharedCastContext = it
                if (cont.isActive) cont.resume(it)
            }.addOnFailureListener {
                if (cont.isActive) cont.resumeWithException(RemoteException("Google Cast indisponible", it))
            }
        }
    }
}

private val castSelector: MediaRouteSelector = MediaRouteSelector.Builder()
    .addControlCategory(CastMediaControlIntent.categoryForCast(CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID))
    .build()

private fun MediaRouter.RouteInfo.castHost(): String? =
    CastDevice.getFromBundle(extras)?.inetAddress?.hostAddress

/** Découverte des appareils Cast via MediaRouter (scan actif tant que le flux est collecté). */
fun castDevices(context: Context): Flow<List<Device>> = callbackFlow {
    castContext(context)
    val router = MediaRouter.getInstance(context.applicationContext)
    fun publish() {
        val devices = router.routes
            .filter { !it.isDefault && !it.isBluetooth && it.matchesSelector(castSelector) }
            .map { route ->
                val cast = CastDevice.getFromBundle(route.extras)
                Device(
                    id = route.id,
                    name = route.name,
                    kind = DeviceKind.CHROMECAST,
                    host = cast?.inetAddress?.hostAddress.orEmpty(),
                    port = cast?.servicePort ?: DeviceKind.CHROMECAST.defaultPort,
                )
            }
        trySend(devices)
    }
    val callback = object : MediaRouter.Callback() {
        override fun onRouteAdded(router: MediaRouter, route: MediaRouter.RouteInfo) = publish()
        override fun onRouteRemoved(router: MediaRouter, route: MediaRouter.RouteInfo) = publish()
        override fun onRouteChanged(router: MediaRouter, route: MediaRouter.RouteInfo) = publish()
    }
    router.addCallback(
        castSelector,
        callback,
        MediaRouter.CALLBACK_FLAG_REQUEST_DISCOVERY or MediaRouter.CALLBACK_FLAG_PERFORM_ACTIVE_SCAN,
    )
    publish()
    // awaitClose s'exécute sur le thread principal (flowOn) : retrait du callback autorisé.
    awaitClose { router.removeCallback(callback) }
}.flowOn(Dispatchers.Main)

/** Attend le résultat d'une commande média, borné dans le temps. */
private suspend fun PendingResult<RemoteMediaClient.MediaChannelResult>.awaitResult(what: String) {
    val status = suspendCancellableCoroutine { cont ->
        cont.invokeOnCancellation { runCatching { cancel() } }
        setResultCallback({ if (cont.isActive) cont.resume(it.status) }, RESULT_TIMEOUT_S, TimeUnit.SECONDS)
    }
    if (!status.isSuccess) {
        throw RemoteException("$what refusé par le Chromecast (${status.statusCode}${status.statusMessage?.let { " : $it" }.orEmpty()})")
    }
}

private const val RESULT_TIMEOUT_S = 15L

/** Chromecast / Google TV via le récepteur multimédia par défaut. Tous les appels SDK passent par le thread principal. */
class CastController(
    override val device: Device,
    private val context: Context,
) : RemoteController {

    override val capabilities = setOf(
        Capability.PLAYBACK, Capability.VOLUME, Capability.SEEK, Capability.PLAY_URL, Capability.NOW_PLAYING,
    )
    override val supportedKeys = setOf(
        RemoteKey.PLAY_PAUSE, RemoteKey.STOP, RemoteKey.NEXT, RemoteKey.PREVIOUS,
        RemoteKey.REWIND, RemoteKey.FAST_FORWARD, RemoteKey.VOLUME_UP, RemoteKey.VOLUME_DOWN, RemoteKey.MUTE,
    )

    /** Évite que deux appels simultanés (état de lecture + touche) sélectionnent la route deux fois. */
    private val sessionLock = Mutex()

    private val router: MediaRouter get() = MediaRouter.getInstance(context.applicationContext)

    /** Route de l'appareil : par identifiant (découverte) ou, pour un appareil saisi à la main, par adresse IP. */
    private fun route(): MediaRouter.RouteInfo? = router.routes
        .filter { !it.isDefault && it.matchesSelector(castSelector) }
        .let { routes ->
            routes.firstOrNull { it.id == device.id }
                ?: routes.firstOrNull { CastMedia.sameHost(it.castHost(), device.host) }
        }

    private fun isOurs(session: CastSession?): Boolean {
        val id = session?.castDevice?.deviceId ?: return false
        return id == route()?.let { CastDevice.getFromBundle(it.extras)?.deviceId }
    }

    private suspend fun session(): CastSession = sessionLock.withLock {
        withContext(Dispatchers.Main) {
            val manager = castContext(context).sessionManager
            manager.currentCastSession?.takeIf { it.isConnected && isOurs(it) }?.let { return@withContext it }

            val started = CompletableDeferred<CastSession>()
            val listener = object : SessionManagerListener<CastSession> {
                override fun onSessionStarted(session: CastSession, sessionId: String) { started.complete(session) }
                override fun onSessionResumed(session: CastSession, wasSuspended: Boolean) { started.complete(session) }
                override fun onSessionStartFailed(session: CastSession, error: Int) {
                    started.completeExceptionally(RemoteException("Échec de la session Cast ($error)"))
                }
                override fun onSessionResumeFailed(session: CastSession, error: Int) {
                    started.completeExceptionally(RemoteException("Reprise de la session Cast impossible ($error)"))
                }
                // Fin de l'éventuelle session précédente (autre appareil) : sans effet sur l'attente.
                override fun onSessionEnded(session: CastSession, error: Int) = Unit
                override fun onSessionStarting(session: CastSession) = Unit
                override fun onSessionEnding(session: CastSession) = Unit
                override fun onSessionResuming(session: CastSession, sessionId: String) = Unit
                override fun onSessionSuspended(session: CastSession, reason: Int) = Unit
            }
            manager.addSessionManagerListener(listener, CastSession::class.java)
            try {
                val current = manager.currentCastSession
                // Une session vers cet appareil déjà en cours d'ouverture : on l'attend sans resélectionner.
                if (current == null || !current.isConnecting || !isOurs(current)) {
                    val route = route() ?: throw RemoteException("${device.name} n'est plus visible sur le réseau")
                    router.selectRoute(route)
                }
                try {
                    withTimeout(SESSION_TIMEOUT_MS) { started.await() }
                } catch (e: TimeoutCancellationException) {
                    runCatching { manager.endCurrentSession(true) }
                    throw RemoteException("${device.name} ne répond pas (délai de connexion Cast dépassé)", e)
                }
            } finally {
                manager.removeSessionManagerListener(listener, CastSession::class.java)
            }
        }
    }

    /** Exécute [block] sur le thread principal et traduit les erreurs du SDK en [RemoteException]. */
    private suspend fun <T> onMain(block: suspend (CastSession) -> T): T {
        val s = session()
        return withContext(Dispatchers.Main) {
            try {
                block(s)
            } catch (e: IOException) {
                throw RemoteException("Commande Cast impossible", e)
            } catch (e: IllegalStateException) {
                throw RemoteException("Session Cast interrompue", e)
            }
        }
    }

    private fun CastSession.media(): RemoteMediaClient =
        remoteMediaClient ?: throw RemoteException("Aucun lecteur actif sur ${device.name}")

    override suspend fun connect() {
        session()
    }

    override suspend fun send(key: RemoteKey) {
        requireKey(key)
        onMain { s ->
            when (key) {
                RemoteKey.PLAY_PAUSE -> s.media().togglePlayback()
                RemoteKey.STOP -> s.media().stop()
                RemoteKey.NEXT -> s.media().queueNext(null)
                RemoteKey.PREVIOUS -> s.media().queuePrev(null)
                RemoteKey.REWIND, RemoteKey.FAST_FORWARD -> s.media().let {
                    val delta = if (key == RemoteKey.REWIND) -SEEK_STEP_MS else SEEK_STEP_MS
                    val target = CastMedia.clampSeek(it.approximateStreamPosition + delta, it.streamDuration)
                    it.seek(MediaSeekOptions.Builder().setPosition(target).build())
                }
                RemoteKey.VOLUME_UP -> s.volume = CastMedia.stepVolume(s.volume, VOLUME_STEP)
                RemoteKey.VOLUME_DOWN -> s.volume = CastMedia.stepVolume(s.volume, -VOLUME_STEP)
                RemoteKey.MUTE -> s.isMute = !s.isMute
                else -> Unit
            }
        }
    }

    override suspend fun playUrl(url: String, title: String?) {
        onMain { s ->
            val type = CastMedia.contentType(url)
            val metadata = MediaMetadata(
                if (CastMedia.isAudio(type)) MediaMetadata.MEDIA_TYPE_MUSIC_TRACK else MediaMetadata.MEDIA_TYPE_MOVIE,
            ).apply { putString(MediaMetadata.KEY_TITLE, title ?: url.substringAfterLast('/')) }
            val info = MediaInfo.Builder(url)
                .setContentType(type)
                .setStreamType(MediaInfo.STREAM_TYPE_BUFFERED)
                .setMetadata(metadata)
                .build()
            // Format non pris en charge, URL injoignable… : l'échec est remonté au lieu d'être ignoré.
            s.media().load(MediaLoadRequestData.Builder().setMediaInfo(info).setAutoplay(true).build())
                .awaitResult("Chargement du média")
        }
    }

    override suspend fun setVolume(percent: Int) {
        onMain { it.volume = percent.coerceIn(0, 100) / 100.0 }
    }

    override suspend fun seekTo(positionMs: Long) {
        onMain { s ->
            val media = s.media()
            media.seek(MediaSeekOptions.Builder().setPosition(CastMedia.clampSeek(positionMs, media.streamDuration)).build())
        }
    }

    override fun playbackState(): Flow<PlaybackState> = flow {
        val s = session()
        while (true) {
            // Le SDK lève IllegalStateException sur volume/isMute une fois la session fermée.
            if (!s.isConnected) throw RemoteException("Session Cast terminée")
            val media = s.remoteMediaClient
            emit(
                PlaybackState(
                    title = media?.mediaInfo?.metadata?.getString(MediaMetadata.KEY_TITLE),
                    isPlaying = media?.isPlaying == true,
                    positionMs = media?.approximateStreamPosition ?: 0,
                    durationMs = media?.streamDuration ?: 0,
                    volumePercent = (s.volume * 100).toInt(),
                    muted = s.isMute,
                ),
            )
            delay(1_000)
        }
    }.flowOn(Dispatchers.Main)

    /** Ne ferme que la session de cet appareil (pas celle qu'un autre écran aurait ouverte). */
    override suspend fun disconnect() {
        withContext(Dispatchers.Main) {
            runCatching {
                val manager = castContext(context).sessionManager
                if (isOurs(manager.currentCastSession)) manager.endCurrentSession(true)
            }
        }
    }

    companion object {
        private const val SEEK_STEP_MS = 10_000L
        private const val VOLUME_STEP = 0.05
        private const val SESSION_TIMEOUT_MS = 15_000L
    }
}
