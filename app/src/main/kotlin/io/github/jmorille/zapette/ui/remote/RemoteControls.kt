package io.github.jmorille.zapette.ui.remote

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.VolumeDown
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.Forward30
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay30
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.compose.ui.unit.sp
import io.github.jmorille.zapette.model.Capability
import io.github.jmorille.zapette.model.MediaDetails
import io.github.jmorille.zapette.model.PlaybackState
import io.github.jmorille.zapette.model.RemoteKey
import io.github.jmorille.zapette.model.UiCommand
import io.github.jmorille.zapette.model.formatHms
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/** Actions de l'écran, regroupées pour ne pas propager le ViewModel dans chaque composable. */
class RemoteActions(
    val press: (RemoteKey) -> Unit,
    val uiCommand: (UiCommand) -> Unit,
    val seekTo: (Long) -> Unit,
    val seekBy: (Long) -> Unit,
    val setVolume: (Int) -> Unit,
)

/** Saut relatif des touches −30 s / +30 s. */
const val SEEK_STEP_MS = 30_000L

/** Bouton d'action avec retour haptique, cible tactile ≥ 48 dp. */
@Composable
fun RemoteIconButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    size: Dp = 56.dp,
    emphasized: Boolean = false,
) {
    val haptic = LocalHapticFeedback.current
    val click = {
        haptic.performHapticFeedback(HapticFeedbackType.VirtualKey)
        onClick()
    }
    val buttonModifier = modifier.size(size.coerceAtLeast(48.dp))
    if (emphasized) {
        FilledIconButton(onClick = click, enabled = enabled, modifier = buttonModifier) {
            Icon(icon, contentDescription = label, modifier = Modifier.size(size * 0.5f))
        }
    } else {
        FilledTonalIconButton(onClick = click, enabled = enabled, modifier = buttonModifier) {
            Icon(icon, contentDescription = label)
        }
    }
}

/** Touche logique : désactivée si l'appareil ne la prend pas en charge. */
@Composable
fun KeyButton(
    icon: ImageVector,
    label: String,
    key: RemoteKey,
    keys: Set<RemoteKey>,
    press: (RemoteKey) -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 56.dp,
    emphasized: Boolean = false,
) {
    RemoteIconButton(icon, label, { press(key) }, modifier, enabled = key in keys, size = size, emphasized = emphasized)
}

// --- Bandeau « en cours de lecture » ---------------------------------------------------------

@Composable
fun NowPlayingBanner(
    playback: PlaybackState,
    canSeek: Boolean,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
    details: MediaDetails? = null,
    subtitle: String? = null,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text(
                playback.title ?: "Rien en lecture",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            val status = buildList {
                add(if (playback.isPlaying) "En lecture" else if (playback.title != null) "En pause" else null)
                details?.speed?.takeIf { it != 1 && it != 0 }?.let { add("Vitesse ×$it") }
                subtitle?.let(::add)
            }.filterNotNull().joinToString(" · ")
            if (status.isNotEmpty()) {
                Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
            }
            if (playback.durationMs > 0) {
                val slider = remember { RemoteSliderState(tolerance = SEEK_TOLERANCE_MS) }
                val remote = playback.positionMs.toFloat()
                LaunchedEffect(remote) { slider.onRemoteValue(remote) }
                PendingExpiry(slider)
                val duration = playback.durationMs.toFloat()
                val shown = slider.displayed(remote).coerceIn(0f, duration)
                Slider(
                    value = shown,
                    onValueChange = slider::onDrag,
                    onValueChangeFinished = { slider.onDragFinished()?.let { onSeek(it.toLong()) } },
                    valueRange = 0f..duration,
                    enabled = canSeek,
                    modifier = Modifier.semantics { contentDescription = "Position de lecture" },
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(formatHms(shown.toLong()), style = MaterialTheme.typography.labelMedium)
                    Text(
                        "−" + formatHms(playback.durationMs - shown.toLong()),
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.semantics { contentDescription = "Temps restant ${formatHms(playback.durationMs - shown.toLong())}" },
                    )
                }
            }
        }
    }
}

// --- Pavé directionnel ------------------------------------------------------------------------

/**
 * Pavé directionnel circulaire (OK au centre) entouré des touches Retour / Accueil en haut
 * et Menu contextuel / OSD / Info en bas. Sa taille s'adapte à la place disponible.
 */
