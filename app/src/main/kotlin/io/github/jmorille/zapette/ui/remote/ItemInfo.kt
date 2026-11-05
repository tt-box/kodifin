package io.github.jmorille.zapette.ui.remote

import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.jmorille.zapette.model.MediaEntry
import io.github.jmorille.zapette.model.MediaItemInfo

// --- Logique pure (testée sur JVM) ------------------------------------------------------------

/** « 08/04/2017 » à partir de `2017-04-08` ; la valeur brute si elle n'a pas ce format. */
fun frenchDate(iso: String): String {
    val parts = iso.split('-')
    return if (parts.size == 3 && parts.all { p -> p.all(Char::isDigit) }) "${parts[2]}/${parts[1]}/${parts[0]}" else iso
}

/** « 24 min », « 1 h 05 ». */
fun durationLabel(ms: Long): String {
    val minutes = ((ms + 30_000) / 60_000).coerceAtLeast(1)
    return if (minutes < 60) "$minutes min" else "${minutes / 60} h %02d".format(minutes % 60)
}

/** Ligne de repères : « S02E02 · Diffusé le 08/04/2017 · 24 min · ★ 8,2 · TV-14 ». */
fun infoFacts(info: MediaItemInfo): String? {
    val code = when {
        info.season != null && info.episode != null -> "S%02dE%02d".format(info.season, info.episode)
        info.episode != null -> "E%02d".format(info.episode)
        else -> null
    }
    val date = info.airDate?.let { if (info.episode != null) "Diffusé le ${frenchDate(it)}" else "Sorti le ${frenchDate(it)}" }
        ?: info.year?.toString()
    val rating = info.rating?.let { "★ " + "%.1f".format(it).replace('.', ',') }
    return listOfNotNull(code, date, info.runtimeMs?.let(::durationLabel), rating, info.officialRating)
        .joinToString(" · ").ifEmpty { null }
}

/** Distribution abrégée : les [max] premiers noms, puis « et N autres ». */
fun castSummary(cast: List<String>, max: Int = 8): String? {
    if (cast.isEmpty()) return null
    val shown = cast.take(max).joinToString(", ")
    return if (cast.size > max) "$shown et ${cast.size - max} autres" else shown
}

// --- Affichage --------------------------------------------------------------------------------

/** Contenu d'une fiche : repères, résumé, genres, équipe et flux. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ItemInfoContent(
    info: MediaItemInfo,
    modifier: Modifier = Modifier,
    showTitle: Boolean = true,
    /** Image de la fiche (vignette d'épisode, affiche) en tête, si le contrôleur en fournit une. */
    showImage: Boolean = false,
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (showImage && info.imageKey != null) {
            KeyedImage(
                info.imageKey,
                Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(MaterialTheme.shapes.medium),
            )
        }
        if (showTitle) {
            info.seriesTitle?.let { Text(it, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary) }
            Text(info.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        }
        infoFacts(info)?.let { Text(it, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        Text(
            info.overview ?: "Aucun résumé disponible.",
            style = MaterialTheme.typography.bodyMedium,
            color = if (info.overview == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
        )
        if (info.genres.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                info.genres.forEach { AssistChip(onClick = {}, label = { Text(it) }, enabled = false) }
            }
        }
        InfoLine("Réalisation", info.directors.joinToString(", ").ifEmpty { null })
        InfoLine("Scénario", info.writers.joinToString(", ").ifEmpty { null })
        InfoLine("Distribution", castSummary(info.cast))
        InfoLine("Studio", info.studios.joinToString(", ").ifEmpty { null })
        InfoLine("Vidéo", info.video)
        InfoLine("Audio", info.audio)
    }
}

@Composable
private fun InfoLine(label: String, value: String?) {
    value ?: return
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp))
        Text(value, style = MaterialTheme.typography.bodySmall)
    }
}

/** Carte repliable « Résumé » du média en cours (volet de lecture). */
@Composable
fun NowPlayingInfoCard(info: MediaItemInfo, modifier: Modifier = Modifier) {
    val summary = infoFacts(info) ?: info.overview?.take(80)
    CollapsibleCard("Résumé et informations", summary = summary, modifier = modifier.fillMaxWidth()) {
        ItemInfoContent(info, showTitle = false, showImage = true)
    }
}

/**
 * Fiche d'un épisode ouverte depuis la liste : image, repères, résumé complet, équipe, puis
 * lecture. Ouverte dépliée d'emblée (pas d'état intermédiaire qui se recale au chargement).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ItemInfoSheet(
    state: ItemInfoState,
    onDismiss: () -> Unit,
    onPlay: (MediaEntry) -> Unit,
    onEnqueue: ((MediaEntry, Boolean) -> Unit)?,
) {
    val entry = state.entry ?: return
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val image = entry.imageKey ?: state.info?.imageKey
            MediaImage(entry.copy(imageKey = image), Modifier.fillMaxWidth().height(200.dp), iconSize = 48.dp)
            if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            val info = state.info
            if (info != null) {
                ItemInfoContent(info)
            } else if (!state.loading) {
                Text(entry.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Text(entry.overview ?: "Aucune information disponible.", style = MaterialTheme.typography.bodyMedium)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = { onDismiss(); onPlay(entry) }) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = null)
                    Text("Lire", Modifier.padding(start = 6.dp))
                }
                if (onEnqueue != null) {
                    OutlinedButton(onClick = { onDismiss(); onEnqueue(entry, true) }) {
                        Icon(Icons.Filled.SkipNext, contentDescription = null)
                        Text("Ensuite", Modifier.padding(start = 6.dp))
                    }
                    OutlinedButton(onClick = { onDismiss(); onEnqueue(entry, false) }) {
                        Icon(Icons.AutoMirrored.Filled.PlaylistAdd, contentDescription = null)
                        Text("File", Modifier.padding(start = 6.dp))
                    }
                }
            }
        }
    }
}
