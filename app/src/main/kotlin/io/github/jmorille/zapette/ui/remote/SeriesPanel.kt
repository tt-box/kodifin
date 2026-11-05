package io.github.jmorille.zapette.ui.remote

import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Equalizer
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.jmorille.zapette.model.MediaEntry
import io.github.jmorille.zapette.model.PlaybackState

/** L'épisode [entry] est-il celui en cours de lecture ? (identifiant, sinon saison et numéro). */
fun isCurrentEpisode(entry: MediaEntry, series: SeriesState, playback: PlaybackState?): Boolean {
    val currentId = series.context?.currentEpisodeId
    if (currentId != null) return entry.id == currentId
    return playback?.episode != null && entry.episode == playback.episode &&
        (playback.season == null || entry.season == null || entry.season == playback.season)
}

/** Le média en cours est-il un épisode de série ? (sélecteur « File d'attente | Série »). */
fun showsSeries(features: RemoteFeatures, series: SeriesState, playback: PlaybackState?): Boolean =
    features.series && (series.context != null || playback?.seriesTitle != null || playback?.episode != null)

/**
 * Volet « File » : file d'attente et, pour un épisode, navigation dans la série (saisons puis
 * épisodes) derrière un sélecteur segmenté.
 */
@Composable
fun QueueTabPanel(
    features: RemoteFeatures,
    queue: QueueState,
    series: SeriesState,
    playback: PlaybackState?,
    queueActions: QueueActions,
    seriesActions: SeriesActions,
    modifier: Modifier = Modifier,
) {
    val withSeries = showsSeries(features, series, playback)
    // null : choix automatique (la série d'abord quand un épisode est en cours).
    var showSeries by rememberSaveable { mutableStateOf<Boolean?>(null) }
    val seriesSelected = withSeries && (showSeries ?: true) || !features.queue
    Column(modifier.fillMaxSize()) {
        if (withSeries && features.queue) {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
                SegmentedButton(
                    selected = !seriesSelected,
                    onClick = { showSeries = false },
                    shape = SegmentedButtonDefaults.itemShape(0, 2),
                ) { Text("File d'attente") }
                SegmentedButton(
                    selected = seriesSelected,
                    onClick = { showSeries = true },
                    shape = SegmentedButtonDefaults.itemShape(1, 2),
                ) { Text("Série") }
            }
        }
        Box(Modifier.weight(1f)) {
            if (seriesSelected) {
                SeriesPanel(series, playback, seriesActions)
            } else {
                // Retirer / vider seulement si l'appareil le permet (pas sur un client Jellyfin piloté).
                QueuePanel(
                    queue, queueActions.refresh, queueActions.play,
                    queueActions.remove.takeIf { features.queueEdit }, queueActions.clear.takeIf { features.queueEdit },
                )
            }
        }
    }
}

class QueueActions(
    val refresh: () -> Unit,
    val play: (Int) -> Unit,
    val remove: (Int) -> Unit,
    val clear: () -> Unit,
)

class SeriesActions(
    val refresh: () -> Unit,
    val selectSeason: (String) -> Unit,
    val play: (MediaEntry) -> Unit,
    val enqueue: (MediaEntry, Boolean) -> Unit,
    /** Fiche de l'épisode (résumé, distribution) ; `null` si l'appareil n'en fournit pas. */
    val info: ((MediaEntry) -> Unit)? = null,
)

@Composable
fun SeriesPanel(series: SeriesState, playback: PlaybackState?, actions: SeriesActions, modifier: Modifier = Modifier) {
    val context = series.context
    Column(modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                context?.title ?: playback?.seriesTitle ?: "Série",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).semantics { heading() },
            )
            IconButton(onClick = actions.refresh) { Icon(Icons.Filled.Refresh, contentDescription = "Actualiser la série") }
        }
        if (context != null && context.seasons.isNotEmpty()) {
            val selected = context.seasons.indexOfFirst { it.id == series.seasonId }.coerceAtLeast(0)
            PrimaryScrollableTabRow(selectedTabIndex = selected, edgePadding = 12.dp) {
                context.seasons.forEachIndexed { index, season ->
                    Tab(
                        selected = index == selected,
                        onClick = { actions.selectSeason(season.id) },
                        text = {
                            Text(
                                season.title,
                                maxLines = 1,
                                fontWeight = if (season.id == context.currentSeasonId) FontWeight.Bold else null,
                            )
                        },
                    )
                }
            }
        }
        if (series.loading || series.episodesLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
        when {
            series.error != null -> SeriesMessage(series.error, actionLabel = "Réessayer", onAction = actions.refresh)
            series.loaded && context == null -> SeriesMessage("Le média en cours n'est pas un épisode de série")
            context != null && context.seasons.isEmpty() -> SeriesMessage("Aucune saison disponible")
            context != null && !series.episodesLoading && series.episodes.isEmpty() -> SeriesMessage("Aucun épisode dans cette saison")
        }
        EpisodeList(series, playback, actions, Modifier.weight(1f))
    }
}

