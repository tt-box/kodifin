package io.github.jmorille.zapette.ui.remote

import android.view.KeyEvent as AndroidKeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Message
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SettingsRemote
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.jmorille.zapette.connection.AndroidNetworkInfo
import io.github.jmorille.zapette.connection.DeviceOptions
import io.github.jmorille.zapette.container
import io.github.jmorille.zapette.model.Capability
import io.github.jmorille.zapette.model.PowerAction
import io.github.jmorille.zapette.model.UiCommand
import kotlinx.coroutines.launch

private enum class RemoteDialog { Url, Text, Notify, ClearQueue, CleanLibrary }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RemoteScreen(
    deviceId: String,
    sharedUrl: String?,
    onBack: () -> Unit,
    onEdit: () -> Unit = {},
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
    val snackbar = remember { SnackbarHostState() }
    // Survivent à la rotation (énumérations sérialisables dans le Bundle).
    var dialog by rememberSaveable { mutableStateOf<RemoteDialog?>(null) }
    var powerConfirm by rememberSaveable { mutableStateOf<PowerAction?>(null) }
    var sheet by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(vm) { vm.messages.collect { snackbar.showSnackbar(it) } }
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
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (state.connecting) LinearProgressIndicator(Modifier.fillMaxWidth())
            state.networkBlocked?.let { NetworkBlockedCard(it) }
            BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
                val layout = RemoteLayouts.forSize(maxWidth.value, maxHeight.value)
                val content = RemoteContent(
                    state = state,
                    actions = actions,
                    playbackActions = playbackActions,
                    vm = vm,
                    onClearQueue = { dialog = RemoteDialog.ClearQueue },
                )
                when (layout) {
                    RemoteLayout.PHONE -> PhoneLayout(content)
                    RemoteLayout.TABLET_PORTRAIT -> TabletPortraitLayout(content)
                    RemoteLayout.TABLET_LANDSCAPE -> TabletLandscapeLayout(content)
                }
            }
        }
    }

    if (sheet) {
        MoreSheet(
            state = state,
            onDismiss = { sheet = false },
            onDialog = { sheet = false; dialog = it },
            onCommand = vm::uiCommand,
            onScan = { sheet = false; vm.scanLibrary() },
            onEdit = { sheet = false; onEdit() },
        )
    }

    when (val kind = dialog) {
        RemoteDialog.Url, RemoteDialog.Text -> TextInputDialog(
            title = if (kind == RemoteDialog.Url) "Lire une URL" else "Envoyer du texte",
            label = if (kind == RemoteDialog.Url) "https://…" else "Texte",
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
            title = "Vider la file d'attente ?",
            text = "Tous les éléments de la file d'attente de ${state.device?.name ?: "l'appareil"} seront retirés.",
            confirmLabel = "Vider",
            onDismiss = { dialog = null },
            onConfirm = { vm.clearQueue(); dialog = null },
        )
        RemoteDialog.CleanLibrary -> ConfirmDialog(
            title = "Nettoyer la bibliothèque ?",
            text = "Les éléments dont le fichier n'existe plus seront retirés de la bibliothèque de ${state.device?.name ?: "l'appareil"}.",
            confirmLabel = "Nettoyer",
            onDismiss = { dialog = null },
            onConfirm = { vm.cleanLibrary(); dialog = null },
        )
        null -> Unit
    }
    powerConfirm?.let { action ->
        ConfirmDialog(
            title = "${action.label} ?",
            text = "« ${action.label} » sera envoyé à ${state.device?.name ?: "l'appareil"}.",
            confirmLabel = action.label,
            onDismiss = { powerConfirm = null },
            onConfirm = { vm.power(action); powerConfirm = null },
        )
    }
}

