package io.github.ttbox.kodifin.controller

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import io.github.ttbox.kodifin.kodi.KodiEventServer
import io.github.ttbox.kodifin.airplay.AirPlayController
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
import io.github.ttbox.kodifin.plex.PlexController
import io.github.ttbox.kodifin.plex.PlexIdentity
import io.github.ttbox.kodifin.roku.RokuController
import io.github.ttbox.kodifin.upnp.UpnpRendererController
import okhttp3.OkHttpClient

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
        val effective = DeviceSecrets.reveal(withInput, secrets)
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

    /** Jeton du compte Plex lié, déchiffré pour ce seul contrôleur (`null` : aucun ou illisible). */
    private suspend fun plexAccountToken(): String? = repository.sealedPlexToken()?.let(secrets::decrypt)

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
