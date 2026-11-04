package io.github.jmorille.zapette.ui.remote

import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.foundation.clickable
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.jmorille.zapette.model.MediaDetails
import io.github.jmorille.zapette.model.MediaTrack
import io.github.jmorille.zapette.model.RemoteKey
import io.github.jmorille.zapette.model.RepeatMode
import io.github.jmorille.zapette.model.UiCommand

/** Actions du volet « Lecture en cours » (pistes, vitesse, synchronisation, modes). */
class PlaybackActions(
    val refresh: () -> Unit,
    val setAudioTrack: (Int) -> Unit,
    val nextAudioTrack: () -> Unit,
    val setSubtitle: (Int?) -> Unit,
    val nextSubtitle: () -> Unit,
    val slower: () -> Unit,
    val faster: () -> Unit,
    val subtitleDelay: (Boolean) -> Unit,
    val audioDelay: (Boolean) -> Unit,
    val setRepeat: (RepeatMode) -> Unit,
    val setShuffle: (Boolean) -> Unit,
    val previousChapter: () -> Unit,
    val nextChapter: () -> Unit,
)

/** Contrôles avancés du média en cours ; chaque section n'apparaît que si l'appareil la gère. */
@Composable
fun PlaybackDetails(
    features: RemoteFeatures,
    details: MediaDetails?,
    keys: Set<RemoteKey>,
    actions: PlaybackActions,
    press: (RemoteKey) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (features.tracks) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Pistes et modes", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                IconButton(onClick = actions.refresh) { Icon(Icons.Filled.Refresh, contentDescription = "Actualiser les pistes") }
            }
        }
        val fastKeys = RemoteKey.REWIND in keys || RemoteKey.FAST_FORWARD in keys
        if (features.speed || features.chapters || (features.seekStep && fastKeys)) {
            val speedSummary = when (val speed = details?.speed ?: 1) {
                0 -> "En pause"
                1 -> "Vitesse normale"
                else -> "Vitesse ×$speed"
            }
            Section("Vitesse et chapitres", summary = speedSummary) {
                if (features.speed) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextAction("Plus lent", actions.slower)
                        val speedLabel = when (val speed = details?.speed ?: 1) {
                            0 -> "En pause"
                            1 -> "Normale"
                            else -> "×$speed"
                        }
                        Text(
                            speedLabel,
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.semantics { contentDescription = "Vitesse : $speedLabel" },
                        )
                        TextAction("Plus vite", actions.faster)
                    }
                } else if (features.seekStep && fastKeys) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextAction("Retour rapide", { press(RemoteKey.REWIND) }, enabled = RemoteKey.REWIND in keys)
                        TextAction("Avance rapide", { press(RemoteKey.FAST_FORWARD) }, enabled = RemoteKey.FAST_FORWARD in keys)
                    }
                }
                if (features.chapters) {
                    val count = details?.chapterCount ?: 0
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        RemoteIconButton(Icons.Filled.SkipPrevious, "Chapitre précédent", actions.previousChapter, size = 48.dp)
                        Text(if (count > 0) "Chapitres ($count)" else "Chapitres", style = MaterialTheme.typography.bodyMedium)
                        RemoteIconButton(Icons.Filled.SkipNext, "Chapitre suivant", actions.nextChapter, size = 48.dp)
                    }
                }
            }
        }
        if (features.tracks) {
            val audio = details?.audioTracks?.firstOrNull { it.id == details.currentAudio }?.label
            Section("Audio", summary = audio ?: "—") {
                TrackChips(details?.audioTracks.orEmpty(), details?.currentAudio, offLabel = null, onSelect = { it?.let(actions.setAudioTrack) })
                TextAction("Piste audio suivante", actions.nextAudioTrack)
            }
            val subtitle = details?.currentSubtitle?.let { id -> details.subtitles.firstOrNull { it.id == id }?.label } ?: "Désactivés"
            Section("Sous-titres", summary = subtitle) {
                TrackChips(details?.subtitles.orEmpty(), details?.currentSubtitle, offLabel = "Désactivés", onSelect = actions.setSubtitle)
                TextAction("Sous-titres suivants", actions.nextSubtitle)
            }
        }
        if (features.syncOffset) {
            Section("Synchronisation", summary = "Décalage sous-titres et audio") {
                SyncRow("Sous-titres", actions.subtitleDelay)
                SyncRow("Audio", actions.audioDelay)
            }
        }
        if (features.playMode) {
            val modeSummary = listOfNotNull(
                when (details?.repeat) {
                    RepeatMode.ONE -> "Répéter le titre"
                    RepeatMode.ALL -> "Répéter tout"
                    else -> "Sans répétition"
                },
                "aléatoire".takeIf { details?.shuffle == true },
            ).joinToString(" · ")
            Section("Modes de lecture", summary = modeSummary) {
                RepeatSelector(details?.repeat, actions.setRepeat)
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Lecture aléatoire", modifier = Modifier.weight(1f))
                    val shuffle = details?.shuffle == true
                    Switch(
                        checked = shuffle,
                        onCheckedChange = actions.setShuffle,
                        modifier = Modifier.semantics { contentDescription = "Lecture aléatoire" },
                    )
                }
            }
        }
    }
}

