package io.github.ttbox.kodifin.ui.remote

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
import io.github.ttbox.kodifin.model.MediaEntry
import io.github.ttbox.kodifin.model.MediaItemInfo
import androidx.compose.ui.res.stringResource
import io.github.ttbox.kodifin.R
import io.github.ttbox.kodifin.text.UiText
import io.github.ttbox.kodifin.ui.text.asString
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.format.FormatStyle
import java.util.Locale

// --- Logique pure (testée sur JVM) ------------------------------------------------------------

/**
 * Date courte de [locale] (« 08/04/2017 » en français) à partir de `2017-04-08` ; la valeur brute
 * si elle n'a pas ce format.
 */
fun localDate(iso: String, locale: Locale = Locale.getDefault()): String = try {
    LocalDate.parse(iso).format(DateTimeFormatter.ofLocalizedDate(FormatStyle.SHORT).withLocale(locale))
} catch (_: DateTimeParseException) {
    iso
}

/** « 24 min », « 1 h 05 ». */
fun durationLabel(ms: Long): UiText {
    val minutes = ((ms + 30_000) / 60_000).coerceAtLeast(1).toInt()
    return if (minutes < 60) {
        UiText.Res(R.string.remote_duration_minutes, minutes)
    } else {
        UiText.Res(R.string.remote_duration_hours, minutes / 60, minutes % 60)
    }
}

/**
 * Repères d'une fiche, à séparer par « · » : « S02E02 », « Diffusé le 08/04/2017 », « 24 min »,
 * « ★ 8,2 », « TV-14 » ; liste vide s'il n'y a rien à dire.
 */
fun infoFacts(info: MediaItemInfo, locale: Locale = Locale.getDefault()): List<UiText> {
    val code = when {
        info.season != null && info.episode != null -> "S%02dE%02d".format(info.season, info.episode)
        info.episode != null -> "E%02d".format(info.episode)
        else -> null
    }
    val date = info.airDate?.let {
        UiText.Res(if (info.episode != null) R.string.remote_info_aired else R.string.remote_info_released, localDate(it, locale))
    } ?: info.year?.let { UiText.Raw(it.toString()) }
    val rating = info.rating?.let { UiText.Raw("★ " + String.format(locale, "%.1f", it)) }
    return listOfNotNull(
        code?.let(UiText::Raw), date, info.runtimeMs?.let(::durationLabel), rating, info.officialRating?.let(UiText::Raw),
    )
}

/** Repères affichés sur une ligne, `null` s'il n'y en a aucun. */
@Composable
private fun infoFactsText(info: MediaItemInfo): String? =
    infoFacts(info).map { it.asString() }.joinToString(" · ").ifEmpty { null }

/** Distribution abrégée : les [max] premiers noms, puis « et N autres ». */
fun castSummary(cast: List<String>, max: Int = 8): UiText? {
    if (cast.isEmpty()) return null
    val shown = cast.take(max).joinToString(", ")
    val others = cast.size - max
    return if (others > 0) UiText.Plural(R.plurals.remote_cast_more, others, listOf(shown, others)) else UiText.Raw(shown)
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
        infoFactsText(info)?.let { Text(it, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        Text(
            info.overview ?: stringResource(R.string.remote_info_no_overview),
            style = MaterialTheme.typography.bodyMedium,
            color = if (info.overview == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
        )
        if (info.genres.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                info.genres.forEach { AssistChip(onClick = {}, label = { Text(it) }, enabled = false) }
            }
        }
        InfoLine(stringResource(R.string.remote_info_directors), info.directors.joinToString(", ").ifEmpty { null })
        InfoLine(stringResource(R.string.remote_info_writers), info.writers.joinToString(", ").ifEmpty { null })
        InfoLine(stringResource(R.string.remote_info_cast), castSummary(info.cast)?.asString())
        InfoLine(stringResource(R.string.remote_info_studios), info.studios.joinToString(", ").ifEmpty { null })
        InfoLine(stringResource(R.string.remote_info_video), info.video)
        InfoLine(stringResource(R.string.remote_audio), info.audio)
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
    val summary = infoFactsText(info) ?: info.overview?.take(80)
    CollapsibleCard(stringResource(R.string.remote_info_title), summary = summary, modifier = modifier.fillMaxWidth()) {
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
                Text(entry.overview ?: stringResource(R.string.remote_info_none), style = MaterialTheme.typography.bodyMedium)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = { onDismiss(); onPlay(entry) }) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = null)
                    Text(stringResource(R.string.remote_play), Modifier.padding(start = 6.dp))
                }
                if (onEnqueue != null) {
                    OutlinedButton(onClick = { onDismiss(); onEnqueue(entry, true) }) {
                        Icon(Icons.Filled.SkipNext, contentDescription = null)
                        Text(stringResource(R.string.remote_info_play_next), Modifier.padding(start = 6.dp))
                    }
                    OutlinedButton(onClick = { onDismiss(); onEnqueue(entry, false) }) {
                        Icon(Icons.AutoMirrored.Filled.PlaylistAdd, contentDescription = null)
                        Text(stringResource(R.string.remote_info_enqueue), Modifier.padding(start = 6.dp))
                    }
                }
            }
        }
    }
}