/** Données et rappels partagés par les trois dispositions. */
private class RemoteContent(
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

// --- Barre du haut et feuille « ⋮ » -----------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RemoteTopBar(
    state: RemoteUiState,
    onBack: () -> Unit,
    onWake: () -> Unit,
    onPower: (PowerAction) -> Unit,
    onRetry: () -> Unit,
    onOpenTargets: () -> Unit,
    onSelectTarget: (String) -> Unit,
    onMore: () -> Unit,
) {
    val status = when {
        state.connecting -> "Connexion…"
        !state.connected -> "Non connecté"
        else -> state.targets.current?.let { "Cible : ${it.name}" }
    }
    TopAppBar(
        title = {
            Column {
                Text(state.device?.name ?: "Télécommande", maxLines = 1, overflow = TextOverflow.Ellipsis)
                status?.let {
                    Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                }
            }
        },
        navigationIcon = {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour") }
        },
        actions = {
            if (state.features.targets && state.connected) TargetsMenu(state.targets, onOpenTargets, onSelectTarget)
            val canWake = DeviceOptions.wakeTarget(state.device) != null
            if (canWake || state.features.powerActions.isNotEmpty()) {
                PowerMenu(canWake, state.features.powerActions, onWake, onPower)
            }
            if (!state.connected && !state.connecting) {
                IconButton(onClick = onRetry) { Icon(Icons.Filled.Refresh, contentDescription = "Reconnecter") }
            }
            IconButton(onClick = onMore) { Icon(Icons.Filled.MoreVert, contentDescription = "Plus d'actions") }
        },
    )
}

