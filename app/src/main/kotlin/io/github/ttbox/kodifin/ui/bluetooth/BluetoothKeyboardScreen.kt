package io.github.ttbox.kodifin.ui.bluetooth

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.ttbox.kodifin.R
import io.github.ttbox.kodifin.bluetooth.AndroidHidKeyboard
import io.github.ttbox.kodifin.bluetooth.HidAvailability
import io.github.ttbox.kodifin.bluetooth.HidKey
import io.github.ttbox.kodifin.bluetooth.HidReports
import io.github.ttbox.kodifin.bluetooth.KeyboardLayout
import io.github.ttbox.kodifin.model.RemoteKey
import io.github.ttbox.kodifin.text.UiText
import io.github.ttbox.kodifin.ui.text.asString

/** Touches proposées à l'essai, par groupe : ressource du libellé et touche HID. */
private val MEDIA_KEYS = listOf(
    R.string.bt_keyboard_key_play_pause to RemoteKey.PLAY_PAUSE, R.string.bt_keyboard_key_stop to RemoteKey.STOP,
    R.string.bt_keyboard_key_previous to RemoteKey.PREVIOUS, R.string.bt_keyboard_key_next to RemoteKey.NEXT,
    R.string.bt_keyboard_key_rewind to RemoteKey.REWIND, R.string.bt_keyboard_key_fast_forward to RemoteKey.FAST_FORWARD,
    R.string.bt_keyboard_key_volume_down to RemoteKey.VOLUME_DOWN, R.string.bt_keyboard_key_volume_up to RemoteKey.VOLUME_UP,
    R.string.bt_keyboard_key_mute to RemoteKey.MUTE,
).map { (label, key) -> label to HidReports.keyFor(key) }

private val NAV_KEYS = listOf(
    R.string.bt_keyboard_key_back to HidReports.AC_BACK, R.string.bt_keyboard_key_home to HidReports.AC_HOME,
    R.string.bt_keyboard_key_menu to HidReports.CONSUMER_MENU, R.string.bt_keyboard_key_info to HidReports.APPLICATION,
)

/** Variantes à comparer : chaque TV ne reconnaît pas les mêmes codes. */
private val VARIANT_KEYS = listOf(
    R.string.bt_keyboard_key_escape to HidReports.ESCAPE,
    R.string.bt_keyboard_key_backspace to HidReports.BACKSPACE,
    R.string.bt_keyboard_key_keyboard_home to HidReports.KEY_HOME,
    R.string.bt_keyboard_key_channel_up to HidReports.CHANNEL_UP,
    R.string.bt_keyboard_key_channel_down to HidReports.CHANNEL_DOWN,
    R.string.bt_keyboard_key_power to HidReports.POWER,
)

