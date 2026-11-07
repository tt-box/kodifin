package io.github.ttbox.kodifin.ui.remote

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.ttbox.kodifin.model.MediaEntry
import io.github.ttbox.kodifin.model.MediaKind
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import io.github.ttbox.kodifin.R
import io.github.ttbox.kodifin.ui.text.asString
import io.github.ttbox.kodifin.text.text

/** Rappels de la vue de recherche (fournis par le ViewModel). */
class SearchActions(
    val close: () -> Unit,
    val query: (String) -> Unit,
    val submit: () -> Unit,
    val toggleKind: (MediaKind) -> Unit,
    val clearKinds: () -> Unit,
    val open: (MediaEntry) -> Unit,
    val back: () -> Boolean,
    val loadMore: () -> Unit,
    val play: (MediaEntry) -> Unit,
    val enqueue: (MediaEntry, Boolean) -> Unit,
)

/**
 * Recherche plein écran, au-dessus de toute la disposition (fenêtre de dialogue pleine taille) :
 * saisie au fil de la frappe, filtres par nature, résultats en grille d'affiches regroupés par
 * nature. Un résultat navigable s'ouvre dans la même vue (retour : niveau précédent).
 */
@Composable
fun SearchScreen(
    search: SearchState,
    searchKinds: Set<MediaKind>,
    canEnqueue: Boolean,
    images: ImageLoader<ImageBitmap>?,
    snackbar: SnackbarHostState,
    actions: SearchActions,
) {
    Dialog(
        onDismissRequest = actions.close,
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false),
    ) {
        CompositionLocalProvider(LocalImageLoader provides images) {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
                Box(Modifier.fillMaxSize()) {
                    SearchContent(search, searchKinds, canEnqueue, actions)
                    SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(16.dp))
                }
            }
        }
    }
}

@Composable
private fun SearchContent(search: SearchState, searchKinds: Set<MediaKind>, canEnqueue: Boolean, actions: SearchActions) {
    var sheetEntry by remember { mutableStateOf<MediaEntry?>(null) }
    val level = search.current
    // Retour : d'abord les dossiers ouverts depuis un résultat, puis fermeture de la recherche.
    BackHandler(enabled = level != null) { actions.back() }

    val onEntry: (MediaEntry) -> Unit = { if (it.browsable) actions.open(it) else sheetEntry = it }
    val onLongPress: (MediaEntry) -> Unit = { sheetEntry = it }

    Column(Modifier.fillMaxSize()) {
        SearchBarRow(search.query, level, actions)
        if (level == null && searchKinds.size > 1) KindFilters(searchKinds, search.kinds, actions)
        val loading = if (level != null) search.pathLoading else search.loading
        Box(Modifier.fillMaxWidth()) { if (loading) LinearProgressIndicator(Modifier.fillMaxWidth()) }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (level != null) {
                val gridState = remember(search.path.size, level.id) { LazyGridState() }
                when {
                    search.pathError != null && level.entries.isEmpty() ->
                        SearchMessage(Icons.Filled.SearchOff, search.pathError.asString())
                    level.endReached && level.entries.isEmpty() -> SearchMessage(Icons.Filled.SearchOff, stringResource(R.string.remote_folder_empty))
                    else -> MediaGrid(
                        entries = level.entries,
                        onEntry = onEntry,
                        onEntryLongPress = onLongPress,
                        state = gridState,
                        onLoadMore = actions.loadMore.takeIf { !level.endReached },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            } else {
                val gridState = remember { LazyGridState() }
                val query = search.query.trim()
                when {
                    search.error != null ->
                        SearchMessage(Icons.Filled.SearchOff, search.error.asString(), stringResource(R.string.remote_retry), actions.submit)
                    query.length < MIN_QUERY -> SearchMessage(
                        Icons.Filled.Search,
                        if (query.isEmpty()) {
                            stringResource(R.string.remote_search_hint)
                        } else {
                            pluralStringResource(R.plurals.remote_search_min_length, MIN_QUERY, MIN_QUERY)
                        },
                    )
                    search.searched && search.results.isEmpty() && !search.loading ->
                        SearchMessage(Icons.Filled.SearchOff, stringResource(R.string.remote_search_no_results, query))
                    else -> MediaGrid(
                        entries = search.results,
                        onEntry = onEntry,
                        onEntryLongPress = onLongPress,
                        grouped = true,
                        state = gridState,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
    }
    sheetEntry?.let { entry ->
        EntryActionsSheet(
            entry = entry,
            onDismiss = { sheetEntry = null },
            onPlay = actions.play,
            onEnqueue = actions.enqueue,
            canEnqueue = canEnqueue,
        )
    }
}

@Composable
private fun SearchBarRow(query: String, level: LibraryLevel?, actions: SearchActions) {
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = remember { FocusRequester() }
    var text by remember { mutableStateOf(TextFieldValue(query, TextRange(query.length))) }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { if (!actions.back()) actions.close() }) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = stringResource(if (level != null) R.string.remote_search_back else R.string.remote_search_close),
            )
        }
        if (level != null) {
            Text(
                level.title,
                style = MaterialTheme.typography.titleLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(horizontal = 8.dp).semantics { heading() },
            )
            IconButton(onClick = actions.close) { Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.remote_search_close)) }
        } else {
            TextField(
                value = text,
                onValueChange = {
                    text = it
                    actions.query(it.text)
                },
                placeholder = { Text(stringResource(R.string.remote_search_placeholder)) },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                trailingIcon = if (text.text.isNotEmpty()) {
                    {
                        IconButton(onClick = {
                            text = TextFieldValue("")
                            actions.query("")
                            runCatching { focus.requestFocus() }
                        }) { Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.remote_search_clear)) }
                    }
                } else {
                    null
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = {
                    actions.submit()
                    keyboard?.hide()
                }),
                shape = MaterialTheme.shapes.extraLarge,
                colors = TextFieldDefaults.colors(
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                ),
                modifier = Modifier.weight(1f).padding(end = 8.dp).focusRequester(focus),
            )
        }
    }
}

@Composable
private fun KindFilters(kinds: Set<MediaKind>, selected: Set<MediaKind>, actions: SearchActions) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FilterChip(
            selected = selected.isEmpty(),
            onClick = actions.clearKinds,
            label = { Text(stringResource(R.string.remote_search_all_kinds)) },
            leadingIcon = if (selected.isEmpty()) {
                { Icon(Icons.Filled.Check, contentDescription = null, Modifier.size(FilterChipDefaults.IconSize)) }
            } else {
                null
            },
        )
        MediaKind.entries.filter { it in kinds }.forEach { kind ->
            val on = kind in selected
            FilterChip(
                selected = on,
                onClick = { actions.toggleKind(kind) },
                label = { Text(kind.text.asString()) },
                leadingIcon = {
                    Icon(
                        if (on) Icons.Filled.Check else kindIcon(kind),
                        contentDescription = null,
                        Modifier.size(FilterChipDefaults.IconSize),
                    )
                },
            )
        }
    }
}

@Composable
private fun SearchMessage(icon: ImageVector, text: String, actionLabel: String? = null, onAction: (() -> Unit)? = null) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(48.dp))
        Text(
            text,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 12.dp),
        )
        if (actionLabel != null && onAction != null) TextButton(onClick = onAction) { Text(actionLabel) }
    }
}

private const val MIN_QUERY = 2
