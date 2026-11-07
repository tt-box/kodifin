package io.github.ttbox.kodifin.airplay

import io.github.ttbox.kodifin.model.Capability
import io.github.ttbox.kodifin.model.Device
import io.github.ttbox.kodifin.model.EMPTY_BODY
import io.github.ttbox.kodifin.model.HttpStatusException
import io.github.ttbox.kodifin.model.PlaybackState
import io.github.ttbox.kodifin.model.RemoteController
import io.github.ttbox.kodifin.model.RemoteError
import io.github.ttbox.kodifin.model.RemoteException
import io.github.ttbox.kodifin.model.RemoteKey
import io.github.ttbox.kodifin.model.Setup
import io.github.ttbox.kodifin.model.fetchString
import io.github.ttbox.kodifin.model.requireKey
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.yield
import okhttp3.ConnectionPool
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.security.SecureRandom
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * AirPlay « vidéo » historique (AirPlay 1, HTTP sur le port 7000) : lecture d'une URL,
 * pause, reprise, recherche, arrêt, d'après la spécification publique non officielle
 * (« Unofficial AirPlay Protocol Specification »).
 *
 * - **Mot de passe** : un récepteur protégé répond 401 avec un défi HTTP Digest (realm
 *   « AirPlay », utilisateur « AirPlay ») ; le mot de passe est lu dans l'extra [EXTRA_PASSWORD]
 *   et n'apparaît jamais dans un message ni un journal (voir [AirPlayDigestAuth]).
 * - **État** : lu sur `/playback-info` (plist : `rate`, `position`, `duration`) ; `/scrub` et
 *   le dernier état connu ne servent que de repli pour les récepteurs qui ne l'implémentent pas.
 * - **Canal `/reverse` et durée de vie de la connexion** : le client officiel ouvre une seconde
 *   connexion (`POST /reverse`, passage en PTTH) pour recevoir les événements du récepteur ;
 *   elle n'est pas utilisée ici (l'état est sondé). Certains récepteurs arrêtent la lecture
 *   lorsque la connexion TCP qui a porté `/play` se ferme : les requêtes passent donc par un
 *   pool de connexions dédié, à longue durée de maintien ([KEEP_ALIVE_HOURS]), si bien que le
 *   sondage réutilise et garde ouverte cette connexion. Rien ne garantit que le récepteur la
 *   conserve (il peut la fermer de lui-même) ; [disconnect] ne la ferme pas, pour ne pas
 *   interrompre une lecture quand l'écran de télécommande est quitté.
 * - **Hors portée** : AirPlay 2 (appairage HomeKit, code PIN affiché à l'écran, chiffrement
 *   FairPlay/SRP) n'est pas pris en charge ; ces récepteurs refusent `/server-info` ou `/play`.
 */
