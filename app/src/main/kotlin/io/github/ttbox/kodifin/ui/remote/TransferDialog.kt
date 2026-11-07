package io.github.ttbox.kodifin.ui.remote

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.github.ttbox.kodifin.model.DeviceKind
import io.github.ttbox.kodifin.model.formatHms
import androidx.compose.ui.res.stringResource
import io.github.ttbox.kodifin.R

/** Libellé court de la nature d'un appareil, dans la liste des destinations. */
private fun DeviceKind.shortLabel(): String = when (this) {
    DeviceKind.KODI -> "Kodi"
    DeviceKind.CHROMECAST -> "Chromecast"
    DeviceKind.UPNP_RENDERER -> "DLNA"
    DeviceKind.AIRPLAY -> "AirPlay"
    DeviceKind.FIRE_TV -> "Fire TV"
    DeviceKind.ROKU -> "Roku"
    else -> name
}

/**
 * « Transférer la lecture vers… » : choix de l'appareil, conversion par le serveur (si la source
 * la propose) et arrêt de la lecture ici.
 */
@Composable
fun TransferDialog(
    transfer: TransferState,
    sourceName: String,
    onDismiss: () -> Unit,
    onTransfer: (targetId: String, transcode: Boolean, stopHere: Boolean) -> Unit,
) {
    val media = transfer.media
    // Destinations chargées après l'ouverture : la première est proposée tant qu'aucune n'est choisie.
    var chosen by rememberSaveable { mutableStateOf<String?>(null) }
    val selected = chosen?.takeIf { id -> transfer.targets.any { it.device.id == id } } ?: transfer.targets.firstOrNull()?.device?.id
    var transcode by rememberSaveable { mutableStateOf(false) }
    var stopHere by rememberSaveable { mutableStateOf(true) }
    AlertDialog(
        onDismissRequest = { if (!transfer.busy) onDismiss() },
        title = { Text(stringResource(R.string.remote_transfer_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (transfer.loading || transfer.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                media?.let {
                    val title = it.title ?: stringResource(R.string.remote_transfer_current_media)
                    Text(
                        if (it.positionMs > 0) stringResource(R.string.remote_transfer_resume_at, title, formatHms(it.positionMs)) else title,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                if (!transfer.loading && transfer.targets.isEmpty()) {
                    Text(
                        stringResource(R.string.remote_transfer_no_target),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                transfer.targets.forEach { target ->
                    val id = target.device.id
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .selectable(selected = id == selected, enabled = !transfer.busy, role = Role.RadioButton) { chosen = id },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = id == selected, onClick = null)
                        Column(Modifier.padding(start = 8.dp)) {
                            Text(target.device.name)
                            Text(
                                if (target.bubble) {
                                    stringResource(R.string.remote_transfer_bubble, target.device.kind.shortLabel())
                                } else {
                                    target.device.kind.shortLabel()
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                if (media?.transcodedUrl != null) {
                    SwitchRow(
                        label = stringResource(R.string.remote_transfer_transcode),
                        detail = stringResource(R.string.remote_transfer_transcode_detail),
                        checked = transcode,
                        enabled = !transfer.busy,
                        onChange = { transcode = it },
                    )
                }
                SwitchRow(
                    label = stringResource(R.string.remote_transfer_stop_here, sourceName),
                    detail = null,
                    checked = stopHere,
                    enabled = !transfer.busy,
                    onChange = { stopHere = it },
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { selected?.let { onTransfer(it, transcode, stopHere) } },
                enabled = selected != null && media != null && !transfer.busy,
            ) { Text(stringResource(R.string.remote_transfer_confirm)) }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !transfer.busy) { Text(stringResource(R.string.remote_cancel)) } },
    )
}

@Composable
private fun SwitchRow(label: String, detail: String?, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}
