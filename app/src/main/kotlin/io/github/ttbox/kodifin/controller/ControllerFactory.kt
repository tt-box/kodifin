package io.github.ttbox.kodifin.controller

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import io.github.ttbox.kodifin.kodi.KodiEventServer
import io.github.ttbox.kodifin.airplay.AirPlayController
import io.github.ttbox.kodifin.data.Decryption
import io.github.ttbox.kodifin.data.DeviceRepository
import io.github.ttbox.kodifin.data.DeviceSecrets
import io.github.ttbox.kodifin.data.SecretStore
import io.github.ttbox.kodifin.connection.DeviceOptions
import io.github.ttbox.kodifin.connection.forDevice
import io.github.ttbox.kodifin.kodi.KodiClient
import io.github.ttbox.kodifin.kodi.KodiController
import io.github.ttbox.kodifin.kodi.KodiHttpTransport
import io.github.ttbox.kodifin.kodi.KodiImageFetcher
import io.github.ttbox.kodifin.kodi.KodiWebSocketTransport
import io.github.ttbox.kodifin.mediaserver.MediaServerController
import io.github.ttbox.kodifin.model.Device
import io.github.ttbox.kodifin.model.DeviceKind
import io.github.ttbox.kodifin.model.RemoteController
import io.github.ttbox.kodifin.model.RemoteError
import io.github.ttbox.kodifin.model.RemoteException
import io.github.ttbox.kodifin.plex.PlexController
import io.github.ttbox.kodifin.plex.PlexIdentity
import io.github.ttbox.kodifin.roku.RokuController
import io.github.ttbox.kodifin.upnp.UpnpRendererController
import okhttp3.OkHttpClient
import kotlin.coroutines.cancellation.CancellationException

/** Contrôleurs qui exigent le framework Android (Context), isolés pour tester le reste en JVM. */
interface PlatformControllers {
    fun fireTv(device: Device): RemoteController
    fun cast(device: Device): RemoteController
}

