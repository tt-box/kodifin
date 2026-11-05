package io.github.jmorille.zapette.controller

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import io.github.jmorille.zapette.model.fetchImage
import io.github.jmorille.zapette.data.SettingsRepository
import io.github.jmorille.zapette.kodi.KodiEventServer
import android.content.Context
import io.github.jmorille.zapette.airplay.AirPlayController
import io.github.jmorille.zapette.cast.CastController
import io.github.jmorille.zapette.data.DeviceRepository
import io.github.jmorille.zapette.data.DeviceSecrets
import io.github.jmorille.zapette.data.SecretStore
import io.github.jmorille.zapette.firetv.DadbShell
import io.github.jmorille.zapette.firetv.FireTvController
import io.github.jmorille.zapette.connection.DeviceOptions
import io.github.jmorille.zapette.connection.forDevice
import io.github.jmorille.zapette.kodi.KodiClient
import io.github.jmorille.zapette.kodi.KodiController
import io.github.jmorille.zapette.kodi.KodiHttpTransport
import io.github.jmorille.zapette.kodi.KodiImageFetcher
import io.github.jmorille.zapette.kodi.KodiWebSocketTransport
import io.github.jmorille.zapette.mediaserver.MediaServerController
import io.github.jmorille.zapette.model.Device
import io.github.jmorille.zapette.model.DeviceKind
import io.github.jmorille.zapette.model.RemoteController
import io.github.jmorille.zapette.plex.PlexController
import io.github.jmorille.zapette.plex.PlexIdentity
import io.github.jmorille.zapette.roku.RokuController
import io.github.jmorille.zapette.upnp.UpnpRendererController
import kotlinx.coroutines.flow.first
import okhttp3.OkHttpClient
import java.io.File

/** Contrôleurs qui exigent le framework Android (Context), isolés pour tester le reste en JVM. */
interface PlatformControllers {
    fun fireTv(device: Device): RemoteController
    fun cast(device: Device): RemoteController
}

/** Implémentation réelle : clés ADB dans le stockage privé, Cast via le SDK Google. */
class AndroidPlatformControllers(
    private val context: Context,
    private val settings: SettingsRepository,
    /** Client des pochettes Cast (sans cookie ni authentification) ; `null` : pas d'images. */
    private val imageHttp: OkHttpClient? = null,
) : PlatformControllers {
    override fun fireTv(device: Device): RemoteController =
        FireTvController(device, DadbShell(device.host, device.port, File(context.filesDir, "adb"), DeviceOptions.timeoutSeconds(device) * 1000, device.extras[DadbShell.EXTRA_TLS] == "true"))

    override fun cast(device: Device): RemoteController = CastController(
        device, context,
        settings = { settings.cast.first() },
        imageFetcher = imageHttp?.let { client -> { url: String -> client.fetchImage(url) } },
    )
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
