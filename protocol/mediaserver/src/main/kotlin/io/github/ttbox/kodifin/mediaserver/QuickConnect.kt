package io.github.ttbox.kodifin.mediaserver

import io.github.ttbox.kodifin.model.HttpStatusException
import io.github.ttbox.kodifin.model.RemoteError
import io.github.ttbox.kodifin.model.RemoteException
import io.github.ttbox.kodifin.model.Setup
import kotlinx.coroutines.delay

/** Quick Connect désactivé (ou absent : Emby, Jellyfin ancien) sur le serveur. */
class QuickConnectUnavailableException : RemoteException(RemoteError.SetupRequired(Setup.QUICK_CONNECT_DISABLED))

/** Code non approuvé à temps, ou demande oubliée par le serveur (404). */
class QuickConnectExpiredException : RemoteException(RemoteError.CodeExpired)

/**
 * Connexion Jellyfin sans mot de passe (API publique « Quick Connect ») :
 * 1. [start] vérifie l'activation (`GET /QuickConnect/Enabled`) et ouvre une demande
 *    (`POST /QuickConnect/Initiate`) dont le code est affiché à l'utilisateur ;
 * 2. l'utilisateur saisit ce code dans l'interface Jellyfin d'un appareil déjà connecté ;
 * 3. [awaitApproval] sonde `GET /QuickConnect/Connect?secret=…` puis échange le secret contre
 *    un jeton (`POST /Users/AuthenticateWithQuickConnect`).
 *
 * Le secret ne quitte jamais le client HTTP fourni et n'est pas journalisé.
 */
class QuickConnect(
    private val client: MediaServerClient,
    private val pollIntervalMs: Long = DEFAULT_POLL_INTERVAL_MS,
    /** Délai au-delà duquel le code est considéré expiré (Jellyfin l'oublie après quelques minutes). */
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    /** Attente entre deux sondages, remplaçable dans les tests. */
    private val sleep: suspend (Long) -> Unit = { delay(it) },
) {
    init {
        require(pollIntervalMs > 0) { "pollIntervalMs doit être strictement positif" }
        require(timeoutMs >= pollIntervalMs) { "timeoutMs doit couvrir au moins un sondage" }
    }

    /** Ouvre une demande ; échoue avec [QuickConnectUnavailableException] si la fonction est coupée. */
    suspend fun start(): QuickConnectDto {
        val enabled = try {
            client.quickConnectEnabled()
        } catch (e: HttpStatusException) {
            // Point d'accès inconnu : serveur Emby ou Jellyfin trop ancien.
            if (e.code == 404) false else throw e
        }
        if (!enabled) throw QuickConnectUnavailableException()
        return try {
            client.quickConnectInitiate()
        } catch (e: HttpStatusException) {
            // Jellyfin répond 401/403 quand l'administrateur a désactivé la fonction entre-temps.
            if (e.code == 401 || e.code == 403) throw QuickConnectUnavailableException() else throw e
        }
    }

    /** Attend l'approbation du code de [request] puis renvoie le jeton obtenu. */
    suspend fun awaitApproval(request: QuickConnectDto): AuthResult {
        var waited = 0L
        while (waited < timeoutMs) {
            sleep(pollIntervalMs)
            waited += pollIntervalMs
            val state = try {
                client.quickConnectState(request.secret)
            } catch (e: HttpStatusException) {
                if (e.code == 404) throw QuickConnectExpiredException() else throw e
            }
            if (state.authenticated) return client.authenticateWithQuickConnect(request.secret)
        }
        throw QuickConnectExpiredException()
    }

    companion object {
        const val DEFAULT_POLL_INTERVAL_MS = 3_000L
        const val DEFAULT_TIMEOUT_MS = 5 * 60_000L
    }
}