/** Instancie la télécommande adaptée au protocole de l'appareil. */
class ControllerFactory(
    private val http: OkHttpClient,
    private val repository: DeviceRepository,
    private val secrets: SecretStore,
    private val platform: PlatformControllers,
) {
    /**
     * [password] : mot de passe saisi mais pas encore enregistré (bouton « Tester la connexion »),
     * utilisé en mémoire uniquement à la place du mot de passe stocké.
     */
    suspend fun create(device: Device, password: String? = null): RemoteController {
        val withInput = if (password != null) withPassword(device, password) else device
        // Client propre à l'appareil : délai de connexion et, si demandé pour CET appareil,
        // certificat non vérifié (épinglé s'il a reçu la confiance). Le client global n'est jamais modifié.
        val http = httpFor(withInput)
        // Mot de passe Kodi et jetons (Jellyfin/Emby, Plex) déchiffrés ici seulement, le temps de
        // construire le contrôleur ; les extras qu'il renvoie à enregistrer sont rechiffrés par le dépôt.
        val revealed = DeviceSecrets.reveal(withInput, secrets)
        if (revealed.unavailable.isNotEmpty()) {
            // Keystore momentanément indisponible : surtout ne rien effacer (le secret est
            // peut-être intact), la connexion échoue avec une erreur qu'on peut réessayer.
            throw RemoteException(RemoteError.SecretStoreUnavailable)
        }
        if (revealed.unreadable.isNotEmpty()) {
            // Secret enregistré illisible (clé du Keystore perdue…) : pas de connexion sans lui
            // (échec d'authentification incompréhensible), il est purgé pour que la fiche de
            // l'appareil n'annonce plus de mot de passe enregistré, et l'utilisateur est invité
            // à le ressaisir.
            try {
                repository.dropUnreadableSecrets(withInput.id, revealed.unreadable)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Écriture impossible : on réessaiera à la prochaine connexion, l'erreur reste la même.
            }
            throw RemoteException(RemoteError.StoredSecretUnreadable)
        }
        val effective = revealed.device
        return when (effective.kind) {
            DeviceKind.KODI -> kodi(effective, http)
            DeviceKind.JELLYFIN, DeviceKind.EMBY ->
                MediaServerController.create(effective, http, repository.installationId())
            else -> createOther(effective, http)
        }
    }

    /** Client HTTP dérivé utilisé pour [device] (exposé pour les tests). */
    fun httpFor(device: Device): OkHttpClient = http.forDevice(device)

    private fun kodi(device: Device, http: OkHttpClient): RemoteController {
        val user = device.extras[KodiController.EXTRA_USERNAME]
        val pass = device.extras[KodiController.EXTRA_PASSWORD]
        val wsUrl = DeviceOptions.kodiWebSocketUrl(device)
        val transport = wsUrl?.let { KodiWebSocketTransport(http, it) }
            ?: KodiHttpTransport(http, DeviceOptions.httpBaseUrl(device), user, pass)
        // WebSocket : images via le serveur web HTTP du même appareil (en HTTP, le transport les fournit).
        val images = if (wsUrl != null) KodiImageFetcher(http, DeviceOptions.httpBaseUrl(device), user, pass)::fetch else null
        return KodiController(
            device, KodiClient(transport),
            eventServer = KodiEventServer.fromDevice(device),
            imageFetcher = images,
            webBaseUrl = kodiWebBase(DeviceOptions.httpBaseUrl(device), user, pass),
        )
    }

    /**
     * Racine du serveur web de Kodi pour un autre lecteur (transfert de lecture) : identifiants
     * dans l'adresse s'il y en a, car le lecteur cible ne connaît pas l'authentification de Kodi.
     *
     * ATTENTION, choix assumé : lors d'un transfert de lecture depuis un Kodi protégé, l'URL du
     * média envoyée au lecteur cible (Chromecast, UPnP, Roku, AirPlay, autre Kodi…) contient
     * `http://utilisateur:motdepasse@hôte:port/…`, c'est-à-dire le mot de passe Kodi EN CLAIR.
     * Le lecteur cible peut le conserver (historique, journaux) ou l'afficher. C'est le seul
     * moyen pour un lecteur tiers de lire un fichier servi par un Kodi protégé par mot de passe ;
     * le mot de passe ne quitte pas le réseau local (cible choisie par l'utilisateur), mais il
     * vaut mieux un compte Kodi dédié au serveur web, sans autre usage.
     */
    internal fun kodiWebBase(base: String, user: String?, pass: String?): String? {
        val url = base.toHttpUrlOrNull() ?: return null
        if (user.isNullOrEmpty()) return url.toString()
        return url.newBuilder().username(user).password(pass.orEmpty()).build().toString()
    }

    private suspend fun createOther(device: Device, http: OkHttpClient): RemoteController = when (device.kind) {
        DeviceKind.KODI, DeviceKind.JELLYFIN, DeviceKind.EMBY -> error("traité par create()")
        DeviceKind.PLEX ->
            PlexController(withPlexToken(device, plexAccountToken()), http, androidPlexIdentity(repository.installationId()))
        DeviceKind.UPNP_RENDERER -> UpnpRendererController(device, http)
        DeviceKind.ROKU -> RokuController(device, http)
        DeviceKind.AIRPLAY -> AirPlayController(device, http)
        DeviceKind.FIRE_TV -> platform.fireTv(device)
        DeviceKind.CHROMECAST -> platform.cast(device)
    }

    /**
     * Jeton du compte Plex lié, déchiffré pour ce seul contrôleur (`null` : aucun ou illisible).
     * Keystore momentanément indisponible : la connexion échoue (à réessayer) au lieu de partir
     * sans jeton.
     */
    private suspend fun plexAccountToken(): String? {
        val sealed = repository.sealedPlexToken() ?: return null
        return when (val result = secrets.open(sealed)) {
            is Decryption.Plain -> result.value
            Decryption.Lost -> null
            Decryption.Unavailable -> throw RemoteException(RemoteError.SecretStoreUnavailable)
        }
    }

    companion object {
        /** Types qui demandent une saisie (identifiants) avant la première utilisation. */
        fun needsSetup(device: Device): Boolean = when (device.kind) {
            DeviceKind.JELLYFIN, DeviceKind.EMBY ->
                !DeviceSecrets.hasToken(device) && device.extras[MediaServerController.EXTRA_USERNAME] == null
            else -> false
        }

        /** Mot de passe saisi (non enregistré) à la place du mot de passe chiffré. */
        fun withPassword(device: Device, password: String): Device =
            device.copy(extras = device.extras - DeviceSecrets.ENCRYPTED_PASSWORD + (DeviceSecrets.PASSWORD to password))

        /**
         * Jeton Plex à utiliser (appareil déjà déchiffré) : celui de l'appareil est prioritaire ;
         * à défaut, le jeton du compte lié ([stored]) est ajouté ; sans aucun jeton, l'appareil
         * est inchangé.
         */
        fun withPlexToken(device: Device, stored: String?): Device {
            if (device.extras[PlexController.EXTRA_TOKEN] != null || stored == null) return device
            return device.copy(extras = device.extras + (PlexController.EXTRA_TOKEN to stored))
        }
    }
}