@Composable
private fun SeriesMessage(text: String, actionLabel: String? = null, onAction: (() -> Unit)? = null) {
    Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (actionLabel != null && onAction != null) TextButton(onClick = onAction) { Text(actionLabel) }
    }
}

@Composable
private fun EpisodeList(series: SeriesState, playback: PlaybackState?, actions: SeriesActions, modifier: Modifier) {
    val gridState = rememberLazyGridState()
    val current = series.episodes.indexOfFirst { isCurrentEpisode(it, series, playback) }
    // Épisode en cours amené à l'écran (à l'ouverture, au changement de saison ou d'épisode).
    LaunchedEffect(series.seasonId, current, series.episodes.size) {
        if (current >= 0) gridState.animateScrollToItem((current - 1).coerceAtLeast(0))
    }
    BoxWithConstraints(modifier) {
        // Volet étroit : une colonne ; volet agrandi : plusieurs colonnes, vignettes et résumés plus grands.
        val wide = maxWidth >= WIDE_EPISODES
        LazyVerticalGrid(
            columns = GridCells.Adaptive(EPISODE_MIN_WIDTH),
            state = gridState,
            modifier = Modifier.fillMaxSize(),
        ) {
            itemsIndexed(series.episodes, key = { index, e -> "$index:${e.id}" }) { index, episode ->
                EpisodeRow(episode, index == current, actions, wide)
            }
        }
    }
}

/** Largeur minimale d'une colonne d'épisodes. */
private val EPISODE_MIN_WIDTH = 360.dp

/** À partir de cette largeur de volet, les épisodes s'affichent en grand. */
private val WIDE_EPISODES = 720.dp

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun EpisodeRow(episode: MediaEntry, current: Boolean, actions: SeriesActions, wide: Boolean = false) {
    var menu by remember { mutableStateOf(false) }
    Surface(
        color = if (current) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface.copy(alpha = 0f),
        modifier = Modifier.fillMaxWidth().combinedClickable(
            onClickLabel = "Lire",
            onLongClickLabel = "Ajouter à la file",
            onLongClick = { menu = true },
            onClick = { actions.play(episode) },
        ),
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box {
                val thumb = if (wide) Modifier.size(width = 176.dp, height = 99.dp) else Modifier.size(width = 120.dp, height = 68.dp)
                MediaImage(episode, thumb, iconSize = 28.dp)
                if (episode.watched == true && !current) WatchedBadge(Modifier.align(Alignment.TopEnd).padding(3.dp))
                if (current) {
                    Surface(
                        shape = MaterialTheme.shapes.extraSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.align(Alignment.BottomStart).padding(3.dp),
                    ) {
                        Icon(
                            Icons.Filled.Equalizer,
                            contentDescription = "En cours",
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(20.dp).padding(2.dp),
                        )
                    }
                }
            }
            Column(Modifier.weight(1f)) {
                episode.episodeLabel()?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.labelMedium,
                        color = if (current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                Text(
                    episode.title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (current) FontWeight.SemiBold else null,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                val status = when {
                    current -> "En cours de lecture"
                    episode.watched == true -> "Vu"
                    else -> episode.subtitle
                }
                status?.let {
                    Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                }
                episode.overview?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = if (wide) 4 else 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
            Box {
                IconButton(onClick = { menu = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = "Actions pour ${episode.title}")
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    actions.info?.let { open ->
                        DropdownMenuItem(text = { Text("Résumé et informations") }, onClick = { menu = false; open(episode) })
                    }
                    DropdownMenuItem(text = { Text("Lire maintenant") }, onClick = { menu = false; actions.play(episode) })
                    DropdownMenuItem(text = { Text("Lire ensuite") }, onClick = { menu = false; actions.enqueue(episode, true) })
                    DropdownMenuItem(text = { Text("Ajouter à la file d'attente") }, onClick = { menu = false; actions.enqueue(episode, false) })
                }
            }
        }
    }
}
