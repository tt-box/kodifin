package io.github.ttbox.kodifin.ui.remote

import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material.icons.filled.CloseFullscreen
import io.github.ttbox.kodifin.model.DeviceKind
import io.github.ttbox.kodifin.model.PlaybackState
import androidx.compose.material.icons.filled.Speaker
import androidx.compose.material.icons.filled.Cast
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.draw.blur
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.background
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.SettingsRemote
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.ttbox.kodifin.model.Capability
import io.github.ttbox.kodifin.model.UiCommand
import kotlinx.coroutines.launch
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import io.github.ttbox.kodifin.R
import io.github.ttbox.kodifin.text.text

// --- Dispositions -----------------------------------------------------------------------------

internal val RemoteTab.icon: ImageVector
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
        content.vm.library.back()
    }
}

@Composable
internal fun PhoneLayout(content: RemoteContent) {
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
                        text = { Text(stringResource(tab.shortRes), style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis) },
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
                    PlaybackDetails(content.features, content.state.mediaDetails, content.keys, content.playbackActions, content.actions.press, info = content.state.nowPlayingInfo)
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
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ProgressFor(content)
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Column(
                        Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
                    ) { if (content.playbackRows) TransportFor(content); VolumeFor(content) }
                    NavigationFor(content, Modifier.weight(1f).fillMaxHeight())
                }
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
internal fun TabletPortraitLayout(content: RemoteContent) {
    val tabs = RemoteLayouts.sideTabs(RemoteLayout.TABLET_PORTRAIT, content.features)
    var selectedIndex by rememberSaveable { mutableIntStateOf(0) }
    val hidden = PanePreferences.hidden && tabs.isNotEmpty()
    val selected = tabs.getOrNull(selectedIndex.coerceIn(0, (tabs.size - 1).coerceAtLeast(0)))
    var expanded by rememberSaveable { mutableStateOf(false) }
    BackHandler(enabled = expanded && tabs.isNotEmpty()) { expanded = false }
    PanelsEffect(content, RemoteLayout.TABLET_PORTRAIT, selected.takeUnless { hidden })
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
    ProgressFor(content)
    if (expanded && !hidden && tabs.isNotEmpty()) {
        if (content.playbackRows) TransportFor(content)
        SidePane(content, tabs, selected, { selectedIndex = tabs.indexOf(it) }, Modifier.weight(1f).fillMaxWidth(), expanded = true, onHide = { expanded = false; PanePreferences.hidden = true }) { expanded = false }
        return@Column
    }
    Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally)) {
        val remoteModifier = if (tabs.isEmpty()) Modifier.widthIn(max = 560.dp) else Modifier.weight(1f)
        Column(remoteModifier.fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            NavigationFor(content, Modifier.weight(1f).fillMaxWidth())
            if (content.playbackRows) TransportFor(content)
            VolumeFor(content)
        }
        if (hidden) {
            PaneRail(tabs) { selectedIndex = tabs.indexOf(it); PanePreferences.hidden = false }
        } else if (tabs.isNotEmpty()) {
            SidePane(content, tabs, selected, { selectedIndex = tabs.indexOf(it) }, Modifier.weight(1f).fillMaxHeight(), onHide = { PanePreferences.hidden = true }) { expanded = true }
        }
    }
    }
}

