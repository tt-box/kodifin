package io.github.ttbox.kodifin.ui.remote

import io.github.ttbox.kodifin.model.MediaItemInfo
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
import io.github.ttbox.kodifin.model.MediaDetails
import io.github.ttbox.kodifin.model.MediaTrack
import io.github.ttbox.kodifin.model.RemoteKey
import io.github.ttbox.kodifin.model.RepeatMode
import io.github.ttbox.kodifin.model.UiCommand
import androidx.compose.ui.res.stringResource
import io.github.ttbox.kodifin.R
import io.github.ttbox.kodifin.text.UiText
import io.github.ttbox.kodifin.ui.text.asString
import io.github.ttbox.kodifin.text.text
import java.text.NumberFormat
import java.util.Locale

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

/**
 * Libellé de vitesse : « En pause », « Normale », « ×2 » (Kodi, vitesses entières) ou « ×1,25 »
 * (Chromecast, [MediaDetails.playbackRate]) ; décimales selon [locale].
 */
fun speedText(details: MediaDetails?, locale: Locale = Locale.getDefault()): UiText {
    val speed = details?.speed ?: 1
    if (speed == 0) return UiText.Res(R.string.remote_speed_paused)
    val rate = details?.playbackRate
        ?: return if (speed == 1) UiText.Res(R.string.remote_speed_normal) else UiText.Res(R.string.remote_speed_factor, speed.toString())
    if (kotlin.math.abs(rate - 1.0) < 0.01) return UiText.Res(R.string.remote_speed_normal)
    val text = NumberFormat.getNumberInstance(locale).apply { maximumFractionDigits = 2 }.format(rate)
    return UiText.Res(R.string.remote_speed_factor, text)
}

/** Contrôles avancés du média en cours ; chaque section n'apparaît que si l'appareil la gère. */
@Composable
fun PlaybackDetails(
    features: RemoteFeatures,
    details: MediaDetails?,
    keys: Set<RemoteKey>,
    actions: PlaybackActions,
    press: (RemoteKey) -> Unit,
    modifier: Modifier = Modifier,
    /** Fiche du média en cours (résumé de l'épisode, distribution), en tête du volet. */
    info: MediaItemInfo? = null,
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        info?.let { NowPlayingInfoCard(it) }
        if (features.tracks) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.remote_tracks_title), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                val refreshLabel = stringResource(R.string.remote_tracks_refresh)
                WithTooltip(refreshLabel) { IconButton(onClick = actions.refresh) { Icon(Icons.Filled.Refresh, contentDescription = refreshLabel) } }
            }
        }
        if (features.tracks) {
            val audio = details?.audioTracks?.firstOrNull { it.id == details.currentAudio }?.label
            Section(stringResource(R.string.remote_audio), summary = audio ?: "—") {
                TrackChips(details?.audioTracks.orEmpty(), details?.currentAudio, offLabel = null, onSelect = { it?.let(actions.setAudioTrack) })
                TextAction(stringResource(R.string.remote_audio_next), actions.nextAudioTrack)
            }
            val subtitlesOff = stringResource(R.string.remote_subtitles_off)
            val subtitle = details?.currentSubtitle?.let { id -> details.subtitles.firstOrNull { it.id == id }?.label } ?: subtitlesOff
            Section(stringResource(R.string.remote_subtitles), summary = subtitle) {
                TrackChips(details?.subtitles.orEmpty(), details?.currentSubtitle, offLabel = subtitlesOff, onSelect = actions.setSubtitle)
                TextAction(stringResource(R.string.remote_subtitles_next), actions.nextSubtitle)
            }
        }
        if (features.syncOffset) {
            Section(stringResource(R.string.remote_sync), summary = stringResource(R.string.remote_sync_summary)) {
                SyncRow(stringResource(R.string.remote_subtitles), actions.subtitleDelay)
                SyncRow(stringResource(R.string.remote_audio), actions.audioDelay)
            }
        }
        if (features.playMode) {
            val modeSummary = listOfNotNull(
                when (details?.repeat) {
                    RepeatMode.ONE -> stringResource(R.string.remote_repeat_one_summary)
                    RepeatMode.ALL -> stringResource(R.string.remote_repeat_all_summary)
                    else -> stringResource(R.string.remote_repeat_off)
                },
                stringResource(R.string.remote_shuffle_summary).takeIf { details?.shuffle == true },
            ).joinToString(" · ")
            Section(stringResource(R.string.remote_play_modes), summary = modeSummary) {
                RepeatSelector(details?.repeat, actions.setRepeat)
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                    val shuffleLabel = stringResource(R.string.remote_shuffle)
                    Text(shuffleLabel, modifier = Modifier.weight(1f))
                    val shuffle = details?.shuffle == true
                    Switch(
                        checked = shuffle,
                        onCheckedChange = actions.setShuffle,
                        modifier = Modifier.semantics { contentDescription = shuffleLabel },
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
    val toggleLabel = stringResource(if (expanded) R.string.remote_collapse_hide else R.string.remote_collapse_show, title)
    OutlinedCard(modifier) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .clickable(onClickLabel = toggleLabel) { expanded = !expanded }
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
                .distinct().joinToString(" · ").ifEmpty { stringResource(R.string.remote_track_number, track.id) }
            FilterChip(
                selected = current == track.id,
                onClick = { onSelect(track.id) },
                label = { Text(label) },
                modifier = Modifier.heightIn(min = 48.dp),
            )
        }
        if (tracks.isEmpty() && offLabel == null) {
            Text(stringResource(R.string.remote_tracks_none), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 12.dp))
        }
    }
}

