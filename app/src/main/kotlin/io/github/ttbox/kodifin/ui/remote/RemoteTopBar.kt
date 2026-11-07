package io.github.ttbox.kodifin.ui.remote

import io.github.ttbox.kodifin.ui.devices.deviceIcon
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.StopScreenShare
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Message
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.ttbox.kodifin.connection.DeviceOptions
import io.github.ttbox.kodifin.model.Capability
import io.github.ttbox.kodifin.model.PowerAction
import io.github.ttbox.kodifin.model.UiCommand
import androidx.compose.ui.res.stringResource
import io.github.ttbox.kodifin.R
import io.github.ttbox.kodifin.ui.text.asString
import io.github.ttbox.kodifin.text.text

// --- Barre du haut et feuille « ⋮ » -----------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RemoteTopBar(
    state: RemoteUiState,
    onBack: () -> Unit,
    onWake: () -> Unit,
    onPower: (PowerAction) -> Unit,
    onRetry: () -> Unit,
    onOpenTargets: () -> Unit,
    onSelectTarget: (String) -> Unit,
    onMore: () -> Unit,
    switchable: List<SwitchEntry> = emptyList(),
    onSwitch: (String) -> Unit = {},
) {
    val status = when {
        state.connecting -> stringResource(R.string.remote_status_connecting)
        !state.connected -> stringResource(R.string.remote_status_disconnected)
        else -> state.targets.current?.let { stringResource(R.string.remote_status_target, it.name) }
    }
    TopAppBar(
        title = {
            DeviceSwitcher(state.device?.name ?: stringResource(R.string.remote_title_default), status, switchable, onSwitch)
        },
        navigationIcon = {
            WithTooltip(stringResource(R.string.remote_back_home)) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.remote_back)) }
            }
        },
        actions = {
            if (state.features.targets && state.connected) TargetsMenu(state.targets, onOpenTargets, onSelectTarget)
            val canWake = DeviceOptions.wakeTarget(state.device) != null
            if (canWake || state.features.powerActions.isNotEmpty()) {
                PowerMenu(canWake, state.features.powerActions, onWake, onPower)
            }
            if (!state.connected && !state.connecting) {
                val reconnect = stringResource(R.string.remote_reconnect)
                WithTooltip(reconnect) {
                    IconButton(onClick = onRetry) { Icon(Icons.Filled.Refresh, contentDescription = reconnect) }
                }
            }
            val more = stringResource(R.string.remote_more_actions)
            WithTooltip(more) {
                IconButton(onClick = onMore) { Icon(Icons.Filled.MoreVert, contentDescription = more) }
            }
        },
    )
}

/**
 * Titre de la barre : nom de l'appareil et, s'il y en a d'autres, menu des télécommandes
 * enregistrées pour passer de l'une à l'autre sans revenir à l'accueil.
 */
@Composable
private fun DeviceSwitcher(name: String, status: String?, entries: List<SwitchEntry>, onSwitch: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val others = entries.count { !it.current }
    val switchLabel = stringResource(R.string.remote_switch_remote)
    Box {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = if (others > 0) {
                Modifier.clip(MaterialTheme.shapes.small)
                    .clickable(onClickLabel = switchLabel) { open = true }
                    .padding(end = 4.dp)
            } else {
                Modifier
            },
        ) {
            Column(Modifier.weight(1f, fill = false)) {
                Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                status?.let {
                    Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                }
            }
            if (others > 0) Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            Text(
                stringResource(R.string.remote_switch_header),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            entries.forEach { entry ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(entry.device.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                entry.detail,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                            )
                        }
                    },
                    leadingIcon = { Icon(deviceIcon(entry.device.kind), contentDescription = null) },
                    trailingIcon = if (entry.current) ({ Icon(Icons.Filled.Check, contentDescription = stringResource(R.string.remote_switch_current)) }) else null,
                    enabled = !entry.current,
                    onClick = { open = false; onSwitch(entry.device.id) },
                )
            }
        }
    }
}

@Composable
private fun TargetsMenu(targets: TargetsState, onOpen: () -> Unit, onSelect: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        val targetLabel = stringResource(R.string.remote_target)
        WithTooltip(targetLabel) {
            IconButton(onClick = { open = true; onOpen() }) { Icon(Icons.Filled.Devices, contentDescription = targetLabel) }
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            if (targets.items.isEmpty()) {
                DropdownMenuItem(text = { Text(stringResource(R.string.remote_target_none)) }, onClick = { open = false }, enabled = false)
            }
            targets.items.forEach { target ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(target.name)
                            target.detail?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                        }
                    },
                    leadingIcon = if (target.id == targets.currentId) {
                        { Icon(Icons.Filled.Check, contentDescription = stringResource(R.string.remote_target_current)) }
                    } else {
                        null
                    },
                    onClick = { open = false; onSelect(target.id) },
                )
            }
        }
    }
}

