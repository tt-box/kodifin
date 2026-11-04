package io.github.jmorille.zapette.ui.remote

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.jmorille.zapette.model.MediaEntry
import io.github.jmorille.zapette.model.MediaKind
import io.github.jmorille.zapette.model.PlaybackState
import io.github.jmorille.zapette.model.episodeCode

// --- Logique pure (testée sur JVM) ------------------------------------------------------------

/** Titre et sous-titre du média en cours : pour un épisode, la série puis « S01E10 · Titre ». */
data class NowPlayingTitles(val title: String?, val subtitle: String?)

fun nowPlayingTitles(playback: PlaybackState): NowPlayingTitles {
    val series = playback.seriesTitle?.takeIf { it.isNotBlank() }
        ?: return NowPlayingTitles(
            playback.title,
            // Musique : « Artiste · Album » ; sinon l'application qui diffuse (Chromecast).
            listOfNotNull(playback.artist, playback.album).filter { it.isNotBlank() }.joinToString(" · ")
                .ifEmpty { playback.appName?.takeIf { it.isNotBlank() && it != playback.title } },
        )
    val subtitle = listOfNotNull(playback.episodeCode(), playback.title?.takeIf { it != series })
        .joinToString(" · ")
        .ifEmpty { null }
    return NowPlayingTitles(series, subtitle)
}

/** « S01E03 » (ou « E03 ») d'un épisode de bibliothèque, sinon `null`. */
fun MediaEntry.episodeLabel(): String? = when {
    season != null && episode != null -> "S%02dE%02d".format(season, episode)
    episode != null -> "E%02d".format(episode)
    else -> null
}

/** Résultats regroupés par nature, dans l'ordre de [MediaKind] (nature inconnue en dernier). */
fun groupByKind(entries: List<MediaEntry>): List<Pair<MediaKind?, List<MediaEntry>>> {
    val groups = entries.groupBy { it.kind }
    val order: List<MediaKind?> = MediaKind.entries.filter { groups.containsKey(it) } +
        if (groups.containsKey(null)) listOf(null) else emptyList()
    return order.map { it to groups.getValue(it) }
}

/** Ligne de détail d'une affiche : année puis sous-titre, ou le code d'épisode. */
fun MediaEntry.posterCaption(): String? =
    listOfNotNull(episodeLabel(), year?.toString(), subtitle?.takeIf { it.isNotBlank() })
        .joinToString(" · ")
        .ifEmpty { null }

/** Proportions de l'image : vignette 16:9 (épisode, chaîne), carré (musique), affiche 2:3. */
fun posterAspect(kind: MediaKind?): Float = when (kind) {
    MediaKind.EPISODE, MediaKind.CHANNEL -> 16f / 9f
    MediaKind.ALBUM, MediaKind.ARTIST, MediaKind.SONG -> 1f
    MediaKind.FOLDER, MediaKind.OTHER, null -> 1f
    else -> 2f / 3f
}

fun kindIcon(kind: MediaKind?, browsable: Boolean = false): ImageVector = when (kind) {
    MediaKind.MOVIE -> Icons.Filled.Movie
    MediaKind.SERIES, MediaKind.SEASON, MediaKind.EPISODE -> Icons.Filled.Tv
    MediaKind.ARTIST -> Icons.Filled.Person
    MediaKind.ALBUM -> Icons.Filled.Album
    MediaKind.SONG -> Icons.Filled.MusicNote
    MediaKind.CHANNEL -> Icons.Filled.LiveTv
    MediaKind.FOLDER -> Icons.Filled.Folder
    MediaKind.OTHER, null -> if (browsable) Icons.Filled.Folder else Icons.Filled.VideoLibrary
}

/** Largeur minimale d'une affiche dans la grille, selon la place disponible. */
fun posterMinWidth(availableWidth: Dp): Dp = if (availableWidth >= 600.dp) 140.dp else 110.dp

// --- Images -----------------------------------------------------------------------------------

/** Chargeur d'images du contrôleur courant ; `null` : pas d'images (icône seule). */
val LocalImageLoader = compositionLocalOf<ImageLoader<ImageBitmap>?> { null }

/**
 * Image d'un élément (affiche, vignette) à la taille réelle de la cellule, avec une icône selon
 * la nature en attendant (ou à défaut). Le chargement est annulé quand l'élément quitte l'écran.
 */
@Composable
fun MediaImage(entry: MediaEntry, modifier: Modifier = Modifier, iconSize: Dp = 36.dp) {
    val loader = LocalImageLoader.current
    Surface(modifier, color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.small) {
        BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            val key = entry.imageKey
            val width = constraints.maxWidth.takeIf { it in 1 until Int.MAX_VALUE } ?: 0
            var image by remember(key, loader, width) {
                mutableStateOf(if (key != null && loader != null && width > 0) loader.cached(key, width) else null)
            }
            if (key != null && loader != null && width > 0 && image == null) {
                LaunchedEffect(key, loader, width) { image = loader.load(key, width) }
            }
            val bitmap = image
            if (bitmap != null) {
                Image(bitmap, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            } else {
                Icon(
                    kindIcon(entry.kind, entry.browsable),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(iconSize),
                )
            }
        }
    }
}

/**
 * Image désignée par [imageKey] (pochette, fond), chargée à la largeur réelle ; [fallback] est
 * affiché tant qu'elle manque (ou s'il n'y a ni clé ni chargeur).
 */
