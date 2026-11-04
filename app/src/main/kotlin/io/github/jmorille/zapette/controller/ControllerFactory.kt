package io.github.jmorille.zapette.controller

import android.content.Context
import io.github.jmorille.zapette.airplay.AirPlayController
import io.github.jmorille.zapette.cast.CastController
import io.github.jmorille.zapette.data.DeviceRepository
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

/** Instancie la télécommande adaptée au protocole de l'appareil. */
class ControllerFactory(
    private val context: Context,
    private val http: OkHttpClient,
    private val repository: DeviceRepository,
) {
    suspend fun create(device: Device): RemoteController = when (device.kind) {
        DeviceKind.KODI -> KodiController.create(device, http)
        DeviceKind.JELLYFIN, DeviceKind.EMBY ->
            MediaServerController.create(device, http, repository.installationId())
        DeviceKind.PLEX -> {
            val token = device.extras[PlexController.EXTRA_TOKEN] ?: repository.plexToken.first()
            val withToken = if (token != null) device.copy(extras = device.extras + (PlexController.EXTRA_TOKEN to token)) else device
            PlexController(withToken, http, PlexIdentity(repository.installationId()))
        }
        DeviceKind.UPNP_RENDERER -> UpnpRendererController(device, http)
        DeviceKind.ROKU -> RokuController(device, http)
        DeviceKind.AIRPLAY -> AirPlayController(device, http)
        DeviceKind.FIRE_TV -> FireTvController(device, DadbShell(device.host, device.port, File(context.filesDir, "adb")))
        DeviceKind.CHROMECAST -> CastController(device, context)
    }

    companion object {
        /** Types qui demandent une saisie (identifiants) avant la première utilisation. */
        fun needsSetup(device: Device): Boolean = when (device.kind) {
            DeviceKind.JELLYFIN, DeviceKind.EMBY ->
                device.extras[MediaServerController.EXTRA_TOKEN] == null &&
                    device.extras[MediaServerController.EXTRA_USERNAME] == null
            else -> false
        }
    }
}