class AirPlayController(
    override val device: Device,
    http: OkHttpClient,
    private val pollIntervalMs: Long = 1_000,
    /** Identifiant client `X-Apple-Device-ID` (forme `0x` + 12 chiffres hexadécimaux). */
    private val clientDeviceId: String = PROCESS_DEVICE_ID,
) : RemoteController {

    override val capabilities = setOf(Capability.PLAYBACK, Capability.SEEK, Capability.PLAY_URL, Capability.NOW_PLAYING)
    override val supportedKeys = setOf(RemoteKey.PLAY_PAUSE, RemoteKey.STOP)

    private val base: HttpUrl? = device.baseUrl.toHttpUrlOrNull()

    /** Identifiant de session `X-Apple-Session-ID` : un UUID par contrôleur (session de pilotage). */
    private val sessionId = UUID.randomUUID().toString()

    private val password: String? = device.extras[EXTRA_PASSWORD]?.ifEmpty { null }

    /**
     * Client dédié : pool AirPlay commun à longue durée de maintien (la connexion de `/play`
     * reste ouverte et réutilisée, y compris par le contrôleur suivant du même récepteur) et authentification Digest éventuelle. Dispatcher et réglages
     * (délais, TLS…) sont ceux du client fourni.
     */
    private val http: OkHttpClient = http.newBuilder()
        .connectionPool(SHARED_POOL)
        .apply { password?.let { addInterceptor(AirPlayDigestAuth(it)) } }
        .build()

    /** Dernier état connu, repli quand le récepteur ne publie pas `/playback-info`. */
    @Volatile
    private var playing = false

    private fun request(path: String): Request.Builder {
        val root = base ?: throw RemoteException(RemoteError.InvalidAddress("${device.host}:${device.port}"))
        return Request.Builder()
            .url(root.resolve(path) ?: throw IllegalArgumentException("Chemin AirPlay invalide : $path"))
            .header("User-Agent", "MediaControl/1.0")
            .header("X-Apple-Device-ID", clientDeviceId)
            .header("X-Apple-Session-ID", sessionId)
    }

    /** Exécute la requête ; un 401 devient une raison explicite (mot de passe requis ou refusé). */
    private suspend fun call(request: Request): String = try {
        http.fetchString(request)
    } catch (e: HttpStatusException) {
        if (e.code != 401) throw e
        throw AirPlayAuthException(if (password == null) RemoteError.PasswordRequired else RemoteError.PasswordRejected, e)
    }

    override suspend fun playUrl(url: String, title: String?) {
        // Le corps text/parameters est analysé ligne à ligne : un retour à la ligne (y compris
        // NEL U+0085, LS U+2028, PS U+2029) injecterait un paramètre ; NUL et les autres
        // caractères de contrôle tronqueraient ou corrompraient la valeur.
        require(url.none { it.isISOControl() || it == ' ' || it == ' ' }) {
            "URL invalide : retour à la ligne ou caractère de contrôle interdit"
        }
        val body = "Content-Location: $url\nStart-Position: 0.000000\n"
        call(request("/play").post(body.toRequestBody(TEXT_PARAMETERS)).build())
        playing = true
    }

    override suspend fun send(key: RemoteKey) {
        requireKey(key)
        when (key) {
            RemoteKey.PLAY_PAUSE -> setRate(if (currentlyPlaying()) 0 else 1)
            RemoteKey.STOP -> {
                call(request("/stop").post(EMPTY_BODY).build())
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
            call(request("/playback-info").get().build())
        } catch (e: AirPlayAuthException) {
            throw e
        } catch (_: RemoteException) {
            return playing
        }
        return parsePlaybackRate(info)?.let { it != 0.0 } ?: playing
    }

    private suspend fun setRate(rate: Int) {
        call(request("/rate?value=$rate.000000").post(EMPTY_BODY).build())
        playing = rate != 0
    }

    override suspend fun seekTo(positionMs: Long) {
        val seconds = String.format(Locale.ROOT, "%.3f", positionMs.coerceAtLeast(0) / 1000.0)
        call(request("/scrub?position=$seconds").post(EMPTY_BODY).build())
    }

    /**
     * État réel lu sur `/playback-info`. Si le récepteur ne l'implémente pas (404, 501, plist
     * sans `rate`), repli sur `/scrub` (position, durée) et le dernier état connu.
     * Seules les erreurs de communication sont ignorées : l'annulation se propage.
     */
    override fun playbackState(): Flow<PlaybackState> = flow {
        var playbackInfoSupported = true
        while (true) {
            var state: PlaybackState? = null
            if (playbackInfoSupported) {
                try {
                    val info = parsePlist(call(request("/playback-info").get().build()))
                    val rate = info["rate"]?.toDoubleOrNull()
                    if (rate != null) {
                        playing = rate != 0.0
                        state = PlaybackState(
                            isPlaying = playing,
                            positionMs = seconds(info["position"]),
                            durationMs = seconds(info["duration"]),
                        )
                    } else if (info["readyToPlay"] == "false") {
                        // Rien n'est chargé : état arrêté, inutile d'interroger /scrub.
                        playing = false
                        state = PlaybackState()
                    }
                } catch (e: HttpStatusException) {
                    if (e.code == 404 || e.code == 501) playbackInfoSupported = false
                } catch (_: RemoteException) {
                }
            }
            if (state == null) {
                val text = try {
                    call(request("/scrub").get().build())
                } catch (_: RemoteException) {
                    null
                }
                if (text != null) {
                    val (position, duration) = parseScrub(text)
                    state = PlaybackState(isPlaying = playing, positionMs = position, durationMs = duration)
                }
            }
            state?.let { emit(it) }
            // delay(0) rend la main sans suspendre : yield() évite une boucle active non annulable.
            if (pollIntervalMs > 0) delay(pollIntervalMs) else yield()
        }
    }

    override suspend fun connect() {
        try {
            call(request("/server-info").get().build())
        } catch (e: AirPlayAuthException) {
            throw e
        } catch (e: RemoteException) {
            throw RemoteException(RemoteError.SetupRequired(Setup.AIRPLAY_1_REQUIRED), e)
        }
    }

    /**
     * Mot de passe AirPlay absent ou refusé (HTTP 401) : raison [RemoteError.PasswordRequired] ou
     * [RemoteError.PasswordRejected]. Le message ne contient jamais le mot de passe.
     */
    class AirPlayAuthException(reason: RemoteError, cause: Throwable? = null) : RemoteException(reason, cause)

    companion object {
        /** Extra : mot de passe AirPlay du récepteur (réglage « Exiger un mot de passe »). */
        const val EXTRA_PASSWORD = "password"

        /** Durée de maintien des connexions inactives (voir la documentation de la classe). */
        const val KEEP_ALIVE_HOURS = 4L
        private const val MAX_IDLE_CONNECTIONS = 4

        /** Pool partagé par tous les contrôleurs AirPlay : borne le nombre de connexions maintenues. */
        private val SHARED_POOL = ConnectionPool(MAX_IDLE_CONNECTIONS, KEEP_ALIVE_HOURS, TimeUnit.HOURS)

        private val TEXT_PARAMETERS = "text/parameters".toMediaType()

        /** Identifiant client aléatoire, stable pour la durée du processus (aucune donnée matérielle). */
        val PROCESS_DEVICE_ID: String = ByteArray(6).also { SecureRandom().nextBytes(it) }
            .joinToString("", prefix = "0x") { "%02x".format(it.toInt() and 0xff) }

        private val TAG = Regex("""<(/?)([A-Za-z]+)\s*(/?)>([^<]*)""")

        /**
         * Valeurs scalaires du dictionnaire racine d'une plist XML (clé → texte ; `true`/`false`
         * pour les booléens). Les dictionnaires et tableaux imbriqués (`loadedTimeRanges`,
         * `seekableTimeRanges`, dont les `duration` ne sont pas celle du média) sont ignorés.
         */
        internal fun parsePlist(xml: String): Map<String, String> {
            val out = linkedMapOf<String, String>()
            var depth = 0
            var key: String? = null
            for (m in TAG.findAll(xml)) {
                val closing = m.groupValues[1] == "/"
                val name = m.groupValues[2]
                val selfClosing = m.groupValues[3] == "/"
                when {
                    name == "dict" || name == "array" -> {
                        if (depth == 1) key = null
                        if (!selfClosing) depth += if (closing) -1 else 1
                    }
                    closing || depth != 1 -> Unit
                    name == "key" -> key = m.groupValues[4].trim()
                    else -> {
                        key?.let { out[it] = if (selfClosing) name else m.groupValues[4].trim() }
                        key = null
                    }
                }
            }
            return out
        }

        /** Secondes (réel) → millisecondes ; valeurs absentes, négatives, NaN ou infinies → 0. */
        private fun seconds(value: String?): Long =
            value?.toDoubleOrNull()?.takeIf { it.isFinite() && it > 0 }?.let { (it * 1000).toLong() } ?: 0

        /** Extrait la clé `rate` de la plist XML renvoyée par `/playback-info` ; `null` si absente. */
        internal fun parsePlaybackRate(plist: String): Double? = parsePlist(plist)["rate"]?.toDoubleOrNull()

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