@Composable
fun KeyedImage(
    imageKey: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    alpha: Float = 1f,
    fallback: @Composable () -> Unit = {},
) {
    val loader = LocalImageLoader.current
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        val width = constraints.maxWidth.takeIf { it in 1 until Int.MAX_VALUE } ?: 0
        var image by remember(imageKey, loader, width) {
            mutableStateOf(if (imageKey != null && loader != null && width > 0) loader.cached(imageKey, width) else null)
        }
        if (imageKey != null && loader != null && width > 0 && image == null) {
            LaunchedEffect(imageKey, loader, width) { image = loader.load(imageKey, width) }
        }
        val bitmap = image
        if (bitmap != null) {
            Image(bitmap, contentDescription = null, contentScale = contentScale, alpha = alpha, modifier = Modifier.fillMaxSize())
        } else {
            fallback()
        }
    }
}

/** Pastille « vu » posée sur une image. */
@Composable
fun WatchedBadge(modifier: Modifier = Modifier) {
    Surface(modifier.size(22.dp), shape = CircleShape, color = MaterialTheme.colorScheme.primary) {
        Icon(Icons.Filled.Check, contentDescription = "Déjà vu", tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.padding(3.dp))
    }
}

// --- Grille d'affiches ------------------------------------------------------------------------

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PosterCard(entry: MediaEntry, onClick: () -> Unit, onLongClick: (() -> Unit)?, modifier: Modifier = Modifier) {
    Column(
        modifier
            .clip(MaterialTheme.shapes.small)
            .combinedClickable(
                onClickLabel = if (entry.browsable) "Ouvrir" else "Actions",
                onLongClickLabel = if (onLongClick != null) "Ajouter à la file" else null,
                onLongClick = onLongClick,
                onClick = onClick,
            )
            .padding(4.dp),
    ) {
        Box {
            MediaImage(entry, Modifier.fillMaxWidth().aspectRatio(posterAspect(entry.kind)))
            if (entry.watched == true) WatchedBadge(Modifier.align(Alignment.TopEnd).padding(4.dp))
        }
        Text(
            entry.title,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 4.dp),
        )
        entry.posterCaption()?.let {
            Text(
                it,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * Grille adaptative d'affiches. [grouped] : en-têtes de section par nature. [onLoadMore] est
 * appelé à l'approche de la fin (pagination).
 */
@Composable
fun MediaGrid(
    entries: List<MediaEntry>,
    onEntry: (MediaEntry) -> Unit,
    onEntryLongPress: (MediaEntry) -> Unit,
    modifier: Modifier = Modifier,
    grouped: Boolean = false,
    state: LazyGridState = remember { LazyGridState() },
    onLoadMore: (() -> Unit)? = null,
    header: (@Composable () -> Unit)? = null,
) {
    BoxWithConstraints(modifier) {
        val minWidth = posterMinWidth(maxWidth)
        if (onLoadMore != null) {
            val nearEnd by remember(state) {
                derivedStateOf {
                    val info = state.layoutInfo
                    val last = info.visibleItemsInfo.lastOrNull()?.index ?: -1
                    info.totalItemsCount > 0 && last >= info.totalItemsCount - GRID_LOAD_MORE_THRESHOLD
                }
            }
            LaunchedEffect(nearEnd, entries.size) { if (nearEnd) onLoadMore() }
        }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minWidth),
            state = state,
            contentPadding = PaddingValues(8.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            if (header != null) item(span = { GridItemSpan(maxLineSpan) }, contentType = "header") { header() }
            val sections = if (grouped) groupByKind(entries) else listOf(null to entries)
            sections.forEach { (kind, items) ->
                if (grouped && sections.size > 1) {
                    item(key = "section:${kind?.name}", span = { GridItemSpan(maxLineSpan) }, contentType = "section") {
                        Text(
                            kind?.label ?: "Autres",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(start = 4.dp, top = 8.dp, bottom = 2.dp).semantics { heading() },
                        )
                    }
                }
                items(items, key = { "${kind?.name}:${it.id}" }, contentType = { "poster" }) { entry ->
                    PosterCard(
                        entry,
                        onClick = { onEntry(entry) },
                        onLongClick = if (entry.browsable) null else ({ onEntryLongPress(entry) }),
                    )
                }
            }
        }
    }
}

private const val GRID_LOAD_MORE_THRESHOLD = 12

// --- Actions sur un élément jouable -----------------------------------------------------------

/**
 * Feuille d'actions d'un élément jouable (Lire maintenant / Lire ensuite / Ajouter à la file).
 * Contenu figé sur l'élément choisi et feuille entièrement dépliée : pas de saut à chaque
 * mise à jour d'état.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EntryActionsSheet(
    entry: MediaEntry,
    onDismiss: () -> Unit,
    onPlay: (MediaEntry) -> Unit,
    onEnqueue: (MediaEntry, Boolean) -> Unit,
    canEnqueue: Boolean = true,
) {
    val frozen = remember { entry }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 16.dp)) {
            ListItem(
                headlineContent = { Text(frozen.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                supportingContent = frozen.posterCaption()?.let { { Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis) } },
                leadingContent = {
                    MediaImage(frozen, Modifier.size(width = 56.dp, height = 56.dp / posterAspect(frozen.kind)), iconSize = 24.dp)
                },
            )
            SheetAction(Icons.Filled.PlayArrow, "Lire maintenant") { onDismiss(); onPlay(frozen) }
            if (canEnqueue) {
                SheetAction(Icons.Filled.SkipNext, "Lire ensuite") { onDismiss(); onEnqueue(frozen, true) }
                SheetAction(Icons.AutoMirrored.Filled.PlaylistAdd, "Ajouter à la file d'attente") { onDismiss(); onEnqueue(frozen, false) }
            }
        }
    }
}

@Composable
private fun SheetAction(icon: ImageVector, label: String, onClick: () -> Unit) {
    Surface(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        ListItem(headlineContent = { Text(label) }, leadingContent = { Icon(icon, contentDescription = null) })
    }
}
