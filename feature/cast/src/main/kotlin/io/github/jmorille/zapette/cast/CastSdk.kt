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
import com.google.android.gms.cast.TextTrackStyle
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.SessionManagerListener
import com.google.android.gms.cast.framework.media.RemoteMediaClient
import com.google.android.gms.common.api.PendingResult
import io.github.jmorille.zapette.model.Device
import io.github.jmorille.zapette.model.DeviceKind
import io.github.jmorille.zapette.model.RemoteException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

// Câblage du SDK Google Cast / MediaRouter : sans logique propre, non testable unitairement
// (exclu de la mesure Kover). La logique est dans CastController, testée avec des faux.

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

/** Clé des données personnalisées portant le plafond de débit (bit/s). */
private const val CUSTOM_MAX_BITRATE = "maxBitrate"

/** Conversion vers le TextTrackStyle du SDK (couleurs ARGB, échelle de police). */
private fun CastSubtitleStyle.toSdk(): TextTrackStyle = TextTrackStyle().apply {
    fontScale = size.fontScale
    foregroundColor = textColor.argb
    backgroundColor = background.argb
    if (outlined) {
        edgeType = TextTrackStyle.EDGE_TYPE_OUTLINE
        edgeColor = 0xFF000000.toInt()
    } else {
        edgeType = TextTrackStyle.EDGE_TYPE_NONE
    }
}

/** [CastPlatform] réel : SessionManager + MediaRouter. Appelé sur le thread principal. */
internal class SdkCastPlatform(private val context: Context) : CastPlatform {
    private var cast: CastContext? = null

    private val router: MediaRouter get() = MediaRouter.getInstance(context.applicationContext)
    private val manager get() = (cast ?: throw RemoteException("Google Cast non initialisé")).sessionManager

    override suspend fun ready() {
        cast = castContext(context)
    }

    override fun routes(): List<CastRoute> = router.routes
        .filter { !it.isDefault && it.matchesSelector(castSelector) }
        .map { route ->
            val cast = CastDevice.getFromBundle(route.extras)
            CastRoute(route.id, cast?.inetAddress?.hostAddress, cast?.deviceId)
        }

    override fun currentSession(): CastSessionPort? = manager.currentCastSession?.let(::SdkCastSession)

    override fun selectRoute(route: CastRoute) {
        val info = router.routes.firstOrNull { it.id == route.id } ?: throw RemoteException("Route Cast disparue")
        router.selectRoute(info)
    }

    override fun endCurrentSession() = manager.endCurrentSession(true)

    override fun listen(events: CastSessionEvents): AutoCloseable {
        val listener = object : SessionManagerListener<CastSession> {
            override fun onSessionStarted(session: CastSession, sessionId: String) = events.started(SdkCastSession(session))
            override fun onSessionResumed(session: CastSession, wasSuspended: Boolean) = events.started(SdkCastSession(session))
            override fun onSessionStartFailed(session: CastSession, error: Int) = events.failed("Échec de la session Cast ($error)")
            override fun onSessionResumeFailed(session: CastSession, error: Int) =
                events.failed("Reprise de la session Cast impossible ($error)")

            // Fin de l'éventuelle session précédente (autre appareil) : sans effet sur l'attente.
            override fun onSessionEnded(session: CastSession, error: Int) = Unit
            override fun onSessionStarting(session: CastSession) = Unit
            override fun onSessionEnding(session: CastSession) = Unit
            override fun onSessionResuming(session: CastSession, sessionId: String) = Unit
            override fun onSessionSuspended(session: CastSession, reason: Int) = Unit
        }
        val sessions = manager
        sessions.addSessionManagerListener(listener, CastSession::class.java)
        return AutoCloseable { sessions.removeSessionManagerListener(listener, CastSession::class.java) }
    }
}

private class SdkCastSession(private val session: CastSession) : CastSessionPort {
    override val deviceId: String? get() = session.castDevice?.deviceId
    override val isConnected: Boolean get() = session.isConnected
    override val isConnecting: Boolean get() = session.isConnecting
    override var volume: Double
        get() = session.volume
        set(value) {
            session.volume = value
        }
    override var isMute: Boolean
        get() = session.isMute
        set(value) {
            session.isMute = value
        }
    override val media: CastMediaPort? get() = session.remoteMediaClient?.let(::SdkCastMedia)
}

private class SdkCastMedia(private val client: RemoteMediaClient) : CastMediaPort {
    override val title: String? get() = client.mediaInfo?.metadata?.getString(MediaMetadata.KEY_TITLE)
    override val isPlaying: Boolean get() = client.isPlaying
    override val positionMs: Long get() = client.approximateStreamPosition
    override val durationMs: Long get() = client.streamDuration

    override fun togglePlayback() {
        client.togglePlayback()
    }

    override fun stop() {
        client.stop()
    }

    override fun next() {
        client.queueNext(null)
    }

    override fun previous() {
        client.queuePrev(null)
    }

    override fun seek(positionMs: Long) {
        client.seek(MediaSeekOptions.Builder().setPosition(positionMs).build())
    }

    override fun setTextTrackStyle(style: CastSubtitleStyle) {
        client.setTextTrackStyle(style.toSdk())
    }

    override suspend fun load(url: String, contentType: String, title: String, options: CastLoadOptions) {
        val metadata = MediaMetadata(
            if (CastMedia.isAudio(contentType)) MediaMetadata.MEDIA_TYPE_MUSIC_TRACK else MediaMetadata.MEDIA_TYPE_MOVIE,
        ).apply { putString(MediaMetadata.KEY_TITLE, title) }
        val info = MediaInfo.Builder(url)
            .setContentType(contentType)
            .setStreamType(MediaInfo.STREAM_TYPE_BUFFERED)
            .setMetadata(metadata)
            .setTextTrackStyle(options.subtitles.toSdk())
            .build()
        val request = MediaLoadRequestData.Builder().setMediaInfo(info).setAutoplay(true)
        // Plafond de débit : aucune API du récepteur par défaut ; transmis en données
        // personnalisées pour un récepteur qui sait l'exploiter (ignoré sinon).
        options.maxBitrate?.let { request.setCustomData(JSONObject().put(CUSTOM_MAX_BITRATE, it)) }
        // Format non pris en charge, URL injoignable… : l'échec est remonté au lieu d'être ignoré.
        client.load(request.build()).awaitResult("Chargement du média")
    }
}
