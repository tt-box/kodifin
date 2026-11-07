package io.github.ttbox.kodifin.ui.remote

import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.rememberLauncherForActivityResult
import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.input.KeyboardType
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.ttbox.kodifin.connection.AndroidNetworkInfo
import io.github.ttbox.kodifin.container
import io.github.ttbox.kodifin.model.Capability
import io.github.ttbox.kodifin.model.PowerAction
import kotlinx.coroutines.launch
import androidx.compose.ui.res.stringResource
import io.github.ttbox.kodifin.R
import io.github.ttbox.kodifin.ui.text.asString
import io.github.ttbox.kodifin.text.resolve
import io.github.ttbox.kodifin.text.text

internal enum class RemoteDialog { Url, Text, Notify, ClearQueue, CleanLibrary }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RemoteScreen(
    deviceId: String,
    sharedUrl: String?,
    onBack: () -> Unit,
    onEdit: () -> Unit = {},
    /** Ouvre directement la télécommande d'un autre appareil enregistré. */
    onSwitch: (String) -> Unit = {},
    /** Réglage global « Garder l'écran allumé » sur la télécommande (fourni par l'appelant). */
    keepScreenOn: Boolean = false,
) {
    val context = LocalContext.current
    val vm: RemoteViewModel = viewModel(
        key = deviceId,
        factory = viewModelFactory {
            initializer {
                val network = AndroidNetworkInfo(context.applicationContext)
                RemoteViewModel(context.container.remoteDependencies(network), deviceId, sharedUrl, createSavedStateHandle())
            }
        },
    )
    // Écran maintenu allumé tant que la télécommande est affichée (drapeau de la vue, aucune permission).
    val view = LocalView.current
    DisposableEffect(view, keepScreenOn) {
        val previous = view.keepScreenOn
        if (keepScreenOn) view.keepScreenOn = true
        onDispose { view.keepScreenOn = previous }
    }
    val state by vm.state.collectAsStateWithLifecycle()
    val saved by context.container.devices.saved.collectAsStateWithLifecycle(emptyList())
    val switchable = remember(saved, deviceId) { RemoteSwitcher.entries(saved, deviceId) }
    val snackbar = remember { SnackbarHostState() }
    // Survivent à la rotation (énumérations sérialisables dans le Bundle).
    var dialog by rememberSaveable { mutableStateOf<RemoteDialog?>(null) }
    var powerConfirm by rememberSaveable { mutableStateOf<PowerAction?>(null) }
    var sheet by rememberSaveable { mutableStateOf(false) }

    val resources = LocalResources.current
    LaunchedEffect(vm) { vm.messages.collect { snackbar.showSnackbar(it.resolve(resources)) } }
    DisposableEffect(vm) { onDispose { vm.setVisiblePanels(emptySet()) } }

    val actions = remember(vm) { RemoteActions(vm::press, vm::uiCommand, vm::seekTo, vm::seekBy, vm::setVolume) }
    val playbackActions = remember(vm) {
        PlaybackActions(
            refresh = vm::refreshDetails, setAudioTrack = vm::setAudioTrack, nextAudioTrack = vm::nextAudioTrack,
            setSubtitle = vm::setSubtitle, nextSubtitle = vm::nextSubtitle, slower = vm::slower, faster = vm::faster,
            subtitleDelay = vm::subtitleDelay, audioDelay = vm::audioDelay, setRepeat = vm::setRepeat,
            setShuffle = vm::setShuffle, previousChapter = vm::previousChapter, nextChapter = vm::nextChapter,
        )
    }

    // Touches de volume du téléphone : elles pilotent l'appareil tant que la télécommande a le focus.
    val keyFocus = remember { FocusRequester() }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { runCatching { keyFocus.requestFocus() } }
    var volumeKeyHandled by remember { mutableStateOf(false) }

    Scaffold(
        modifier = Modifier
            .focusRequester(keyFocus)
            .focusable()
            .onPreviewKeyEvent { event ->
                val code = event.nativeKeyEvent.keyCode
                if (code != AndroidKeyEvent.KEYCODE_VOLUME_UP && code != AndroidKeyEvent.KEYCODE_VOLUME_DOWN) {
                    return@onPreviewKeyEvent false
                }
                when (event.type) {
                    KeyEventType.KeyDown -> vm.onVolumeKey(up = code == AndroidKeyEvent.KEYCODE_VOLUME_UP).also { volumeKeyHandled = it }
                    KeyEventType.KeyUp -> volumeKeyHandled
                    else -> false
                }
            },
        topBar = {
            RemoteTopBar(
                state = state,
                onBack = onBack,
                onWake = vm::wake,
                onPower = { powerConfirm = it },
                onRetry = vm::retry,
                onOpenTargets = vm::refreshTargets,
                onSelectTarget = vm::selectTarget,
                onMore = { sheet = true },
                switchable = switchable,
                onSwitch = onSwitch,
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (state.connecting) LinearProgressIndicator(Modifier.fillMaxWidth())
            state.networkBlocked?.let { NetworkBlockedCard(it.asString()) }
            CompositionLocalProvider(LocalImageLoader provides vm.images.takeIf { state.features.images }) {
            BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
                val layout = RemoteLayouts.forSize(maxWidth.value, maxHeight.value)
                val content = RemoteContent(
                    state = state,
                    actions = actions,
                    playbackActions = playbackActions,
                    vm = vm,
                    onClearQueue = { dialog = RemoteDialog.ClearQueue },
                )
                RemoteBackdrop(state.playback)
                when (layout) {
                    RemoteLayout.PHONE -> PhoneLayout(content)
                    RemoteLayout.TABLET_PORTRAIT -> TabletPortraitLayout(content)
                    RemoteLayout.TABLET_LANDSCAPE -> TabletLandscapeLayout(content)
                }
            }
            }
        }
    }

    // Recherche plein écran : au-dessus de toutes les dispositions (y compris tablette 3 volets).
    if (state.search.open && state.features.search) {
        val searchActions = remember(vm) {
            SearchActions(
                close = vm.search::close, query = vm.search::setQuery, submit = vm.search::submit,
                toggleKind = vm.search::toggleKind, clearKinds = vm.search::clearKinds, open = vm.search::openEntry,
                back = vm.search::back, loadMore = vm.search::loadMore, play = vm::playEntry,
                enqueue = { entry, next -> vm.enqueue(entry, next) },
            )
        }
        SearchScreen(
            search = state.search,
            searchKinds = state.features.searchKinds,
            canEnqueue = state.features.library,
            images = vm.images.takeIf { state.features.images },
            snackbar = snackbar,
            actions = searchActions,
        )
    }

    // Sélecteur de documents Android : aucun accès global au stockage, seul le fichier choisi est lu.
    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { vm.transfer.sendLocalFile(it.toString()) }
    }

    if (sheet) {
        MoreSheet(
            state = state,
            onDismiss = { sheet = false },
            onDialog = { sheet = false; dialog = it },
            onCommand = vm::uiCommand,
            fullscreen = state.fullscreen,
            onFullscreen = vm::setFullscreen,
            onScan = { sheet = false; vm.scanLibrary() },
            onEdit = { sheet = false; onEdit() },
            onSendFile = { sheet = false; pickFile.launch(arrayOf("video/*", "audio/*", "image/*")) },
            onTransfer = { sheet = false; vm.transfer.open() },
            onStopSharing = { sheet = false; vm.transfer.stopSharing() },
        )
    }

    if (state.itemInfo.entry != null) {
        ItemInfoSheet(
            state = state.itemInfo,
            onDismiss = vm::closeItemInfo,
            onPlay = vm::playEntry,
            onEnqueue = if (state.features.library) { entry, next -> vm.enqueue(entry, next) } else null,
        )
    }

    if (state.transfer.open) {
        TransferDialog(
            transfer = state.transfer,
            sourceName = state.device?.name ?: stringResource(R.string.remote_this_device),
            onDismiss = vm.transfer::close,
            onTransfer = vm.transfer::transferTo,
        )
    }

    val deviceName = state.device?.name ?: stringResource(R.string.remote_the_device)
    when (val kind = dialog) {
        RemoteDialog.Url, RemoteDialog.Text -> TextInputDialog(
            title = stringResource(if (kind == RemoteDialog.Url) R.string.remote_play_url else R.string.remote_send_text_title),
            label = if (kind == RemoteDialog.Url) "https://…" else stringResource(R.string.remote_text_label),
            keyboardType = if (kind == RemoteDialog.Url) KeyboardType.Uri else KeyboardType.Text,
            onDismiss = { dialog = null },
            onConfirm = { value ->
                if (kind == RemoteDialog.Url) vm.playUrl(value) else vm.sendText(value)
                dialog = null
            },
        )
        RemoteDialog.Notify -> NotifyDialog(
            onDismiss = { dialog = null },
            onConfirm = { title, text -> vm.showMessage(title, text); dialog = null },
        )
        RemoteDialog.ClearQueue -> ConfirmDialog(
            title = stringResource(R.string.remote_clear_queue_title),
            text = stringResource(R.string.remote_clear_queue_text, deviceName),
            confirmLabel = stringResource(R.string.remote_clear_queue_confirm),
            onDismiss = { dialog = null },
            onConfirm = { vm.queue.clear(); dialog = null },
        )
        RemoteDialog.CleanLibrary -> ConfirmDialog(
            title = stringResource(R.string.remote_clean_library_title),
            text = stringResource(R.string.remote_clean_library_text, deviceName),
            confirmLabel = stringResource(R.string.remote_clean_library_confirm),
            onDismiss = { dialog = null },
            onConfirm = { vm.cleanLibrary(); dialog = null },
        )
        null -> Unit
    }
    powerConfirm?.let { action ->
        val actionLabel = action.text.asString()
        ConfirmDialog(
            title = stringResource(R.string.remote_power_confirm_title, actionLabel),
            text = stringResource(R.string.remote_power_confirm_text, actionLabel, deviceName),
            confirmLabel = actionLabel,
            onDismiss = { powerConfirm = null },
            onConfirm = { vm.power(action); powerConfirm = null },
        )
    }
}

/** Données et rappels partagés par les trois dispositions. */
internal class RemoteContent(
    val state: RemoteUiState,
    val actions: RemoteActions,
    val playbackActions: PlaybackActions,
    val vm: RemoteViewModel,
    val onClearQueue: () -> Unit,
) {
    val features get() = state.features
    val keys get() = state.supportedKeys
    val playbackRows get() = Capability.PLAYBACK in state.capabilities
    val navigation get() = Capability.NAVIGATION in state.capabilities
}