/** Tablette en paysage : lecture en cours et pistes | pavé directionnel | file d'attente ou bibliothèque. */
@Composable
internal fun TabletLandscapeLayout(content: RemoteContent) {
    val tabs = RemoteLayouts.sideTabs(RemoteLayout.TABLET_LANDSCAPE, content.features)
    var selectedIndex by rememberSaveable { mutableIntStateOf(0) }
    val hidden = PanePreferences.hidden && tabs.isNotEmpty()
    val selected = tabs.getOrNull(selectedIndex.coerceIn(0, (tabs.size - 1).coerceAtLeast(0)))
    var expanded by rememberSaveable { mutableStateOf(false) }
    BackHandler(enabled = expanded && tabs.isNotEmpty()) { expanded = false }
    PanelsEffect(content, RemoteLayout.TABLET_LANDSCAPE, selected.takeUnless { hidden })
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
    ProgressFor(content)
    if (expanded && !hidden && tabs.isNotEmpty()) {
        // Volet agrandi : transport et volume sur une ligne, le reste pour les séries et films.
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            if (content.playbackRows) Box(Modifier.weight(1f)) { TransportFor(content) }
            Box(Modifier.weight(1f)) { VolumeFor(content) }
        }
        SidePane(content, tabs, selected, { selectedIndex = tabs.indexOf(it) }, Modifier.weight(1f).fillMaxWidth(), expanded = true, onHide = { expanded = false; PanePreferences.hidden = true }) { expanded = false }
        return@Column
    }
    Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(
            Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (content.playbackRows) TransportFor(content)
            VolumeFor(content)
            PlaybackDetails(content.features, content.state.mediaDetails, content.keys, content.playbackActions, content.actions.press, info = content.state.nowPlayingInfo)
        }
        Column(Modifier.weight(1.1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            NavigationFor(content, Modifier.weight(1f).fillMaxWidth())
            val extra = content.features.uiCommands - setOf(UiCommand.OSD, UiCommand.CONTEXT_MENU)
            if (extra.isNotEmpty()) {
                CollapsibleCard(
                    stringResource(R.string.remote_more_commands),
                    summary = pluralStringResource(R.plurals.remote_device_commands_count, extra.size, extra.size),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    UiCommandChips(
                        extra, content.actions.uiCommand, Modifier.fillMaxWidth(),
                        fullscreen = content.state.fullscreen,
                        onFullscreen = content.vm::setFullscreen.takeIf { content.features.fullscreen },
                    )
                }
            }
        }
        if (hidden) {
            PaneRail(tabs) { selectedIndex = tabs.indexOf(it); PanePreferences.hidden = false }
        } else if (tabs.isNotEmpty()) {
            SidePane(content, tabs, selected, { selectedIndex = tabs.indexOf(it) }, Modifier.weight(1f).fillMaxHeight(), onHide = { PanePreferences.hidden = true }) { expanded = true }
        }
    }
    }
}

/**
 * Choix « volet masqué », commun à toutes les télécommandes ouvertes pendant la session (passer
 * d'un appareil à l'autre le garde masqué).
 */
private object PanePreferences {
    var hidden by mutableStateOf(false)
}

/** Volet masqué : une colonne d'icônes, une par onglet, pour le rouvrir directement. */
@Composable
private fun PaneRail(tabs: List<RemoteTab>, onShow: (RemoteTab) -> Unit) {
    Surface(
        Modifier.fillMaxHeight().width(64.dp),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.88f),
    ) {
        Column(Modifier.padding(vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            tabs.forEach { tab ->
                val showLabel = stringResource(R.string.remote_show_pane, stringResource(tab.labelRes))
                WithTooltip(showLabel) {
                    IconButton(onClick = { onShow(tab) }) { Icon(tab.icon, contentDescription = showLabel) }
                }
            }
        }
    }
}

/**
 * Volet à onglets (lecture, file, série, bibliothèque). [onToggleExpand] : bouton qui l'agrandit
 * à toute la largeur de la page pour mieux parcourir les séries et films, ou le réduit.
 */
