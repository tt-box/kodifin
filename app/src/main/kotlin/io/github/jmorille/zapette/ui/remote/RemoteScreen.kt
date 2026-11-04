package io.github.jmorille.zapette.ui.remote

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.VolumeDown
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.jmorille.zapette.container
import io.github.jmorille.zapette.model.Capability
import io.github.jmorille.zapette.model.PlaybackState
import io.github.jmorille.zapette.model.RemoteKey
import io.github.jmorille.zapette.model.formatHms

private enum class InputDialog { Url, Text }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RemoteScreen(deviceId: String, sharedUrl: String?, onBack: () -> Unit) {
    val context = LocalContext.current
    val vm: RemoteViewModel = viewModel(
        key = deviceId,
        factory = viewModelFactory { initializer { RemoteViewModel(context.container, deviceId, sharedUrl) } },
    )
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var dialog by remember { mutableStateOf<InputDialog?>(null) }

    LaunchedEffect(vm) { vm.messages.collect { snackbar.showSnackbar(it) } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(state.device?.name ?: "Télécommande") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour") }
                },
                actions = {
                    if (Capability.PLAY_URL in state.capabilities) {
                        IconButton(onClick = { dialog = InputDialog.Url }) { Icon(Icons.Filled.Link, contentDescription = "Lire une URL") }
                    }
                    if (Capability.TEXT_INPUT in state.capabilities) {
                        IconButton(onClick = { dialog = InputDialog.Text }) { Icon(Icons.Filled.Keyboard, contentDescription = "Saisir du texte") }
                    }
                    if (!state.connected && !state.connecting) {
                        IconButton(onClick = vm::retry) { Icon(Icons.Filled.Refresh, contentDescription = "Reconnecter") }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp).verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            if (state.connecting) LinearProgressIndicator(Modifier.fillMaxWidth())
            state.playback?.let { NowPlaying(it, onSeek = vm::seekTo, canSeek = Capability.SEEK in state.capabilities) }

            val keys = state.supportedKeys
            val press: (RemoteKey) -> Unit = vm::press
            if (Capability.NAVIGATION in state.capabilities) {
                DPad(keys, press)
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    KeyButton(Icons.AutoMirrored.Filled.ArrowBack, "Retour", RemoteKey.BACK, keys, press)
                    KeyButton(Icons.Filled.Home, "Accueil", RemoteKey.HOME, keys, press)
                    KeyButton(Icons.Filled.Menu, "Menu", RemoteKey.MENU, keys, press)
                    KeyButton(Icons.Filled.Info, "Infos", RemoteKey.INFO, keys, press)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                KeyButton(Icons.Filled.SkipPrevious, "Précédent", RemoteKey.PREVIOUS, keys, press)
                KeyButton(Icons.Filled.FastRewind, "Retour rapide", RemoteKey.REWIND, keys, press)
                KeyButton(
                    if (state.playback?.isPlaying == true) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    "Lecture / pause", RemoteKey.PLAY_PAUSE, keys, press,
                )
                KeyButton(Icons.Filled.FastForward, "Avance rapide", RemoteKey.FAST_FORWARD, keys, press)
                KeyButton(Icons.Filled.SkipNext, "Suivant", RemoteKey.NEXT, keys, press)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                KeyButton(Icons.Filled.Stop, "Stop", RemoteKey.STOP, keys, press)
                KeyButton(Icons.AutoMirrored.Filled.VolumeDown, "Volume -", RemoteKey.VOLUME_DOWN, keys, press)
                KeyButton(Icons.AutoMirrored.Filled.VolumeOff, "Muet", RemoteKey.MUTE, keys, press)
                KeyButton(Icons.AutoMirrored.Filled.VolumeUp, "Volume +", RemoteKey.VOLUME_UP, keys, press)
            }
            if (Capability.VOLUME in state.capabilities) {
                state.playback?.volumePercent?.let { volume -> VolumeSlider(volume, vm::setVolume) }
            }
        }
    }

    dialog?.let { kind ->
        TextInputDialog(
            title = if (kind == InputDialog.Url) "Lire une URL" else "Envoyer du texte",
            label = if (kind == InputDialog.Url) "https://…" else "Texte",
            onDismiss = { dialog = null },
            onConfirm = { value ->
                if (kind == InputDialog.Url) vm.playUrl(value) else vm.sendText(value)
                dialog = null
            },
        )
    }
}

