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
import kotlinx.coroutines.yield
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

    /** Dernier état connu ; écrit par les commandes, lu par le flux de suivi (autre thread). */
    @Volatile
    private var playing = false

    private fun request(path: String) = Request.Builder()
        .url(base.resolve(path) ?: throw IllegalArgumentException("Chemin AirPlay invalide : $path"))
        .header("User-Agent", "MediaControl/1.0")
        .header("X-Apple-Session-ID", sessionId)

    override suspend fun playUrl(url: String, title: String?) {
        // Le corps text/parameters est analysé ligne à ligne : un retour à la ligne (y compris
        // NEL U+0085, LS U+2028, PS U+2029) injecterait un paramètre ; NUL et les autres
        // caractères de contrôle tronqueraient ou corrompraient la valeur.
        require(url.none { it.isISOControl() || it == ' ' || it == ' ' }) {
            "URL invalide : retour à la ligne ou caractère de contrôle interdit"
        }
        val body = "Content-Location: $url\nStart-Position: 0.000000\n"
        http.fetchString(request("/play").post(body.toRequestBody(TEXT_PARAMETERS)).build())
        playing = true
    }

    override suspend fun send(key: RemoteKey) {
        requireKey(key)
        when (key) {
            RemoteKey.PLAY_PAUSE -> setRate(if (currentlyPlaying()) 0 else 1)
            RemoteKey.STOP -> {
                http.fetchString(request("/stop").post(EMPTY_BODY).build())
                playing = false
            }
            else -> Unit
        }
    }

    /**
     * Interroge `/playback-info` pour connaître la vitesse réelle (la lecture a pu être lancée
     * ou mise en pause depuis un autre appareil) ; à défaut, dernier état connu.
     */
    private suspend fun currentlyPlaying(): Boolean {
        val info = try {
            http.fetchString(request("/playback-info").get().build())
        } catch (_: RemoteException) {
            return playing
        }
        return parsePlaybackRate(info)?.let { it != 0.0 } ?: playing
    }

    private suspend fun setRate(rate: Int) {
        http.fetchString(request("/rate?value=$rate.000000").post(EMPTY_BODY).build())
        playing = rate != 0
    }

    override suspend fun seekTo(positionMs: Long) {
        val seconds = String.format(Locale.ROOT, "%.3f", positionMs.coerceAtLeast(0) / 1000.0)
        http.fetchString(request("/scrub?position=$seconds").post(EMPTY_BODY).build())
    }

    override fun playbackState(): Flow<PlaybackState> = flow {
        while (true) {
            // Seules les erreurs de communication sont ignorées : l'annulation doit se propager.
            val text = try {
                http.fetchString(request("/scrub").get().build())
            } catch (_: RemoteException) {
                null
            }
            if (text != null) {
                val (position, duration) = parseScrub(text)
                emit(PlaybackState(isPlaying = playing, positionMs = position, durationMs = duration))
            }
            // delay(0) rend la main sans suspendre : yield() évite une boucle active non annulable.
            if (pollIntervalMs > 0) delay(pollIntervalMs) else yield()
        }
    }

    override suspend fun connect() {
        try {
            http.fetchString(request("/server-info").get().build())
        } catch (e: RemoteException) {
            throw RemoteException("Récepteur AirPlay injoignable ou AirPlay 2 uniquement", e)
        }
    }

    companion object {
        private val TEXT_PARAMETERS = "text/parameters".toMediaType()

        private val RATE = Regex("""<key>\s*rate\s*</key>\s*<(?:real|integer)>\s*([-+0-9.eE]+)\s*</""")

        /** Extrait la clé `rate` de la plist XML renvoyée par `/playback-info` ; `null` si absente. */
        internal fun parsePlaybackRate(plist: String): Double? =
            RATE.find(plist)?.groupValues?.get(1)?.toDoubleOrNull()

        /** Analyse `duration: 123.4\nposition: 12.0` en millisecondes (position, durée). */
        fun parseScrub(text: String): Pair<Long, Long> {
            val values = text.lines().mapNotNull { line ->
                val idx = line.indexOf(':')
                if (idx < 0) null else line.substring(0, idx).trim().lowercase() to line.substring(idx + 1).trim().toDoubleOrNull()
            }.toMap()
            // Valeurs négatives, NaN ou infinies (flux en direct) ramenées à 0.
            fun ms(key: String): Long = values[key]?.takeIf { it.isFinite() && it > 0 }?.let { (it * 1000).toLong() } ?: 0
            return ms("position") to ms("duration")
        }
    }
}
