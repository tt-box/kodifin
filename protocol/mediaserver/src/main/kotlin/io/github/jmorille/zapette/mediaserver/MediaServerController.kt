package io.github.jmorille.zapette.mediaserver

import io.github.jmorille.zapette.model.Capability
import io.github.jmorille.zapette.model.DescribesServer
import io.github.jmorille.zapette.model.Device
import io.github.jmorille.zapette.model.DeviceKind
import io.github.jmorille.zapette.model.HttpStatusException
import io.github.jmorille.zapette.model.PersistsCredentials
import io.github.jmorille.zapette.model.PlaybackState
import io.github.jmorille.zapette.model.RemoteController
import io.github.jmorille.zapette.model.RemoteException
import io.github.jmorille.zapette.model.RemoteKey
import io.github.jmorille.zapette.model.requireKey
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient

/**
 * Pilote une session cliente (TV, navigateur, application) d'un serveur Jellyfin/Emby.
 * Le [Device] représente le serveur ; la session cible est `extras[sessionId]`
 * ou, à défaut, la session contrôlable la plus pertinente (hors Zapette elle-même).
 *
 * Une session disparue (404) entraîne une nouvelle sélection ; un jeton refusé (401)
 * entraîne une nouvelle authentification si les identifiants sont encore en mémoire.
 */