@Composable
private fun NowPlaying(playback: PlaybackState, onSeek: (Long) -> Unit, canSeek: Boolean) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(playback.title ?: "Rien en lecture", style = MaterialTheme.typography.titleMedium, maxLines = 2)
            if (playback.durationMs > 0) {
                var dragging by remember { mutableStateOf(false) }
                var position by remember { mutableFloatStateOf(0f) }
                val shown = if (dragging) position else playback.positionMs.toFloat()
                Slider(
                    value = shown.coerceIn(0f, playback.durationMs.toFloat()),
                    onValueChange = { dragging = true; position = it },
                    onValueChangeFinished = { dragging = false; onSeek(position.toLong()) },
                    valueRange = 0f..playback.durationMs.toFloat(),
                    enabled = canSeek,
                )
                Text("${formatHms(shown.toLong())} / ${formatHms(playback.durationMs)}", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun VolumeSlider(volume: Int, onChange: (Int) -> Unit) {
    var local by remember(volume) { mutableFloatStateOf(volume.toFloat()) }
    Column(Modifier.fillMaxWidth()) {
        Text("Volume ${local.toInt()} %", style = MaterialTheme.typography.bodySmall)
        Slider(value = local, onValueChange = { local = it }, onValueChangeFinished = { onChange(local.toInt()) }, valueRange = 0f..100f)
    }
}

@Composable
private fun DPad(keys: Set<RemoteKey>, press: (RemoteKey) -> Unit) {
    val haptic = LocalHapticFeedback.current
    Box(
        Modifier.size(220.dp).background(MaterialTheme.colorScheme.surfaceVariant, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.align(Alignment.TopCenter).padding(8.dp)) {
            KeyButton(Icons.Filled.KeyboardArrowUp, "Haut", RemoteKey.UP, keys, press)
        }
        Box(Modifier.align(Alignment.BottomCenter).padding(8.dp)) {
            KeyButton(Icons.Filled.KeyboardArrowDown, "Bas", RemoteKey.DOWN, keys, press)
        }
        Box(Modifier.align(Alignment.CenterStart).padding(8.dp)) {
            KeyButton(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Gauche", RemoteKey.LEFT, keys, press)
        }
        Box(Modifier.align(Alignment.CenterEnd).padding(8.dp)) {
            KeyButton(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Droite", RemoteKey.RIGHT, keys, press)
        }
        FilledTonalButton(
            onClick = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); press(RemoteKey.SELECT) },
            enabled = RemoteKey.SELECT in keys,
            shape = CircleShape,
            modifier = Modifier.size(80.dp),
        ) { Text("OK") }
    }
}

@Composable
private fun KeyButton(icon: ImageVector, label: String, key: RemoteKey, keys: Set<RemoteKey>, press: (RemoteKey) -> Unit) {
    val haptic = LocalHapticFeedback.current
    FilledTonalIconButton(
        onClick = { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); press(key) },
        enabled = key in keys,
        modifier = Modifier.size(56.dp),
    ) { Icon(icon, contentDescription = label) }
}

@Composable
private fun TextInputDialog(title: String, label: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var value by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { OutlinedTextField(value, { value = it }, label = { Text(label) }, singleLine = true) },
        confirmButton = { TextButton(onClick = { onConfirm(value) }, enabled = value.isNotBlank()) { Text("Envoyer") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } },
    )
}
