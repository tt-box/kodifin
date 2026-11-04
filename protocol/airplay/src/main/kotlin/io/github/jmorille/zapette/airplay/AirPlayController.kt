package io.github.jmorille.zapette.airplay

import io.github.jmorille.zapette.model.Capability
import io.github.jmorille.zapette.model.Device
import io.github.jmorille.zapette.model.EMPTY_BODY
import io.github.jmorille.zapette.model.PlaybackState
import io.github.jmorille.zapette.model.RemoteController
import io.github.jmorille.zapette.model.RemoteException
import io.github.jmorille.zapette.model.RemoteKey
import io.github.jmorille.zapette.model.fetchString
import io.github.jmorille.zapette.model.requireKey
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.Locale
import java.util.UUID

/**
 * AirPlay « vidéo » historique (AirPlay 1, HTTP sur le port 7000) : lecture d'une URL,
 * pause, reprise, recherche, arrêt. Les récepteurs AirPlay 2 exigeant l'appairage
 * HomeKit ne sont pas pris en charge (limite documentée).
 */
class AirPlayController(
    override val device: Device,
    private val http: OkHttpClient,
    private val pollIntervalMs: Long = 1_000,
) : RemoteController {

    override val capabilities = setOf(Capability.PLAYBACK, Capability.SEEK, Capability.PLAY_URL, Capability.NOW_PLAYING)
    override val supportedKeys = setOf(RemoteKey.PLAY_PAUSE, RemoteKey.STOP)

    private val base: HttpUrl = device.baseUrl.toHttpUrl()
    private val sessionId = UUID.randomUUID().toString()
    private var playing = false

    private fun request(path: String) = Request.Builder()
        .url(base.resolve(path)!!)
        .header("User-Agent", "MediaControl/1.0")
        .header("X-Apple-Session-ID", sessionId)

    override suspend fun playUrl(url: String, title: String?) {
        val body = "Content-Location: $url\nStart-Position: 0\n"
        http.fetchString(request("/play").post(body.toRequestBody(TEXT_PARAMETERS)).build())
        playing = true
    }

    override suspend fun send(key: RemoteKey) {
        requireKey(key)
        when (key) {
            RemoteKey.PLAY_PAUSE -> setRate(if (playing) 0 else 1)
            RemoteKey.STOP -> {
                http.fetchString(request("/stop").post(EMPTY_BODY).build())
                playing = false
            }
            else -> Unit
        }
    }

    private suspend fun setRate(rate: Int) {
        http.fetchString(request("/rate?value=$rate.000000").post(EMPTY_BODY).build())
        playing = rate != 0
    }

    override suspend fun seekTo(positionMs: Long) {
        val seconds = String.format(Locale.ROOT, "%.3f", positionMs / 1000.0)
        http.fetchString(request("/scrub?position=$seconds").post(EMPTY_BODY).build())
    }

    override fun playbackState(): Flow<PlaybackState> = flow {
        while (true) {
            val text = runCatching { http.fetchString(request("/scrub").get().build()) }.getOrNull()
            if (text != null) {
                val (position, duration) = parseScrub(text)
                emit(PlaybackState(isPlaying = playing, positionMs = position, durationMs = duration))
            }
            delay(pollIntervalMs)
        }
    }

    override suspend fun connect() {
        runCatching { http.fetchString(request("/server-info").get().build()) }
            .onFailure { throw RemoteException("Récepteur AirPlay injoignable ou AirPlay 2 uniquement", it) }
    }

    companion object {
        private val TEXT_PARAMETERS = "text/parameters".toMediaType()

        /** Analyse `duration: 123.4\nposition: 12.0` en millisecondes (position, durée). */
        fun parseScrub(text: String): Pair<Long, Long> {
            val values = text.lines().mapNotNull { line ->
                val idx = line.indexOf(':')
                if (idx < 0) null else line.substring(0, idx).trim() to line.substring(idx + 1).trim().toDoubleOrNull()
            }.toMap()
            return ((values["position"] ?: 0.0) * 1000).toLong() to ((values["duration"] ?: 0.0) * 1000).toLong()
        }
    }
}