@Composable
private fun SidePane(
    content: RemoteContent,
    tabs: List<RemoteTab>,
    selected: RemoteTab?,
    onSelect: (RemoteTab) -> Unit,
    modifier: Modifier,
    expanded: Boolean = false,
    /** Masque le volet (la télécommande reprend la place). */
    onHide: () -> Unit,
    onToggleExpand: () -> Unit,
) {
    Surface(modifier, shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.88f)) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (tabs.size > 1) {
                    PrimaryTabRow(selectedTabIndex = tabs.indexOf(selected).coerceAtLeast(0), modifier = Modifier.weight(1f)) {
                        tabs.forEach { tab ->
                            Tab(
                                selected = tab == selected,
                                onClick = { onSelect(tab) },
                                text = { Text(stringResource(tab.shortRes), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                icon = { Icon(tab.icon, contentDescription = stringResource(tab.labelRes)) },
                            )
                        }
                    }
                } else {
                    Text(
                        selected?.let { stringResource(it.labelRes) }.orEmpty(),
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.weight(1f).padding(start = 16.dp),
                    )
                }
                val expandLabel = stringResource(if (expanded) R.string.remote_pane_collapse else R.string.remote_pane_expand)
                WithTooltip(expandLabel) {
                    IconButton(onClick = onToggleExpand) {
                        Icon(if (expanded) Icons.Filled.CloseFullscreen else Icons.Filled.OpenInFull, contentDescription = expandLabel)
                    }
                }
                val hideLabel = stringResource(R.string.remote_pane_hide)
                WithTooltip(hideLabel) {
                    IconButton(onClick = onHide) {
                        Icon(Icons.Filled.VisibilityOff, contentDescription = hideLabel)
                    }
                }
            }
            Box(Modifier.weight(1f)) {
                when (selected) {
                    RemoteTab.NOW_PLAYING -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)) {
                        PlaybackDetails(content.features, content.state.mediaDetails, content.keys, content.playbackActions, content.actions.press, info = content.state.nowPlayingInfo)
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

/** Avancement pleine largeur (titre, curseur précis, chapitres) pour les dispositions larges. */
@Composable
private fun ProgressFor(content: RemoteContent) {
    val playback = content.state.playback ?: return
    val details = content.state.mediaDetails
    val titles = nowPlayingTitles(playback)
    val status = when {
        playback.isPlaying -> stringResource(R.string.remote_status_playing)
        playback.title != null -> stringResource(R.string.remote_status_paused)
        else -> null
    }
    ProgressCard(
        title = titles.title,
        subtitle = titles.subtitle,
        status = status,
        positionMs = playback.positionMs,
        durationMs = playback.durationMs,
        chapters = details?.chapters.orEmpty(),
        canSeek = Capability.SEEK in content.state.capabilities,
        onSeek = content.actions.seekTo,
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
        // Lecteurs sans pavé (Chromecast, DLNA, AirPlay…) : la place revient à la pochette.
        NowPlayingArtwork(content, modifier)
    }
}

/** Grande pochette du média en cours (icône de l'appareil à défaut). */
@Composable
private fun NowPlayingArtwork(content: RemoteContent, modifier: Modifier) {
    val playback = content.state.playback
    Box(modifier.padding(8.dp), contentAlignment = Alignment.Center) {
        KeyedImage(playback?.artworkKey, Modifier.fillMaxSize(), contentScale = ContentScale.Fit) {
            Surface(Modifier.size(160.dp), shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceVariant) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        if (content.state.device?.kind == DeviceKind.CHROMECAST) Icons.Filled.Cast else Icons.Filled.Speaker,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(72.dp),
                    )
                }
            }
        }
        playback?.appName?.takeIf { it.isNotBlank() }?.let { app ->
            Surface(
                Modifier.align(Alignment.BottomCenter).padding(bottom = 4.dp),
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.9f),
            ) {
                Text(app, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp))
            }
        }
    }
}

/**
 * Fond de la télécommande : fanart de la série ou du film (à défaut la pochette, floutée), très
 * atténué et voilé par un dégradé pour que les commandes restent lisibles.
 */
@Composable
internal fun RemoteBackdrop(playback: PlaybackState?) {
    val backdrop = playback?.backdropKey
    val key = backdrop ?: playback?.artworkKey ?: return
    val background = MaterialTheme.colorScheme.background
    Box(Modifier.fillMaxSize()) {
        KeyedImage(
            key,
            Modifier.fillMaxSize().then(if (backdrop == null) Modifier.blur(24.dp) else Modifier),
            alpha = BACKDROP_ALPHA,
        )
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(listOf(background.copy(alpha = 0.25f), background.copy(alpha = 0.7f))),
            ),
        )
    }
}

/** Opacité de l'image de fond : présente sans gêner la lecture des commandes. */
private const val BACKDROP_ALPHA = 0.35f

@Composable
private fun QueueContent(content: RemoteContent) {
    val vm = content.vm
    val queueActions = remember(vm, content.onClearQueue) {
        QueueActions(vm.queue::refresh, vm.queue::play, vm.queue::remove, content.onClearQueue)
    }
    val seriesActions = remember(vm) {
        SeriesActions(
            vm.series::refresh, vm.series::selectSeason, vm::playEntry,
            enqueue = { entry, next -> vm.enqueue(entry, next) },
            info = vm::openItemInfo,
        )
    }
    QueueTabPanel(
        features = content.features,
        queue = content.state.queue,
        series = content.state.series,
        playback = content.state.playback,
        queueActions = queueActions,
        seriesActions = seriesActions,
    )
}

@Composable
private fun LibraryContent(content: RemoteContent) {
    LibraryPanel(
        library = content.state.library,
        onOpenSection = content.vm.library::openSection,
        onOpenEntry = content.vm.library::openEntry,
        onEnqueue = content.vm::enqueue,
        onBack = { content.vm.library.back() },
        onLoadMore = content.vm.library::loadMore,
        onRetry = content.vm.library::open,
        onSearch = if (content.features.search) content.vm.search::open else null,
        images = content.features.images,
        onPlay = content.vm::playEntry,
    )
}
