package io.github.jmorille.zapette.controller

import android.content.Context
import io.github.jmorille.zapette.airplay.AirPlayController
import io.github.jmorille.zapette.cast.CastController
import io.github.jmorille.zapette.data.DeviceRepository
import io.github.jmorille.zapette.data.DeviceSecrets
import io.github.jmorille.zapette.data.SecretStore
import io.github.jmorille.zapette.firetv.DadbShell
import io.github.jmorille.zapette.firetv.FireTvController
import io.github.jmorille.zapette.kodi.KodiController
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
class AndroidPlatformControllers(private val context: Context) : PlatformControllers {
    override fun fireTv(device: Device): RemoteController =
        FireTvController(device, DadbShell(device.host, device.port, File(context.filesDir, "adb")))

    override fun cast(device: Device): RemoteController = CastController(device, context)
}

/** Instancie la télécommande adaptée au protocole de l'appareil. */
class ControllerFactory(
    private val http: OkHttpClient,
    private val repository: DeviceRepository,
    private val secrets: SecretStore,
    private val platform: PlatformControllers,
) {
    suspend fun create(device: Device): RemoteController = when (device.kind) {
        // Mot de passe Kodi déchiffré ici seulement, le temps de construire le transport.
        DeviceKind.KODI -> KodiController.create(DeviceSecrets.reveal(device, secrets), http)
        DeviceKind.JELLYFIN, DeviceKind.EMBY ->
            MediaServerController.create(device, http, repository.installationId())
        DeviceKind.PLEX ->
            PlexController(withPlexToken(device, repository.plexToken.first()), http, PlexIdentity(repository.installationId()))
        DeviceKind.UPNP_RENDERER -> UpnpRendererController(device, http)
        DeviceKind.ROKU -> RokuController(device, http)
        DeviceKind.AIRPLAY -> AirPlayController(device, http)
        DeviceKind.FIRE_TV -> platform.fireTv(device)
        DeviceKind.CHROMECAST -> platform.cast(device)
    }

    companion object {
        /** Types qui demandent une saisie (identifiants) avant la première utilisation. */
        fun needsSetup(device: Device): Boolean = when (device.kind) {
            DeviceKind.JELLYFIN, DeviceKind.EMBY ->
                device.extras[MediaServerController.EXTRA_TOKEN] == null &&
                    device.extras[MediaServerController.EXTRA_USERNAME] == null
            else -> false
        }

        /**
         * Jeton Plex à utiliser : celui de l'appareil est prioritaire ; à défaut, le jeton du
         * compte lié ([stored]) est ajouté ; sans aucun jeton, l'appareil est inchangé.
         */
        fun withPlexToken(device: Device, stored: String?): Device {
            if (device.extras[PlexController.EXTRA_TOKEN] != null || stored == null) return device
            return device.copy(extras = device.extras + (PlexController.EXTRA_TOKEN to stored))
        }
    }
}