@Composable
fun NavigationCluster(
    keys: Set<RemoteKey>,
    uiCommands: Set<UiCommand>,
    actions: RemoteActions,
    modifier: Modifier = Modifier,
    maxDiameter: Dp = 380.dp,
) {
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        val sideRow = 56.dp
        val gap = 8.dp
        val diameter = min(min(maxWidth, maxHeight - (sideRow + gap) * 2), maxDiameter).coerceAtLeast(168.dp)
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(gap)) {
            Row(Modifier.width(diameter), horizontalArrangement = Arrangement.SpaceBetween) {
                KeyButton(Icons.AutoMirrored.Filled.ArrowBack, "Retour", RemoteKey.BACK, keys, actions.press)
                KeyButton(Icons.Filled.Home, "Accueil", RemoteKey.HOME, keys, actions.press)
            }
            DPad(diameter, keys, actions.press)
            Row(Modifier.width(diameter), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                // Menu contextuel : touche MENU, sinon la commande d'interface équivalente.
                if (RemoteKey.MENU !in keys && UiCommand.CONTEXT_MENU in uiCommands) {
                    RemoteIconButton(Icons.Filled.Menu, "Menu contextuel", { actions.uiCommand(UiCommand.CONTEXT_MENU) })
                } else {
                    KeyButton(Icons.Filled.Menu, "Menu contextuel", RemoteKey.MENU, keys, actions.press)
                }
                if (UiCommand.OSD in uiCommands) {
                    RemoteIconButton(Icons.Filled.Tune, "Menu de lecture (OSD)", { actions.uiCommand(UiCommand.OSD) })
                }
                KeyButton(Icons.Filled.Info, "Informations", RemoteKey.INFO, keys, actions.press)
            }
        }
    }
}

