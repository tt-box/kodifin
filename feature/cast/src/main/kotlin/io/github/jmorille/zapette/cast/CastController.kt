package io.github.jmorille.zapette.cast

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
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Initialisation asynchrone du SDK Cast (API `getSharedInstance(Context, Executor)`). */
suspend fun castContext(context: Context): CastContext = suspendCancellableCoroutine { cont ->
    CastContext.getSharedInstance(context.applicationContext, Executors.newSingleThreadExecutor())
        .addOnSuccessListener { cont.resume(it) }
        .addOnFailureListener { cont.resumeWithException(RemoteException("Google Cast indisponible", it)) }
}

private val castSelector: MediaRouteSelector = MediaRouteSelector.Builder()
    .addControlCategory(CastMediaControlIntent.categoryForCast(CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID))
    .build()

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
    router.addCallback(castSelector, callback, MediaRouter.CALLBACK_FLAG_REQUEST_DISCOVERY)
    publish()
    awaitClose { router.removeCallback(callback) }
}.flowOn(Dispatchers.Main)

/** Chromecast / Google TV via le récepteur multimédia par défaut. */
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

    private suspend fun session(): CastSession = withContext(Dispatchers.Main) {
        val cast = castContext(context)
        val manager = cast.sessionManager
        manager.currentCastSession?.takeIf { it.isConnected && it.castDevice?.deviceId == castDeviceId() }?.let {
            return@withContext it
        }
        val started = CompletableDeferred<CastSession>()
        val listener = object : SessionManagerListener<CastSession> {
            override fun onSessionStarted(session: CastSession, sessionId: String) { started.complete(session) }
            override fun onSessionResumed(session: CastSession, wasSuspended: Boolean) { started.complete(session) }
            override fun onSessionStartFailed(session: CastSession, error: Int) {
                started.completeExceptionally(RemoteException("Échec de la session Cast ($error)"))
            }
            override fun onSessionStarting(session: CastSession) = Unit
            override fun onSessionEnding(session: CastSession) = Unit
            override fun onSessionEnded(session: CastSession, error: Int) = Unit
            override fun onSessionResuming(session: CastSession, sessionId: String) = Unit
            override fun onSessionResumeFailed(session: CastSession, error: Int) = Unit
            override fun onSessionSuspended(session: CastSession, reason: Int) = Unit
        }
        manager.addSessionManagerListener(listener, CastSession::class.java)
        try {
            val router = MediaRouter.getInstance(context.applicationContext)
            val route = router.routes.firstOrNull { it.id == device.id }
                ?: throw RemoteException("${device.name} n'est plus visible sur le réseau")
            router.selectRoute(route)
            withTimeout(15_000) { started.await() }
        } finally {
            manager.removeSessionManagerListener(listener, CastSession::class.java)
        }
    }

    private fun castDeviceId(): String? = MediaRouter.getInstance(context.applicationContext).routes
        .firstOrNull { it.id == device.id }
        ?.let { CastDevice.getFromBundle(it.extras)?.deviceId }

    private suspend fun <T> onMain(block: suspend (CastSession) -> T): T {
        val s = session()
        return withContext(Dispatchers.Main) { block(s) }
    }

    override suspend fun connect() {
        session()
    }

    override suspend fun send(key: RemoteKey) {
        requireKey(key)
        onMain { s ->
            val media = s.remoteMediaClient
            when (key) {
                RemoteKey.PLAY_PAUSE -> media?.togglePlayback()
                RemoteKey.STOP -> media?.stop()
                RemoteKey.NEXT -> media?.queueNext(null)
                RemoteKey.PREVIOUS -> media?.queuePrev(null)
                RemoteKey.REWIND, RemoteKey.FAST_FORWARD -> media?.let {
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
            s.remoteMediaClient?.load(MediaLoadRequestData.Builder().setMediaInfo(info).setAutoplay(true).build())
                ?: throw RemoteException("Lecteur Cast indisponible")
        }
    }

    override suspend fun setVolume(percent: Int) {
        onMain { it.volume = percent.coerceIn(0, 100) / 100.0 }
    }

    override suspend fun seekTo(positionMs: Long) {
        onMain { it.remoteMediaClient?.seek(MediaSeekOptions.Builder().setPosition(positionMs).build()) }
    }

    override fun playbackState(): Flow<PlaybackState> = flow {
        val s = session()
        while (true) {
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

    override suspend fun disconnect() {
        withContext(Dispatchers.Main) {
            runCatching { castContext(context).sessionManager.endCurrentSession(true) }
        }
    }

    companion object {
        private const val SEEK_STEP_MS = 10_000L
        private const val VOLUME_STEP = 0.05
    }
}
