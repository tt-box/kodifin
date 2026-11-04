package io.github.jmorille.zapette.ui.remote

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.jmorille.zapette.model.Chapter
import io.github.jmorille.zapette.model.formatHms

/** Index du chapitre contenant [positionMs] (dernier chapitre commencé), ou `null`. */
internal fun chapterAt(chapters: List<Chapter>, positionMs: Long): Int? =
    chapters.indexOfLast { it.startMs <= positionMs }.takeIf { it >= 0 }

/** Libellé d'un chapitre : « Chapitre 3/12 · Nom » (nom omis s'il est absent). */
internal fun chapterLabel(chapters: List<Chapter>, index: Int): String {
    val name = chapters[index].name
    return "Chapitre ${index + 1}/${chapters.size}" + if (name != null) " · $name" else ""
}

/**
 * Barre d'avancement pleine largeur : curseur précis (le temps visé s'affiche pendant le
 * glissement), repères de chapitres sur la piste et bande de chapitres cliquables en dessous
 * (un appui place la lecture au début du chapitre).
 */
@Composable
fun MediaProgress(
    positionMs: Long,
    durationMs: Long,
    chapters: List<Chapter>,
    canSeek: Boolean,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (durationMs <= 0) return
    val slider = remember { RemoteSliderState(tolerance = SEEK_TOLERANCE_MS) }
    val remote = positionMs.toFloat()
    LaunchedEffect(remote) { slider.onRemoteValue(remote) }
    PendingExpiry(slider)
    val duration = durationMs.toFloat()
    val shown = slider.displayed(remote).coerceIn(0f, duration)
    val dragging = slider.dragValue != null
    val marks = chapters.filter { it.startMs in 1 until durationMs }
    val current = chapterAt(chapters, shown.toLong())
    val haptic = LocalHapticFeedback.current

    fun seek(ms: Long) {
        slider.onDrag(ms.toFloat())
        slider.onDragFinished()?.let { onSeek(it.toLong()) }
    }

    Column(modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth()) {
            Slider(
                value = shown,
                onValueChange = slider::onDrag,
                onValueChangeFinished = { slider.onDragFinished()?.let { onSeek(it.toLong()) } },
                valueRange = 0f..duration,
                enabled = canSeek,
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Position de lecture" },
            )
            if (marks.isNotEmpty()) {
                // Repères de début de chapitre, dessinés sur la piste du curseur.
                val color = MaterialTheme.colorScheme.onSurface
                Canvas(Modifier.matchParentSize()) {
                    val y = size.height / 2
                    marks.forEach { c ->
                        val x = size.width * (c.startMs / duration)
                        drawLine(color, Offset(x, y - 9.dp.toPx()), Offset(x, y + 9.dp.toPx()), strokeWidth = 2.dp.toPx())
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                formatHms(shown.toLong()),
                style = if (dragging) MaterialTheme.typography.titleMedium else MaterialTheme.typography.labelMedium,
                fontWeight = if (dragging) FontWeight.Bold else null,
            )
            Text(
                current?.let { chapterLabel(chapters, it) }.orEmpty(),
                style = MaterialTheme.typography.labelMedium,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
            )
            Text(
                "−" + formatHms(durationMs - shown.toLong()),
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.semantics { contentDescription = "Temps restant ${formatHms(durationMs - shown.toLong())}" },
            )
        }
        if (chapters.size > 1) {
            // Bande de chapitres : largeur proportionnelle à la durée, chapitre courant en évidence.
            Row(
                Modifier.fillMaxWidth().height(28.dp).padding(top = 4.dp).clip(RoundedCornerShape(6.dp)),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                chapters.forEachIndexed { i, c ->
                    val end = chapters.getOrNull(i + 1)?.startMs ?: durationMs
                    val weight = ((end - c.startMs).coerceAtLeast(1).toFloat() / duration).coerceAtLeast(0.004f)
                    val selected = i == current
                    Surface(
                        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier
                            .weight(weight)
                            .fillMaxHeight()
                            .clickable(enabled = canSeek, onClickLabel = "Aller au ${chapterLabel(chapters, i)}") {
                                haptic.performHapticFeedback(HapticFeedbackType.VirtualKey)
                                seek(c.startMs)
                            }
                            .semantics { contentDescription = chapterLabel(chapters, i) + " à " + formatHms(c.startMs) },
                    ) {
                        if (weight > 0.06f) {
                            Text(
                                c.name ?: "${i + 1}",
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                textAlign = TextAlign.Center,
                                color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Carte « avancement » pleine largeur (tablettes et téléphone couché) : titre compact sur une
 * ligne puis [MediaProgress].
 */
@Composable
fun ProgressCard(
    title: String?,
    status: String?,
    positionMs: Long,
    durationMs: Long,
    chapters: List<Chapter>,
    canSeek: Boolean,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.secondaryContainer) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    title ?: "Rien en lecture",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (status != null) Text(status, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 12.dp))
            }
            MediaProgress(positionMs, durationMs, chapters, canSeek, onSeek)
        }
    }
}
