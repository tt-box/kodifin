package io.github.jmorille.zapette.ui.remote

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.ClearAll
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Equalizer
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.jmorille.zapette.model.MediaEntry
import io.github.jmorille.zapette.model.MediaSection

@Composable
private fun PanelHeader(
    title: String,
    modifier: Modifier = Modifier,
    navigation: (@Composable () -> Unit)? = null,
    actions: @Composable () -> Unit = {},
) {
    Row(modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
        navigation?.invoke()
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(horizontal = 8.dp).semantics { heading() },
        )
        actions()
    }
}

@Composable
private fun EmptyText(text: String) {
    Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// --- File d'attente ---------------------------------------------------------------------------

@Composable
fun QueuePanel(
    queue: QueueState,
    onRefresh: () -> Unit,
    onPlay: (Int) -> Unit,
    onRemove: (Int) -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize()) {
        PanelHeader("File d'attente (${queue.items.size})") {
            IconButton(onClick = onRefresh) { Icon(Icons.Filled.Refresh, contentDescription = "Actualiser la file d'attente") }
            IconButton(onClick = onClear, enabled = queue.items.isNotEmpty()) {
                Icon(Icons.Filled.ClearAll, contentDescription = "Vider la file d'attente")
            }
        }
        if (queue.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (queue.loaded && queue.items.isEmpty()) EmptyText("La file d'attente est vide")
        val listState = rememberLazyListState()
        // Amène l'élément en cours à l'écran à l'ouverture et à chaque changement.
        LaunchedEffect(queue.position) { queue.position?.let { listState.scrollToItem((it - 1).coerceAtLeast(0)) } }
        LazyColumn(state = listState, modifier = Modifier.weight(1f)) {
            itemsIndexed(queue.items, key = { index, item -> "$index:${item.id}" }) { index, item ->
                val current = index == queue.position
                ListItem(
                    headlineContent = { Text(item.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                    supportingContent = item.subtitle?.let { { Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis) } },
                    leadingContent = {
                        if (current) {
                            Icon(Icons.Filled.Equalizer, contentDescription = "En cours", tint = MaterialTheme.colorScheme.primary)
                        } else {
                            Text("${index + 1}", style = MaterialTheme.typography.labelLarge)
                        }
                    },
                    trailingContent = {
                        IconButton(onClick = { onRemove(index) }) {
                            Icon(Icons.Filled.Close, contentDescription = "Retirer ${item.title} de la file")
                        }
                    },
                    colors = if (current) {
                        ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
                    } else {
                        ListItemDefaults.colors()
                    },
                    modifier = Modifier.clickable(onClickLabel = "Lire") { onPlay(index) },
                )
            }
        }
    }
}

// --- Bibliothèque -----------------------------------------------------------------------------

@Composable
fun LibraryPanel(
    library: LibraryState,
    onOpenSection: (MediaSection) -> Unit,
    onOpenEntry: (MediaEntry) -> Unit,
    onEnqueue: (MediaEntry, Boolean) -> Unit,
    onBack: () -> Unit,
    onLoadMore: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val level = library.current
    Column(modifier.fillMaxSize()) {
        PanelHeader(
            title = level?.title ?: "Bibliothèque",
            navigation = if (level != null) {
                { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Dossier parent") } }
            } else {
                null
            },
        ) {
            if (level == null && !library.sectionsLoaded && !library.loading) {
                IconButton(onClick = onRetry) { Icon(Icons.Filled.Refresh, contentDescription = "Recharger la bibliothèque") }
            }
        }
        if (library.path.size > 1) {
            Text(
                library.path.joinToString(" › ") { it.title },
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
        if (library.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        HorizontalDivider()
        // Une liste par niveau : chaque dossier ouvert repart en haut de liste.
        val listState = remember(library.path.size, level?.id) { LazyListState() }
        if (level == null) {
            if (library.sectionsLoaded && library.sections.isEmpty()) EmptyText("Aucune rubrique")
            LazyColumn(state = listState, modifier = Modifier.weight(1f)) {
                items(library.sections, key = { it.id }) { section ->
                    ListItem(
                        headlineContent = { Text(section.title) },
                        leadingContent = { Icon(Icons.Filled.LibraryMusic, contentDescription = null) },
                        modifier = Modifier.clickable(onClickLabel = "Ouvrir") { onOpenSection(section) },
                    )
                }
            }
        } else {
            if (level.endReached && level.entries.isEmpty()) EmptyText("Dossier vide")
            val nearEnd by remember(listState) {
                derivedStateOf {
                    val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
                    last >= listState.layoutInfo.totalItemsCount - LOAD_MORE_THRESHOLD
                }
            }
            LaunchedEffect(nearEnd, level.entries.size, level.endReached) {
                if (nearEnd && !level.endReached) onLoadMore()
            }
            LazyColumn(state = listState, modifier = Modifier.weight(1f)) {
                itemsIndexed(level.entries, key = { index, entry -> "$index:${entry.id}" }) { _, entry ->
                    LibraryEntryItem(entry, onOpenEntry, onEnqueue)
                }
            }
        }
    }
}

@Composable
private fun LibraryEntryItem(entry: MediaEntry, onOpen: (MediaEntry) -> Unit, onEnqueue: (MediaEntry, Boolean) -> Unit) {
    var menu by remember { mutableStateOf(false) }
    ListItem(
        headlineContent = { Text(entry.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        supportingContent = entry.subtitle?.let { { Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis) } },
        leadingContent = {
            Icon(
                if (entry.browsable) Icons.Filled.Folder else Icons.Filled.PlayArrow,
                contentDescription = if (entry.browsable) "Dossier" else "Élément jouable",
            )
        },
        trailingContent = if (entry.browsable) {
            null
        } else {
            {
                Box {
                    IconButton(onClick = { menu = true }) {
                        Icon(Icons.AutoMirrored.Filled.PlaylistAdd, contentDescription = "Ajouter ${entry.title} à la file")
                    }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("Lire maintenant") }, onClick = { menu = false; onOpen(entry) })
                        DropdownMenuItem(text = { Text("Lire juste après") }, onClick = { menu = false; onEnqueue(entry, true) })
                        DropdownMenuItem(text = { Text("Ajouter à la file d'attente") }, onClick = { menu = false; onEnqueue(entry, false) })
                    }
                }
            }
        },
        modifier = Modifier.clickable(onClickLabel = if (entry.browsable) "Ouvrir" else "Lire") { onOpen(entry) },
    )
}

private const val LOAD_MORE_THRESHOLD = 10