class MediaServerController(
    override val device: Device,
    private val client: MediaServerClient,
    private val pollIntervalMs: Long = 2_000,
    private val ownDeviceId: String? = null,
) : RemoteController, PersistsCredentials, DescribesServer {

    override fun persistableExtras(): Map<String, String> = buildMap {
        putAll(device.extras - EXTRA_PASSWORD - EXTRA_SESSION_ID - EXTRA_TOKEN - EXTRA_USER_ID)
        client.accessToken?.let { put(EXTRA_TOKEN, it) }
        userId?.let { put(EXTRA_USER_ID, it) }
        // Une session imposée mais disparue n'est pas reconduite.
        pinnedSessionId?.let { put(EXTRA_SESSION_ID, it) }
        detectedBasePath?.let { put(EXTRA_BASE_PATH, it) }
    }

    /** Chemin de base trouvé automatiquement à la connexion (ex. `/jellyfin`), à enregistrer. */
    @Volatile private var detectedBasePath: String? = null

    override val capabilities = setOf(
        Capability.NAVIGATION, Capability.PLAYBACK, Capability.VOLUME, Capability.SEEK,
        Capability.TEXT_INPUT, Capability.NOW_PLAYING,
    )
    override val supportedKeys = RemoteKey.entries.toSet()

    /** Protège l'authentification et la sélection de session contre les appels concurrents. */
    private val mutex = Mutex()

    @Volatile private var userId: String? = device.extras[EXTRA_USER_ID]

    @Volatile private var pinnedSessionId: String? = device.extras[EXTRA_SESSION_ID]

    @Volatile private var sessionId: String? = pinnedSessionId

    /** « Jellyfin Server 10.10.3 – thor » (Emby ne publie pas `ProductName`). */
    override suspend fun serverDescription(): String = describe(device.kind, client.publicInfo())

    override suspend fun connect() {
        try {
            mutex.withLock { if (client.accessToken == null) authenticateLocked() }
        } catch (e: Exception) {
            // Réponse non JSON (redirection vers l'interface web) ou 404 : le serveur est
            // probablement publié sous un chemin de base (« URL de base » de Jellyfin/Emby).
            if (!looksLikeWrongPath(e) || !detectBasePath()) throw e
            mutex.withLock { if (client.accessToken == null) authenticateLocked() }
        }
        session()
    }

    private fun looksLikeWrongPath(e: Exception): Boolean =
        device.extras[EXTRA_BASE_PATH].isNullOrEmpty() &&
            ((e is RemoteException && e.cause is kotlinx.serialization.SerializationException) ||
                (e is HttpStatusException && e.code == 404))

    /** Essaie les chemins de base usuels ; `true` (et client recalé) si l'un expose l'API. */
    private suspend fun detectBasePath(): Boolean {
        if (client.baseUrl == null) return false
        return when (val path = client.locateBasePath(BASE_PATH_CANDIDATES)) {
            "" -> false
            null -> throw RemoteException(
                "${device.name} ne répond pas comme un serveur ${device.kind.label} à cette adresse : " +
                    "vérifiez le port et le chemin de base (ex. /jellyfin) dans les options avancées",
            )
            else -> {
                detectedBasePath = path
                true
            }
        }
    }

    private suspend fun authenticateLocked() {
        val user = device.extras[EXTRA_USERNAME]
            ?: throw RemoteException("Jeton absent ou expiré sur ${device.name} : reconnectez-vous")
        // Jellyfin et Emby acceptent les comptes sans mot de passe.
        userId = try {
            client.authenticate(user, device.extras[EXTRA_PASSWORD].orEmpty()).user.id
        } catch (e: HttpStatusException) {
            if (e.code != 401) throw e
            throw RemoteException("Identifiants refusés par ${device.name} : reconnectez-vous", e)
        }
    }

    private suspend fun session(): String {
        sessionId?.let { return it }
        return mutex.withLock {
            sessionId ?: selectSession(client.sessions(userId)).also { sessionId = it }
        }
    }

    /** Choisit une session contrôlable autre que la nôtre, de préférence en cours de lecture. */
    internal fun selectSession(sessions: List<SessionDto>): String {
        val candidates = sessions.filter { it.supportsRemoteControl && (ownDeviceId == null || it.deviceId != ownDeviceId) }
        val target = candidates.firstOrNull { it.nowPlaying != null } ?: candidates.firstOrNull()
            ?: throw RemoteException("Aucun client contrôlable sur ${device.name}")
        return target.id
    }

    /** Oublie la session [stale] si c'est encore la session courante. */
    private suspend fun forgetSession(stale: String) = mutex.withLock {
        if (sessionId == stale) sessionId = null
        if (pinnedSessionId == stale) pinnedSessionId = null
    }

    /**
     * Renouvelle le jeton [refused] s'il est encore le jeton courant : des requêtes parallèles
     * refusées avec le même jeton ne déclenchent qu'une seule authentification. Le jeton refusé
     * reste en place pendant l'authentification et n'est oublié qu'en cas d'échec.
     */
    private suspend fun renewToken(refused: String?) = mutex.withLock {
        if (client.accessToken == refused) {
            try {
                authenticateLocked()
            } catch (e: Throwable) {
                if (client.accessToken == refused) client.accessToken = null
                throw e
            }
        }
    }

    /**
     * Exécute [action] sur la session courante. Au plus une nouvelle authentification (401) et
     * une nouvelle sélection de session (404) : un second 401 ou un second 404 est remonté tel quel,
     * sans boucle.
     */
    private suspend fun <T> withSession(action: suspend (String) -> T): T {
        var renewed = false
        var reselected = false
        while (true) {
            val token = client.accessToken
            val id = session()
            try {
                return action(id)
            } catch (e: HttpStatusException) {
                when {
                    e.code == 401 && !renewed -> {
                        renewed = true
                        renewToken(token)
                    }
                    e.code == 404 && !reselected -> {
                        reselected = true
                        forgetSession(id)
                    }
                    else -> throw e
                }
            }
        }
    }

    override suspend fun send(key: RemoteKey) {
        requireKey(key)
        withSession { id ->
            when (key) {
                RemoteKey.UP -> client.generalCommand(id, "MoveUp")
                RemoteKey.DOWN -> client.generalCommand(id, "MoveDown")
                RemoteKey.LEFT -> client.generalCommand(id, "MoveLeft")
                RemoteKey.RIGHT -> client.generalCommand(id, "MoveRight")
                RemoteKey.SELECT -> client.generalCommand(id, "Select")
                RemoteKey.BACK -> client.generalCommand(id, "Back")
                RemoteKey.HOME -> client.generalCommand(id, "GoHome")
                RemoteKey.MENU -> client.generalCommand(id, "ToggleContextMenu")
                RemoteKey.INFO -> client.generalCommand(id, "ToggleOsdMenu")
                RemoteKey.VOLUME_UP -> client.generalCommand(id, "VolumeUp")
                RemoteKey.VOLUME_DOWN -> client.generalCommand(id, "VolumeDown")
                RemoteKey.MUTE -> client.generalCommand(id, "ToggleMute")
                RemoteKey.PLAY_PAUSE -> client.playstate(id, "PlayPause")
                RemoteKey.STOP -> client.playstate(id, "Stop")
                RemoteKey.NEXT -> client.playstate(id, "NextTrack")
                RemoteKey.PREVIOUS -> client.playstate(id, "PreviousTrack")
                RemoteKey.REWIND -> client.playstate(id, "Rewind")
                RemoteKey.FAST_FORWARD -> client.playstate(id, "FastForward")
            }
        }
    }

    override suspend fun setVolume(percent: Int) {
        withSession { client.generalCommand(it, "SetVolume", mapOf("Volume" to percent.coerceIn(0, 100).toString())) }
    }

    override suspend fun seekTo(positionMs: Long) {
        withSession { client.playstate(it, "Seek", seekPositionTicks = positionMs.coerceAtLeast(0) * TICKS_PER_MS) }
    }

    override suspend fun sendText(text: String) {
        withSession { client.generalCommand(it, "SendString", mapOf("String" to text)) }
    }

    override fun playbackState(): Flow<PlaybackState> = flow {
        while (true) {
            val s = withSession { id ->
                client.sessions(userId).firstOrNull { it.id == id }.also {
                    // Session disparue : nouvelle sélection au prochain tour.
                    if (it == null) forgetSession(id)
                }
            }
            emit(
                PlaybackState(
                    title = s?.nowPlaying?.name,
                    isPlaying = s?.nowPlaying != null && s.playState?.isPaused == false,
                    positionMs = (s?.playState?.positionTicks ?: 0) / TICKS_PER_MS,
                    durationMs = (s?.nowPlaying?.runTimeTicks ?: 0) / TICKS_PER_MS,
                    volumePercent = s?.playState?.volumeLevel,
                    muted = s?.playState?.isMuted ?: false,
                ),
            )
            delay(pollIntervalMs)
        }
    }

    companion object {
        const val EXTRA_USERNAME = "username"
        const val EXTRA_PASSWORD = "password"
        const val EXTRA_USER_ID = "userId"
        const val EXTRA_SESSION_ID = "sessionId"
        const val EXTRA_TOKEN = "token"

        /** Chemin de base de l'API (« URL de base » du serveur), ex. `/jellyfin`. */
        const val EXTRA_BASE_PATH = "base_path"

        private val BASE_PATH_CANDIDATES = MediaServerClient.DEFAULT_BASE_PATHS
        private val BASE_PATH = Regex("^(/[A-Za-z0-9._~-]+)+$")

        /** Chemin de base normalisé (`/a/b`), ou `null` s'il est vide ou invalide. */
        fun normalizeBasePath(raw: String?): String? {
            val trimmed = raw?.trim()?.trim('/')?.takeIf { it.isNotEmpty() } ?: return null
            return "/$trimmed".takeIf(BASE_PATH::matches)
        }

        /**
         * URL du serveur : [Device.baseUrl] impose `http` ; le schéma `https` annoncé à la
         * découverte (extra [MediaServerDiscovery.EXTRA_SCHEME]) est respecté.
         */
        fun baseUrl(device: Device): String {
            val root = if (device.extras[MediaServerDiscovery.EXTRA_SCHEME] == "https") {
                "https://" + device.baseUrl.removePrefix("http://")
            } else {
                device.baseUrl
            }
            return root + normalizeBasePath(device.extras[EXTRA_BASE_PATH]).orEmpty()
        }

        /** Description lisible des informations publiques du serveur (logique pure). */
        fun describe(kind: DeviceKind, info: PublicInfoDto): String {
            val product = info.productName?.takeIf { it.isNotBlank() } ?: kind.label
            val head = listOfNotNull(product, info.version?.takeIf { it.isNotBlank() }).joinToString(" ")
            return info.serverName?.takeIf { it.isNotBlank() }?.let { "$head – $it" } ?: head
        }

        fun create(device: Device, http: OkHttpClient, deviceId: String): MediaServerController {
            val flavor = if (device.kind == DeviceKind.EMBY) MediaServerFlavor.EMBY else MediaServerFlavor.JELLYFIN
            val client = MediaServerClient(http, baseUrl(device), flavor, deviceId, device.extras[EXTRA_TOKEN])
            return MediaServerController(device, client, ownDeviceId = deviceId)
        }
    }
}
