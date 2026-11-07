package io.github.ttbox.kodifin.ui.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.ttbox.kodifin.R
import io.github.ttbox.kodifin.cast.CastMaxBitrate
import io.github.ttbox.kodifin.cast.SubtitleBackground
import io.github.ttbox.kodifin.cast.SubtitleColor
import io.github.ttbox.kodifin.cast.SubtitleSize
import io.github.ttbox.kodifin.data.AppSettings
import io.github.ttbox.kodifin.data.SettingsStore
import io.github.ttbox.kodifin.text.UiText
import io.github.ttbox.kodifin.ui.text.asString

/** Largeur maximale du contenu : sur tablette, la colonne reste lisible et centrée. */
private val MAX_CONTENT_WIDTH = 640.dp

@Composable
fun SettingsScreen(store: SettingsStore, onBack: () -> Unit) {
    val vm: SettingsViewModel = viewModel(factory = viewModelFactory { initializer { SettingsViewModel(store) } })
    val settings by vm.settings.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    SettingsContent(
        settings = settings,
        error = error,
        actions = SettingsActions(
            keepScreenOn = vm::setKeepScreenOn,
            maxBitrate = vm::setMaxBitrate,
            subtitleSize = vm::setSubtitleSize,
            subtitleColor = vm::setSubtitleColor,
            subtitleBackground = vm::setSubtitleBackground,
            castAutoDisconnect = vm::setCastAutoDisconnect,
        ),
        onBack = onBack,
    )
}

/** Rappels de l'écran (regroupés pour garder une signature lisible). */
class SettingsActions(
    val keepScreenOn: (Boolean) -> Unit,
    val maxBitrate: (CastMaxBitrate) -> Unit,
    val subtitleSize: (SubtitleSize) -> Unit,
    val subtitleColor: (SubtitleColor) -> Unit,
    val subtitleBackground: (SubtitleBackground) -> Unit,
    val castAutoDisconnect: (Boolean) -> Unit,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsContent(settings: AppSettings?, error: UiText?, actions: SettingsActions, onBack: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.settings_back)) }
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            if (settings == null) {
                CircularProgressIndicator(Modifier.padding(32.dp))
                return@Box
            }
            Column(
                Modifier
                    .widthIn(max = MAX_CONTENT_WIDTH)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                error?.let { Text(it.asString(), color = MaterialTheme.colorScheme.error) }

                SectionTitle(stringResource(R.string.settings_section_remote))
                SwitchRow(
                    title = stringResource(R.string.settings_keep_screen_on_title),
                    subtitle = stringResource(R.string.settings_keep_screen_on_subtitle),
                    checked = settings.keepScreenOn,
                    onChange = actions.keepScreenOn,
                )

                HorizontalDivider()
                SectionTitle("Chromecast")
                ChoiceGroup(
                    title = stringResource(R.string.settings_max_bitrate_title),
                    note = stringResource(R.string.settings_max_bitrate_note),
                    values = CastMaxBitrate.entries,
                    selected = settings.cast.maxBitrate,
                    label = { it.labelRes },
                    onSelect = actions.maxBitrate,
                )
                ChoiceGroup(
                    title = stringResource(R.string.settings_subtitle_size_title),
                    values = SubtitleSize.entries,
                    selected = settings.cast.subtitles.size,
                    label = { it.labelRes },
                    onSelect = actions.subtitleSize,
                )
                ChoiceGroup(
                    title = stringResource(R.string.settings_subtitle_color_title),
                    values = SubtitleColor.entries,
                    selected = settings.cast.subtitles.textColor,
                    label = { it.labelRes },
                    swatch = { Color(it.argb) },
                    onSelect = actions.subtitleColor,
                )
                ChoiceGroup(
                    title = stringResource(R.string.settings_subtitle_background_title),
                    values = SubtitleBackground.entries,
                    selected = settings.cast.subtitles.background,
                    label = { it.labelRes },
                    swatch = { Color(it.argb) },
                    onSelect = actions.subtitleBackground,
                )
                SwitchRow(
                    title = stringResource(R.string.settings_auto_disconnect_title),
                    subtitle = stringResource(R.string.settings_auto_disconnect_subtitle),
                    checked = settings.cast.autoDisconnect,
                    onChange = actions.castAutoDisconnect,
                )
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
}

@Composable
private fun SwitchRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .toggleable(value = checked, role = Role.Switch, onValueChange = onChange)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 16.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        // La ligne entière porte l'interaction (accessibilité) : le Switch n'est qu'un indicateur.
        Switch(checked = checked, onCheckedChange = null)
    }
}

@Composable
private fun <T> ChoiceGroup(
    title: String,
    values: List<T>,
    selected: T,
    label: (T) -> Int,
    onSelect: (T) -> Unit,
    note: String? = null,
    swatch: ((T) -> Color)? = null,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        note?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            values.forEach { value ->
                FilterChip(
                    selected = value == selected,
                    onClick = { onSelect(value) },
                    label = { Text(stringResource(label(value))) },
                    leadingIcon = swatch?.let { color ->
                        {
                            Box(
                                Modifier
                                    .size(16.dp)
                                    .background(color(value), CircleShape)
                                    .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape),
                            )
                        }
                    },
                )
            }
        }
    }
}

/*
 * Libellés traduisibles des énumérations de feature/cast (le module garde son `label` français
 * pour ses journaux) ; le `when` exhaustif impose un libellé à toute nouvelle valeur.
 */

@get:StringRes
private val CastMaxBitrate.labelRes: Int
    get() = when (this) {
        CastMaxBitrate.AUTO -> R.string.settings_bitrate_auto
        CastMaxBitrate.P1080 -> R.string.settings_bitrate_1080p
        CastMaxBitrate.P720 -> R.string.settings_bitrate_720p
        CastMaxBitrate.P480 -> R.string.settings_bitrate_480p
    }

@get:StringRes
private val SubtitleSize.labelRes: Int
    get() = when (this) {
        SubtitleSize.SMALL -> R.string.settings_subtitle_size_small
        SubtitleSize.NORMAL -> R.string.settings_subtitle_size_normal
        SubtitleSize.LARGE -> R.string.settings_subtitle_size_large
        SubtitleSize.HUGE -> R.string.settings_subtitle_size_huge
    }

@get:StringRes
private val SubtitleColor.labelRes: Int
    get() = when (this) {
        SubtitleColor.WHITE -> R.string.settings_subtitle_color_white
        SubtitleColor.YELLOW -> R.string.settings_subtitle_color_yellow
        SubtitleColor.CYAN -> R.string.settings_subtitle_color_cyan
        SubtitleColor.GREEN -> R.string.settings_subtitle_color_green
    }

@get:StringRes
private val SubtitleBackground.labelRes: Int
    get() = when (this) {
        SubtitleBackground.NONE -> R.string.settings_subtitle_background_none
        SubtitleBackground.TRANSLUCENT -> R.string.settings_subtitle_background_translucent
        SubtitleBackground.OPAQUE -> R.string.settings_subtitle_background_opaque
    }

/**
 * Garde l'écran allumé tant que ce composable est affiché et [enabled] vrai (à placer dans
 * l'écran de télécommande).
 */
@Composable
fun KeepScreenOn(enabled: Boolean) {
    val view = LocalView.current
    DisposableEffect(view, enabled) {
        if (enabled) view.keepScreenOn = true
        onDispose { if (enabled) view.keepScreenOn = false }
    }
}