/**
 * Essai du pilotage par Bluetooth : la tablette se déclare comme clavier, la TV l'appaire depuis
 * son menu Bluetooth, puis chaque touche peut être testée.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun BluetoothKeyboardScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val vm: BluetoothKeyboardViewModel = viewModel(
        factory = viewModelFactory { initializer { BluetoothKeyboardViewModel(AndroidHidKeyboard(context)) } },
    )
    val state by vm.state.collectAsStateWithLifecycle()
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.refresh() }

    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { vm.refresh() }
    val system = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { vm.refresh() }
    val press: (Int, HidKey) -> Unit = { label, key -> vm.press(UiText.Res(label), key) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.bt_keyboard_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.bt_keyboard_back)) }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                stringResource(R.string.bt_keyboard_intro),
                style = MaterialTheme.typography.bodyMedium,
            )

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.bt_keyboard_status_title), style = MaterialTheme.typography.titleMedium)
                    Text(statusText(state).asString(), style = MaterialTheme.typography.bodyMedium)
                    state.link.error?.let { Text(it.asString(), color = MaterialTheme.colorScheme.error) }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        when (state.availability) {
                            HidAvailability.NO_PERMISSION -> Button(onClick = {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                    permissions.launch(arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE))
                                }
                            }) { Text(stringResource(R.string.bt_keyboard_allow_nearby)) }
                            HidAvailability.BLUETOOTH_OFF -> Button(onClick = {
                                runCatching { system.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)) }
                            }) { Text(stringResource(R.string.bt_keyboard_enable_bluetooth)) }
                            HidAvailability.READY -> {
                                OutlinedButton(onClick = {
                                    val intent = Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE)
                                        .putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, DISCOVERABLE_SECONDS)
                                    runCatching { system.launch(intent) }
                                }) { Text(stringResource(R.string.bt_keyboard_make_visible, DISCOVERABLE_SECONDS / 60)) }
                                if (state.link.connected != null) OutlinedButton(onClick = vm::disconnect) { Text(stringResource(R.string.bt_keyboard_disconnect)) }
                            }
                            else -> Unit
                        }
                    }
                }
            }

            if (state.availability == HidAvailability.READY && state.hosts.isNotEmpty()) {
                Text(stringResource(R.string.bt_keyboard_paired_devices), style = MaterialTheme.typography.titleMedium)
                state.hosts.forEach { host ->
                    val connected = state.link.connected?.address == host.address
                    ListItem(
                        headlineContent = { Text(host.name) },
                        supportingContent = { Text(if (connected) stringResource(R.string.bt_keyboard_host_connected) else host.address) },
                        trailingContent = {
                            if (!connected) TextButton(onClick = { vm.connect(host) }, enabled = state.link.registered) { Text(stringResource(R.string.bt_keyboard_connect)) }
                        },
                    )
                }
            }

            Text(stringResource(R.string.bt_keyboard_section_navigation), style = MaterialTheme.typography.titleMedium)
            DPad(enabled = state.canSend, onKey = press)
            KeyGroup(NAV_KEYS, state.canSend, press)

            Text(stringResource(R.string.bt_keyboard_section_media), style = MaterialTheme.typography.titleMedium)
            KeyGroup(MEDIA_KEYS, state.canSend, press)

            Text(stringResource(R.string.bt_keyboard_section_variants), style = MaterialTheme.typography.titleMedium)
            KeyGroup(VARIANT_KEYS, state.canSend, press)

            Text(stringResource(R.string.bt_keyboard_section_text), style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(R.string.bt_keyboard_layout_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SingleChoiceSegmentedButtonRow {
                KeyboardLayout.entries.forEachIndexed { index, layout ->
                    SegmentedButton(
                        selected = state.layout == layout,
                        onClick = { vm.setLayout(layout) },
                        shape = SegmentedButtonDefaults.itemShape(index, KeyboardLayout.entries.size),
                    ) { Text(layout.text.asString()) }
                }
            }
            var text by rememberSaveable { mutableStateOf("") }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(text, { text = it }, label = { Text(stringResource(R.string.bt_keyboard_text_label)) }, singleLine = true, modifier = Modifier.weight(1f))
                Button(onClick = { vm.type(text) }, enabled = state.canSend && text.isNotEmpty()) { Text(stringResource(R.string.bt_keyboard_send)) }
            }

            state.feedback?.let { Text(it.asString(), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary) }
        }
    }
}

private fun statusText(state: BluetoothKeyboardUiState): UiText = when (state.availability) {
    HidAvailability.UNSUPPORTED -> UiText.Res(R.string.bt_keyboard_status_unsupported)
    HidAvailability.NO_BLUETOOTH -> UiText.Res(R.string.bt_keyboard_status_no_bluetooth)
    HidAvailability.NO_PERMISSION -> UiText.Res(R.string.bt_keyboard_status_no_permission)
    HidAvailability.BLUETOOTH_OFF -> UiText.Res(R.string.bt_keyboard_status_bluetooth_off)
    HidAvailability.READY -> {
        val link = state.link
        when {
            link.connected != null -> UiText.Res(R.string.bt_keyboard_status_connected, link.connected.name)
            link.connecting != null -> UiText.Res(R.string.bt_keyboard_status_connecting, link.connecting.name)
            link.registered -> UiText.Res(R.string.bt_keyboard_status_registered)
            else -> UiText.Res(R.string.bt_keyboard_status_registering)
        }
    }
}

@Composable
private fun DPad(enabled: Boolean, onKey: (Int, HidKey) -> Unit) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        PadButton(Icons.Filled.KeyboardArrowUp, R.string.bt_keyboard_key_up, RemoteKey.UP, enabled, onKey)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PadButton(Icons.AutoMirrored.Filled.KeyboardArrowLeft, R.string.bt_keyboard_key_left, RemoteKey.LEFT, enabled, onKey)
            FilledTonalButton(onClick = { onKey(R.string.bt_keyboard_key_ok, HidReports.keyFor(RemoteKey.SELECT)) }, enabled = enabled, modifier = Modifier.widthIn(min = 88.dp)) {
                Text(stringResource(R.string.bt_keyboard_key_ok))
            }
            PadButton(Icons.AutoMirrored.Filled.KeyboardArrowRight, R.string.bt_keyboard_key_right, RemoteKey.RIGHT, enabled, onKey)
        }
        PadButton(Icons.Filled.KeyboardArrowDown, R.string.bt_keyboard_key_down, RemoteKey.DOWN, enabled, onKey)
        Spacer(Modifier.size(4.dp))
    }
}

@Composable
private fun PadButton(icon: ImageVector, @StringRes label: Int, key: RemoteKey, enabled: Boolean, onKey: (Int, HidKey) -> Unit) {
    FilledTonalIconButton(onClick = { onKey(label, HidReports.keyFor(key)) }, enabled = enabled, modifier = Modifier.size(64.dp)) {
        Icon(icon, contentDescription = stringResource(label), modifier = Modifier.size(36.dp))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun KeyGroup(keys: List<Pair<Int, HidKey>>, enabled: Boolean, onKey: (Int, HidKey) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        keys.forEach { (label, key) ->
            OutlinedButton(onClick = { onKey(label, key) }, enabled = enabled) { Text(stringResource(label)) }
        }
    }
}

private const val DISCOVERABLE_SECONDS = 120