/** Menu « Alimentation » : réveil Wake-on-LAN puis actions annoncées par l'appareil (confirmées). */
@Composable
private fun PowerMenu(canWake: Boolean, actions: Set<PowerAction>, onWake: () -> Unit, onPower: (PowerAction) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        val powerLabel = stringResource(R.string.remote_power)
        WithTooltip(powerLabel) {
            IconButton(onClick = { open = true }) { Icon(Icons.Filled.PowerSettingsNew, contentDescription = powerLabel) }
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            if (canWake) {
                DropdownMenuItem(text = { Text(stringResource(R.string.remote_power_wake)) }, onClick = { open = false; onWake() })
            }
            PowerAction.entries.filter { it in actions }.forEach { action ->
                DropdownMenuItem(text = { Text(action.text.asString()) }, onClick = { open = false; onPower(action) })
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MoreSheet(
    state: RemoteUiState,
    onDismiss: () -> Unit,
    onDialog: (RemoteDialog) -> Unit,
    onCommand: (UiCommand) -> Unit,
    fullscreen: Boolean?,
    onFullscreen: (Boolean) -> Unit,
    onScan: () -> Unit,
    onEdit: () -> Unit,
    onSendFile: () -> Unit,
    onTransfer: () -> Unit,
    onStopSharing: () -> Unit,
) {
    // Contenu figé à l'ouverture : l'état (position, commandes de la session) change chaque
    // seconde ; sans cela la hauteur de la feuille varie et elle saute de haut en bas.
    val frozen = remember { state }
    @Suppress("NAME_SHADOWING") val state = frozen
    // Ouverte en entier d'emblée : pas d'état « à moitié déplié » qui se recale à chaque mesure.
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(bottom = 16.dp)) {
            if (Capability.TEXT_INPUT in state.capabilities) {
                SheetItem(Icons.Filled.Keyboard, stringResource(R.string.remote_more_type_text)) { onDialog(RemoteDialog.Text) }
            }
            if (Capability.PLAY_URL in state.capabilities) {
                SheetItem(Icons.Filled.Link, stringResource(R.string.remote_play_url)) { onDialog(RemoteDialog.Url) }
                SheetItem(Icons.Filled.FileUpload, stringResource(R.string.remote_more_send_file), onSendFile)
            }
            if (state.sharing) {
                SheetItem(Icons.Filled.StopScreenShare, stringResource(R.string.remote_more_stop_sharing), onStopSharing)
            }
            if (state.features.transferOut) {
                SheetItem(Icons.Filled.Devices, stringResource(R.string.remote_more_transfer), onTransfer)
            }
            if (state.features.notify) {
                SheetItem(Icons.AutoMirrored.Filled.Message, stringResource(R.string.remote_notify_title)) { onDialog(RemoteDialog.Notify) }
            }
            if (state.features.libraryMaintenance) {
                SheetItem(Icons.Filled.Sync, stringResource(R.string.remote_more_scan_library), onScan)
            }
            if (state.features.cleanLibrary) {
                SheetItem(Icons.Filled.CleaningServices, stringResource(R.string.remote_more_clean_library)) { onDialog(RemoteDialog.CleanLibrary) }
            }
            if (state.features.uiCommands.isNotEmpty()) {
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                Text(
                    stringResource(R.string.remote_device_commands),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
                UiCommandChips(
                    state.features.uiCommands, onCommand, Modifier.padding(horizontal = 16.dp),
                    fullscreen = fullscreen,
                    onFullscreen = onFullscreen.takeIf { state.features.fullscreen },
                )
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
            }
            SheetItem(Icons.Filled.Edit, stringResource(R.string.remote_more_edit), onEdit)
        }
    }
}

@Composable
private fun SheetItem(icon: ImageVector, label: String, onClick: () -> Unit) {
    Surface(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        ListItem(headlineContent = { Text(label) }, leadingContent = { Icon(icon, contentDescription = null) })
    }
}

@Composable
internal fun NetworkBlockedCard(reason: String) {
    Card(Modifier.fillMaxWidth().padding(16.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(reason, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.error)
            Text(
                stringResource(R.string.remote_network_blocked_hint),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}