@Composable
private fun TargetsMenu(targets: TargetsState, onOpen: () -> Unit, onSelect: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true; onOpen() }) { Icon(Icons.Filled.Devices, contentDescription = "Cible de lecture") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            if (targets.items.isEmpty()) {
                DropdownMenuItem(text = { Text("Aucune session active") }, onClick = { open = false }, enabled = false)
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
                        { Icon(Icons.Filled.Check, contentDescription = "Cible actuelle") }
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
        IconButton(onClick = { open = true }) { Icon(Icons.Filled.PowerSettingsNew, contentDescription = "Alimentation") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            if (canWake) {
                DropdownMenuItem(text = { Text("Allumer (Wake-on-LAN)") }, onClick = { open = false; onWake() })
            }
            PowerAction.entries.filter { it in actions }.forEach { action ->
                DropdownMenuItem(text = { Text(action.label) }, onClick = { open = false; onPower(action) })
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MoreSheet(
    state: RemoteUiState,
    onDismiss: () -> Unit,
    onDialog: (RemoteDialog) -> Unit,
    onCommand: (UiCommand) -> Unit,
    onScan: () -> Unit,
    onEdit: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(bottom = 16.dp)) {
            if (Capability.TEXT_INPUT in state.capabilities) {
                SheetItem(Icons.Filled.Keyboard, "Saisir du texte") { onDialog(RemoteDialog.Text) }
            }
            if (Capability.PLAY_URL in state.capabilities) {
                SheetItem(Icons.Filled.Link, "Lire une URL") { onDialog(RemoteDialog.Url) }
            }
            if (state.features.notify) {
                SheetItem(Icons.AutoMirrored.Filled.Message, "Message à l'écran") { onDialog(RemoteDialog.Notify) }
            }
            if (state.features.libraryMaintenance) {
                SheetItem(Icons.Filled.Sync, "Mettre à jour la bibliothèque", onScan)
                SheetItem(Icons.Filled.CleaningServices, "Nettoyer la bibliothèque") { onDialog(RemoteDialog.CleanLibrary) }
            }
            if (state.features.uiCommands.isNotEmpty()) {
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                Text(
                    "Commandes de l'appareil",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
                UiCommandChips(state.features.uiCommands, onCommand, Modifier.padding(horizontal = 16.dp))
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
            }
            SheetItem(Icons.Filled.Edit, "Modifier la configuration", onEdit)
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
private fun NetworkBlockedCard(reason: String) {
    Card(Modifier.fillMaxWidth().padding(16.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(reason, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.error)
            Text(
                "Connectez-vous à un réseau autorisé puis appuyez sur « Reconnecter », ou modifiez les options réseau de l'appareil.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

// --- Dispositions -----------------------------------------------------------------------------

private val RemoteTab.icon: ImageVector
    get() = when (this) {
        RemoteTab.REMOTE -> Icons.Filled.SettingsRemote
        RemoteTab.NOW_PLAYING -> Icons.Filled.PlayCircle
        RemoteTab.QUEUE -> Icons.AutoMirrored.Filled.QueueMusic
        RemoteTab.LIBRARY -> Icons.Filled.VideoLibrary
    }

/** Signale au ViewModel les volets visibles (chargement à l'ouverture) et gère le retour en bibliothèque. */
@Composable
private fun PanelsEffect(content: RemoteContent, layout: RemoteLayout, selected: RemoteTab?) {
    val visible = RemoteLayouts.visiblePanels(layout, selected, content.features)
    LaunchedEffect(visible, content.state.connected) { content.vm.setVisiblePanels(visible) }
    BackHandler(enabled = RemotePanel.LIBRARY in visible && content.state.library.path.isNotEmpty()) {
        content.vm.libraryBack()
    }
}

@Composable
private fun PhoneLayout(content: RemoteContent) {
    val tabs = RemoteLayouts.phoneTabs(content.state.capabilities, content.features)
    val currentTabs by rememberUpdatedState(tabs)
    val pager = rememberPagerState(pageCount = { currentTabs.size })
    val scope = rememberCoroutineScope()
    val selected = tabs.getOrNull(pager.currentPage)
    PanelsEffect(content, RemoteLayout.PHONE, selected)
    Column(Modifier.fillMaxSize()) {
        if (tabs.size > 1) {
            PrimaryTabRow(selectedTabIndex = pager.currentPage.coerceIn(0, tabs.lastIndex)) {
                tabs.forEachIndexed { index, tab ->
                    Tab(
                        selected = index == pager.currentPage,
                        onClick = { scope.launch { pager.animateScrollToPage(index) } },
                        text = { Text(tab.short, style = MaterialTheme.typography.labelMedium, maxLines = 1) },
                        icon = { Icon(tab.icon, contentDescription = null) },
                    )
                }
            }
        }
        HorizontalPager(pager, modifier = Modifier.weight(1f), key = { tabs.getOrNull(it) ?: it }) { page ->
            when (tabs.getOrNull(page)) {
                RemoteTab.REMOTE, null -> RemotePage(content)
                RemoteTab.NOW_PLAYING -> Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    PlaybackHeader(content)
                    PlaybackDetails(content.features, content.state.mediaDetails, content.keys, content.playbackActions, content.actions.press)
                }
                RemoteTab.QUEUE -> QueueContent(content)
                RemoteTab.LIBRARY -> LibraryContent(content)
            }
        }
    }
}

/**
 * Page « Télécommande » du téléphone : sans défilement, le pavé prend toute la place restante.
 * En paysage (téléphone couché), bandeau et transport passent à gauche du pavé.
 */
@Composable
private fun RemotePage(content: RemoteContent) {
    BoxWithConstraints(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp)) {
        if (maxWidth > maxHeight) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Column(
                    Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
                ) { PlaybackHeader(content); VolumeFor(content) }
                NavigationFor(content, Modifier.weight(1f).fillMaxHeight())
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                content.state.playback?.let { BannerFor(content) }
                NavigationFor(content, Modifier.weight(1f).fillMaxWidth())
                if (content.playbackRows) TransportFor(content)
                VolumeFor(content)
            }
        }
    }
}

/** Tablette en portrait : télécommande à gauche, volet à onglets (lecture, file, bibliothèque) à droite. */
@Composable
private fun TabletPortraitLayout(content: RemoteContent) {
    val tabs = RemoteLayouts.sideTabs(RemoteLayout.TABLET_PORTRAIT, content.features)
    var selectedIndex by rememberSaveable { mutableIntStateOf(0) }
    val selected = tabs.getOrNull(selectedIndex.coerceIn(0, (tabs.size - 1).coerceAtLeast(0)))
    PanelsEffect(content, RemoteLayout.TABLET_PORTRAIT, selected)
    Row(Modifier.fillMaxSize().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally)) {
        val remoteModifier = if (tabs.isEmpty()) Modifier.widthIn(max = 560.dp) else Modifier.weight(1f)
        Column(remoteModifier.fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            content.state.playback?.let { BannerFor(content) }
            NavigationFor(content, Modifier.weight(1f).fillMaxWidth())
            if (content.playbackRows) TransportFor(content)
            VolumeFor(content)
        }
        if (tabs.isNotEmpty()) {
            SidePane(content, tabs, selected, { selectedIndex = tabs.indexOf(it) }, Modifier.weight(1f).fillMaxHeight())
        }
    }
}

/** Tablette en paysage : lecture en cours et pistes | pavé directionnel | file d'attente ou bibliothèque. */
@Composable
private fun TabletLandscapeLayout(content: RemoteContent) {
    val tabs = RemoteLayouts.sideTabs(RemoteLayout.TABLET_LANDSCAPE, content.features)
    var selectedIndex by rememberSaveable { mutableIntStateOf(0) }
    val selected = tabs.getOrNull(selectedIndex.coerceIn(0, (tabs.size - 1).coerceAtLeast(0)))
    PanelsEffect(content, RemoteLayout.TABLET_LANDSCAPE, selected)
    Row(Modifier.fillMaxSize().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(
            Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            PlaybackHeader(content)
            VolumeFor(content)
            PlaybackDetails(content.features, content.state.mediaDetails, content.keys, content.playbackActions, content.actions.press)
        }
        Column(Modifier.weight(1.1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            NavigationFor(content, Modifier.weight(1f).fillMaxWidth())
            val extra = content.features.uiCommands - setOf(UiCommand.OSD, UiCommand.CONTEXT_MENU)
            UiCommandChips(extra, content.actions.uiCommand, Modifier.fillMaxWidth())
        }
        if (tabs.isNotEmpty()) {
            SidePane(content, tabs, selected, { selectedIndex = tabs.indexOf(it) }, Modifier.weight(1f).fillMaxHeight())
        }
    }
}

@Composable
private fun SidePane(
    content: RemoteContent,
    tabs: List<RemoteTab>,
    selected: RemoteTab?,
    onSelect: (RemoteTab) -> Unit,
    modifier: Modifier,
) {
    Surface(modifier, shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column {
            if (tabs.size > 1) {
                PrimaryTabRow(selectedTabIndex = tabs.indexOf(selected).coerceAtLeast(0)) {
                    tabs.forEach { tab ->
                        Tab(
                            selected = tab == selected,
                            onClick = { onSelect(tab) },
                            text = { Text(tab.short, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            icon = { Icon(tab.icon, contentDescription = tab.label) },
                        )
                    }
                }
            }
            Box(Modifier.weight(1f)) {
                when (selected) {
                    RemoteTab.NOW_PLAYING -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)) {
                        PlaybackDetails(content.features, content.state.mediaDetails, content.keys, content.playbackActions, content.actions.press)
                    }
                    RemoteTab.QUEUE -> QueueContent(content)
                    RemoteTab.LIBRARY -> LibraryContent(content)
                    RemoteTab.REMOTE, null -> Unit
                }
            }
        }
    }
}

// --- Briques communes -------------------------------------------------------------------------

@Composable
private fun BannerFor(content: RemoteContent) {
    val playback = content.state.playback ?: return
    NowPlayingBanner(
        playback = playback,
        canSeek = Capability.SEEK in content.state.capabilities,
        onSeek = content.actions.seekTo,
        details = content.state.mediaDetails,
    )
}

/** Bandeau puis rangée de transport (celle-ci seulement si l'appareil gère la lecture). */
@Composable
private fun PlaybackHeader(content: RemoteContent) {
    BannerFor(content)
    if (content.playbackRows) TransportFor(content)
}

@Composable
private fun TransportFor(content: RemoteContent) {
    TransportRow(content.keys, content.state.playback?.isPlaying == true, content.features.seekStep, content.actions)
}

@Composable
private fun VolumeFor(content: RemoteContent) {
    VolumeRow(content.keys, content.state.capabilities, content.state.playback?.volumePercent, content.actions)
}

@Composable
private fun NavigationFor(content: RemoteContent, modifier: Modifier) {
    if (content.navigation) {
        NavigationCluster(content.keys, content.features.uiCommands, content.actions, modifier)
    } else {
        Spacer(modifier)
    }
}

@Composable
private fun QueueContent(content: RemoteContent) {
    QueuePanel(
        queue = content.state.queue,
        onRefresh = content.vm::refreshQueue,
        onPlay = content.vm::playQueueItem,
        onRemove = content.vm::removeQueueItem,
        onClear = content.onClearQueue,
    )
}

@Composable
private fun LibraryContent(content: RemoteContent) {
    LibraryPanel(
        library = content.state.library,
        onOpenSection = content.vm::openSection,
        onOpenEntry = content.vm::openEntry,
        onEnqueue = content.vm::enqueue,
        onBack = { content.vm.libraryBack() },
        onLoadMore = content.vm::loadMoreLibrary,
        onRetry = content.vm::openLibrary,
    )
}
