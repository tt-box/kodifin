package io.github.jmorille.zapette.ui.config.accounts

import io.github.jmorille.zapette.mediaserver.MediaServerClient
import io.github.jmorille.zapette.mediaserver.MediaServerFlavor
import io.github.jmorille.zapette.mediaserver.PublicUserDto
import io.github.jmorille.zapette.mediaserver.QuickConnect
import io.github.jmorille.zapette.model.DeviceKind
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import kotlin.coroutines.cancellation.CancellationException

/*
 * Logique sans Android des composables de comptes Jellyfin/Emby (testée sur la JVM).
 * Aucun jeton, secret ni mot de passe n'est journalisé ou affiché.
 */

/** Variante d'API correspondant au type d'appareil. */
internal fun flavorOf(kind: DeviceKind): MediaServerFlavor =
    if (kind == DeviceKind.EMBY) MediaServerFlavor.EMBY else MediaServerFlavor.JELLYFIN

internal fun mediaServerClient(kind: DeviceKind, baseUrl: String, http: OkHttpClient, deviceId: String) =
    MediaServerClient(http, baseUrl, flavorOf(kind), deviceId)

/** État de la liste des utilisateurs publics. */
internal sealed interface PublicUsersState {
    data object Idle : PublicUsersState
    data object Loading : PublicUsersState
    data class Loaded(val users: List<PublicUserDto>) : PublicUsersState
    data class Failed(val message: String) : PublicUsersState
}

/**
 * Utilisateurs publics du serveur ; le chemin de base usuel (`/jellyfin`, `/emby`) est
 * essayé si l'URL saisie ne répond pas comme un serveur.
 */
internal suspend fun loadPublicUsers(client: MediaServerClient): PublicUsersState = try {
    if (client.locateBasePath() == null) {
        PublicUsersState.Failed("Serveur introuvable à cette adresse : vérifiez l'hôte, le port et le chemin de base.")
    } else {
        PublicUsersState.Loaded(client.publicUsers())
    }
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    PublicUsersState.Failed(e.message ?: "Serveur injoignable")
}

/** État du panneau Quick Connect. */
internal sealed interface QuickConnectState {
    data object Idle : QuickConnectState
    data object Starting : QuickConnectState
    data class WaitingForApproval(val code: String) : QuickConnectState
    data class Authenticated(val userName: String) : QuickConnectState
    data class Failed(val message: String) : QuickConnectState
}

/** Résultat transmis à l'appelant : jeton à enregistrer dans les extras de l'appareil. */
internal data class QuickConnectLogin(val token: String, val userId: String, val userName: String) {
    override fun toString() = "QuickConnectLogin(token=***, userId=$userId, userName=$userName)"
}

/**
 * Déroule Quick Connect : recherche du chemin de base, ouverture de la demande, attente de
 * l'approbation. Le réseau passe par [io] ; [onState] est appelé dans le contexte de l'appelant
 * (fil principal pour un composable). Renvoie l'identité obtenue ou `null` en cas d'échec.
 */
internal suspend fun runQuickConnect(
    client: MediaServerClient,
    quickConnect: QuickConnect,
    io: CoroutineDispatcher,
    onState: (QuickConnectState) -> Unit,
): QuickConnectLogin? {
    onState(QuickConnectState.Starting)
    return try {
        if (withContext(io) { client.locateBasePath() } == null) {
            onState(QuickConnectState.Failed("Serveur Jellyfin introuvable à cette adresse."))
            return null
        }
        val request = withContext(io) { quickConnect.start() }
        onState(QuickConnectState.WaitingForApproval(request.code))
        val auth = withContext(io) { quickConnect.awaitApproval(request) }
        val name = auth.user.name.ifBlank { auth.user.id }
        onState(QuickConnectState.Authenticated(name))
        QuickConnectLogin(auth.accessToken, auth.user.id, name)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        onState(QuickConnectState.Failed(e.message ?: "Serveur injoignable"))
        null
    }
}