@Composable
private fun SyncRow(label: String, shift: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, modifier = Modifier.weight(1f))
        TextAction(stringResource(R.string.remote_sync_earlier), { shift(false) })
        TextAction(stringResource(R.string.remote_sync_later), { shift(true) })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RepeatSelector(current: RepeatMode?, onSelect: (RepeatMode) -> Unit) {
    val options = listOf(
        RepeatMode.OFF to stringResource(R.string.remote_repeat_off),
        RepeatMode.ONE to stringResource(R.string.remote_repeat_one),
        RepeatMode.ALL to stringResource(R.string.remote_repeat_all),
    )
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
fun UiCommandChips(
    commands: Set<UiCommand>,
    onCommand: (UiCommand) -> Unit,
    modifier: Modifier = Modifier,
    /** État du plein écran, si le lecteur sait le lire (`null` : inconnu). */
    fullscreen: Boolean? = null,
    /** Plein écran explicite ; `null` : simple bascule [UiCommand.FULLSCREEN]. */
    onFullscreen: ((Boolean) -> Unit)? = null,
) {
    if (commands.isEmpty()) return
    val haptic = LocalHapticFeedback.current
    val explicitFullscreen = onFullscreen != null && UiCommand.FULLSCREEN in commands
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (explicitFullscreen) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    fullscreenLabel(fullscreen).asString(),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                listOf(true to stringResource(R.string.remote_on), false to stringResource(R.string.remote_off)).forEach { (value, label) ->
                    FilterChip(
                        selected = fullscreen == value,
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.VirtualKey)
                            onFullscreen?.invoke(value)
                        },
                        label = { Text(label) },
                        modifier = Modifier.heightIn(min = 48.dp),
                    )
                }
            }
        }
        UiCommand.entries.filter { it in commands && !(explicitFullscreen && it == UiCommand.FULLSCREEN) }.forEach { command ->
            AssistChip(
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.VirtualKey)
                    onCommand(command)
                },
                label = { Text(command.text.asString()) },
                modifier = Modifier.heightIn(min = 48.dp),
            )
        }
    }
}

/** Libellé du plein écran : état connu ou non. */
fun fullscreenLabel(fullscreen: Boolean?): UiText = when (fullscreen) {
    true -> UiText.Res(R.string.remote_fullscreen_on)
    false -> UiText.Res(R.string.remote_fullscreen_off)
    null -> UiText.Res(R.string.remote_fullscreen_unknown)
}