@Composable
private fun DPad(diameter: Dp, keys: Set<RemoteKey>, press: (RemoteKey) -> Unit) {
    val arrow = (diameter * 0.28f).coerceAtLeast(48.dp)
    val inset = diameter * 0.03f
    Box(
        Modifier.size(diameter).background(MaterialTheme.colorScheme.surfaceContainerHighest, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        DPadArrow(Icons.Filled.KeyboardArrowUp, "Haut", RemoteKey.UP, keys, press, arrow, Modifier.align(Alignment.TopCenter).padding(top = inset))
        DPadArrow(Icons.Filled.KeyboardArrowDown, "Bas", RemoteKey.DOWN, keys, press, arrow, Modifier.align(Alignment.BottomCenter).padding(bottom = inset))
        DPadArrow(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Gauche", RemoteKey.LEFT, keys, press, arrow, Modifier.align(Alignment.CenterStart).padding(start = inset))
        DPadArrow(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Droite", RemoteKey.RIGHT, keys, press, arrow, Modifier.align(Alignment.CenterEnd).padding(end = inset))
        val haptic = LocalHapticFeedback.current
        Surface(
            onClick = {
                haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                press(RemoteKey.SELECT)
            },
            enabled = RemoteKey.SELECT in keys,
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            shadowElevation = 2.dp,
            modifier = Modifier.size((diameter * 0.36f).coerceAtLeast(64.dp)).semantics {
                contentDescription = "OK (valider)"
                role = Role.Button
            },
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text("OK", fontSize = (diameter.value * 0.075f).coerceIn(16f, 30f).sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun DPadArrow(
    icon: ImageVector,
    label: String,
    key: RemoteKey,
    keys: Set<RemoteKey>,
    press: (RemoteKey) -> Unit,
    size: Dp,
    modifier: Modifier,
) {
    val haptic = LocalHapticFeedback.current
    Surface(
        onClick = {
            haptic.performHapticFeedback(HapticFeedbackType.VirtualKey)
            press(key)
        },
        enabled = key in keys,
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.size(size),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = label, modifier = Modifier.size(size * 0.6f))
        }
    }
}

// --- Transport et volume ----------------------------------------------------------------------

/**
 * Précédent, −30 s, lecture/pause, +30 s, suivant, stop. Sans saut relatif, −30/+30 cèdent la
 * place au retour / à l'avance rapides de l'appareil.
 */
@Composable
fun TransportRow(
    keys: Set<RemoteKey>,
    isPlaying: Boolean,
    seekStep: Boolean,
    actions: RemoteActions,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        KeyButton(Icons.Filled.SkipPrevious, "Précédent", RemoteKey.PREVIOUS, keys, actions.press, size = 48.dp)
        if (seekStep) {
            RemoteIconButton(Icons.Filled.Replay30, "Reculer de 30 secondes", { actions.seekBy(-SEEK_STEP_MS) }, size = 48.dp)
        } else {
            KeyButton(Icons.Filled.FastRewind, "Retour rapide", RemoteKey.REWIND, keys, actions.press, size = 48.dp)
        }
        KeyButton(
            if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
            if (isPlaying) "Pause" else "Lecture",
            RemoteKey.PLAY_PAUSE, keys, actions.press, size = 64.dp, emphasized = true,
        )
        if (seekStep) {
            RemoteIconButton(Icons.Filled.Forward30, "Avancer de 30 secondes", { actions.seekBy(SEEK_STEP_MS) }, size = 48.dp)
        } else {
            KeyButton(Icons.Filled.FastForward, "Avance rapide", RemoteKey.FAST_FORWARD, keys, actions.press, size = 48.dp)
        }
        KeyButton(Icons.Filled.SkipNext, "Suivant", RemoteKey.NEXT, keys, actions.press, size = 48.dp)
        KeyButton(Icons.Filled.Stop, "Stop", RemoteKey.STOP, keys, actions.press, size = 48.dp)
    }
}

/** Muet, volume −, curseur (si l'appareil publie son volume), volume +. */
@Composable
fun VolumeRow(
    keys: Set<RemoteKey>,
    capabilities: Set<Capability>,
    volumePercent: Int?,
    actions: RemoteActions,
    modifier: Modifier = Modifier,
) {
    val slider = Capability.VOLUME in capabilities && volumePercent != null
    val anyKey = keys.any { it == RemoteKey.VOLUME_UP || it == RemoteKey.VOLUME_DOWN || it == RemoteKey.MUTE }
    if (!slider && !anyKey) return
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (RemoteKey.MUTE in keys) KeyButton(Icons.AutoMirrored.Filled.VolumeOff, "Couper le son", RemoteKey.MUTE, keys, actions.press, size = 48.dp)
        if (slider) {
            if (RemoteKey.VOLUME_DOWN in keys) KeyButton(Icons.AutoMirrored.Filled.VolumeDown, "Baisser le volume", RemoteKey.VOLUME_DOWN, keys, actions.press, size = 48.dp)
            VolumeSlider(volumePercent, actions.setVolume, Modifier.weight(1f))
            if (RemoteKey.VOLUME_UP in keys) KeyButton(Icons.AutoMirrored.Filled.VolumeUp, "Monter le volume", RemoteKey.VOLUME_UP, keys, actions.press, size = 48.dp)
        } else {
            Spacer(Modifier.weight(1f))
            KeyButton(Icons.AutoMirrored.Filled.VolumeDown, "Baisser le volume", RemoteKey.VOLUME_DOWN, keys, actions.press)
            KeyButton(Icons.AutoMirrored.Filled.VolumeUp, "Monter le volume", RemoteKey.VOLUME_UP, keys, actions.press)
            Spacer(Modifier.weight(1f))
        }
    }
}

@Composable
private fun VolumeSlider(volume: Int, onChange: (Int) -> Unit, modifier: Modifier = Modifier) {
    // Pas de remember(volume) : une valeur reçue pendant le glissement réinitialisait le curseur.
    val slider = remember { RemoteSliderState(tolerance = 1f) }
    val remote = volume.toFloat()
    LaunchedEffect(remote) { slider.onRemoteValue(remote) }
    PendingExpiry(slider)
    val shown = slider.displayed(remote).coerceIn(0f, 100f)
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Slider(
            value = shown,
            onValueChange = slider::onDrag,
            onValueChangeFinished = { slider.onDragFinished()?.let { onChange(it.roundToInt()) } },
            valueRange = 0f..100f,
            modifier = Modifier.weight(1f).semantics { contentDescription = "Volume ${shown.roundToInt()} %" },
        )
        Text("${shown.roundToInt()} %", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(start = 8.dp).width(40.dp))
    }
}

/** Abandonne la valeur en attente si l'appareil ne la confirme pas à temps. */
@Composable
private fun PendingExpiry(slider: RemoteSliderState) {
    val pending = slider.pending
    LaunchedEffect(pending) {
        if (pending != null) {
            delay(PENDING_TIMEOUT_MS)
            slider.expirePending()
        }
    }
}

private const val SEEK_TOLERANCE_MS = 3_000f
private const val PENDING_TIMEOUT_MS = 4_000L
