package io.github.ttbox.kodifin.controller

import android.content.Context
import io.github.ttbox.kodifin.cast.CastController
import io.github.ttbox.kodifin.connection.DeviceOptions
import io.github.ttbox.kodifin.data.SettingsRepository
import io.github.ttbox.kodifin.firetv.DadbShell
import io.github.ttbox.kodifin.firetv.FireTvController
import io.github.ttbox.kodifin.model.Device
import io.github.ttbox.kodifin.model.RemoteController
import io.github.ttbox.kodifin.model.fetchImage
import kotlinx.coroutines.flow.first
import okhttp3.OkHttpClient
import java.io.File

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
