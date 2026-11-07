package io.github.ttbox.kodifin.ui.remote

import io.github.ttbox.kodifin.model.Capability
import io.github.ttbox.kodifin.model.Device
import io.github.ttbox.kodifin.model.DeviceKind
import io.github.ttbox.kodifin.model.RemoteController
import io.github.ttbox.kodifin.model.TransferableMedia
import io.github.ttbox.kodifin.upnp.UpnpRendererController
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/** Appareil vers lequel reprendre la lecture ; [bubble] : renderer BubbleUPnP Server (conversion à la volée). */
data class TransferTarget(val device: Device, val bubble: Boolean = false)

/** Boîte « Transférer la lecture vers… ». */
data class TransferState(
    val open: Boolean = false,
    val loading: Boolean = false,
    val media: TransferableMedia? = null,
    val targets: List<TransferTarget> = emptyList(),
    val busy: Boolean = false,
)

/** Natures d'appareils qui savent lire une adresse (`playUrl`). */
val TRANSFER_TARGET_KINDS = setOf(
    DeviceKind.KODI, DeviceKind.CHROMECAST, DeviceKind.UPNP_RENDERER,
    DeviceKind.AIRPLAY, DeviceKind.FIRE_TV, DeviceKind.ROKU,
)

/** Destinations possibles : les autres appareils enregistrés capables de lire une adresse. */
fun transferTargets(devices: List<Device>, currentId: String): List<TransferTarget> = devices
    .filter { it.id != currentId && it.kind in TRANSFER_TARGET_KINDS }
    .map { TransferTarget(it, UpnpRendererController.isBubbleUpnpServer(it)) }
    .sortedWith(compareByDescending<TransferTarget> { it.bubble }.thenBy { it.device.name.lowercase() })

/** En deçà, la reprise se fait au début (inutile d'attendre le démarrage pour sauter). */
const val MIN_RESUME_MS = 5_000L

/**
 * Lance [media] sur [target] puis, si le lecteur sait sauter, reprend à la même position une fois
 * la lecture démarrée (durée connue, au plus [settleTimeoutMs]). [transcode] : variante convertie
 * par le serveur, qui démarre déjà à la bonne position.
 */
suspend fun transferPlayback(
    media: TransferableMedia,
    target: RemoteController,
    transcode: Boolean,
    settleTimeoutMs: Long = 20_000,
) {
    target.connect()
    val converted = transcode && media.transcodedUrl != null
    target.playUrl(if (converted) media.transcodedUrl!! else media.url, media.title)
    if (converted || media.positionMs < MIN_RESUME_MS || Capability.SEEK !in target.capabilities) return
    withTimeoutOrNull(settleTimeoutMs) { target.playbackState().first { it.durationMs > 0 } }
    target.seekTo(media.positionMs)
}