/**
 * Section repliable, repliée par défaut pour garder l'écran épuré : seul l'en-tête (titre et
 * résumé de l'état courant, ex. piste choisie) reste visible. L'état ouvert/fermé survit à la
 * rotation.
 */
@Composable
private fun Section(title: String, summary: String? = null, content: @Composable () -> Unit) {
    CollapsibleCard(title, summary, Modifier.fillMaxWidth(), content = content)
}

@Composable
fun CollapsibleCard(
    title: String,
    summary: String? = null,
    modifier: Modifier = Modifier,
    initiallyExpanded: Boolean = false,
    content: @Composable () -> Unit,
) {
    var expanded by rememberSaveable(title) { mutableStateOf(initiallyExpanded) }
    OutlinedCard(modifier) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .clickable(onClickLabel = if (expanded) "Masquer $title" else "Afficher $title") { expanded = !expanded }
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                if (!expanded && summary != null) {
                    Text(
                        summary,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Icon(
                if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                contentDescription = null,
            )
        }
        AnimatedVisibility(expanded) {
            Column(Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                content()
            }
        }
    }
}

@Composable
private fun TextAction(label: String, onClick: () -> Unit, enabled: Boolean = true) {
    val haptic = LocalHapticFeedback.current
    OutlinedButton(
        onClick = {
            haptic.performHapticFeedback(HapticFeedbackType.VirtualKey)
            onClick()
        },
        enabled = enabled,
        modifier = Modifier.heightIn(min = 48.dp),
    ) { Text(label, maxLines = 1, softWrap = false) }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TrackChips(tracks: List<MediaTrack>, current: Int?, offLabel: String?, onSelect: (Int?) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (offLabel != null) {
            FilterChip(selected = current == null, onClick = { onSelect(null) }, label = { Text(offLabel) }, modifier = Modifier.heightIn(min = 48.dp))
        }
        tracks.forEach { track ->
            val label = listOfNotNull(track.label.takeIf { it.isNotBlank() }, track.language?.takeIf { it.isNotBlank() })
                .distinct().joinToString(" · ").ifEmpty { "Piste ${track.id}" }
            FilterChip(
                selected = current == track.id,
                onClick = { onSelect(track.id) },
                label = { Text(label) },
                modifier = Modifier.heightIn(min = 48.dp),
            )
        }
        if (tracks.isEmpty() && offLabel == null) {
            Text("Aucune piste annoncée", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 12.dp))
        }
    }
}

@Composable
private fun SyncRow(label: String, shift: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, modifier = Modifier.weight(1f))
        TextAction("Plus tôt", { shift(false) })
        TextAction("Plus tard", { shift(true) })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RepeatSelector(current: RepeatMode?, onSelect: (RepeatMode) -> Unit) {
    val options = listOf(RepeatMode.OFF to "Sans répétition", RepeatMode.ONE to "Répéter un", RepeatMode.ALL to "Tout répéter")
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        options.forEachIndexed { index, (mode, label) ->
            SegmentedButton(
                selected = current == mode,
                onClick = { onSelect(mode) },
                shape = SegmentedButtonDefaults.itemShape(index, options.size),
                label = { Text(label, maxLines = 1) },
            )
        }
    }
}

/** Commandes d'interface secondaires de l'appareil (plein écran, guide, chaînes…). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun UiCommandChips(commands: Set<UiCommand>, onCommand: (UiCommand) -> Unit, modifier: Modifier = Modifier) {
    if (commands.isEmpty()) return
    val haptic = LocalHapticFeedback.current
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        UiCommand.entries.filter { it in commands }.forEach { command ->
            AssistChip(
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.VirtualKey)
                    onCommand(command)
                },
                label = { Text(command.label) },
                modifier = Modifier.heightIn(min = 48.dp),
            )
        }
    }
}
