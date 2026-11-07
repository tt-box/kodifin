package io.github.ttbox.kodifin.ui.remote

import io.github.ttbox.kodifin.R
import io.github.ttbox.kodifin.model.DeviceKind
import io.github.ttbox.kodifin.model.RemoteKey
import io.github.ttbox.kodifin.model.SupportsTransferOut
import io.github.ttbox.kodifin.text.UiText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Fichiers du téléphone servis sur le réseau local et « Transférer la lecture vers… » :
 * reprise du média en cours sur un autre appareil enregistré.
 */
class TransferFeature internal constructor(
    private val session: RemoteSession,
    private val deps: RemoteDependencies,
    private val deviceId: String,
) {
    /** Sert le document [uri] sur le réseau local et le fait lire par l'appareil. */
    fun sendLocalFile(uri: String) {
        val share = deps.localShare ?: return session.message(UiText.Res(R.string.remote_share_unavailable))
        session.run { c ->
            val file = share.share(uri)
            session.update { it.copy(sharing = true) }
            c.playUrl(file.url, file.title)
            val target = session.value.device?.name
            session.message(
                if (target != null) UiText.Res(R.string.remote_file_sent_to, file.title, target)
                else UiText.Res(R.string.remote_file_sent, file.title),
            )
        }
    }

    fun stopSharing() {
        deps.localShare?.stop()
        session.update { it.copy(sharing = false) }
        session.message(UiText.Res(R.string.remote_share_stopped))
    }

    /** Ouvre « Transférer la lecture vers… » : média en cours et destinations possibles. */
    fun open() {
        val c = session.connectedAs<SupportsTransferOut>() ?: return session.message(RemoteSession.NOT_SUPPORTED)
        session.update { it.copy(transfer = TransferState(open = true, loading = true)) }
        session.scope.launch {
            val media = session.load { c.currentMedia() }
            val devices = session.load { deps.allDevices() }.orEmpty()
            if (media == null) session.message(UiText.Res(R.string.remote_transfer_no_media))
            session.update {
                it.copy(transfer = TransferState(open = media != null, media = media, targets = transferTargets(devices, deviceId)))
            }
        }
    }

    fun close() = session.update { it.copy(transfer = TransferState()) }

    /**
     * Reprend le média sur [targetId] (à la même position), puis l'arrête ici si [stopHere].
     * La connexion à la cible est refermée ensuite, sauf pour un Chromecast : fermer sa session
     * pourrait interrompre la lecture qui vient d'y démarrer.
     */
    fun transferTo(targetId: String, transcode: Boolean, stopHere: Boolean) {
        val media = session.value.transfer.media ?: return
        session.update { it.copy(transfer = it.transfer.copy(busy = true)) }
        session.scope.launch {
            try {
                val name = withContext(session.io) {
                    val device = deps.loadDevice(targetId) ?: return@withContext null
                    val target = deps.createController(device)
                    try {
                        transferPlayback(media, target, transcode)
                    } finally {
                        if (device.kind != DeviceKind.CHROMECAST) runCatching { target.disconnect() }
                    }
                    val source = session.controller
                    if (stopHere && source != null && RemoteKey.STOP in source.supportedKeys) {
                        runCatching { source.send(RemoteKey.STOP) }
                    }
                    device.name
                }
                if (name == null) {
                    session.update { it.copy(transfer = it.transfer.copy(busy = false)) }
                    session.message(UiText.Res(R.string.remote_device_not_found))
                    return@launch
                }
                session.update { it.copy(transfer = TransferState()) }
                session.message(UiText.Res(R.string.remote_transfer_done, name))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                session.update { it.copy(transfer = it.transfer.copy(busy = false)) }
                session.message(session.errorMessage(e))
            }
        }
    }
}
