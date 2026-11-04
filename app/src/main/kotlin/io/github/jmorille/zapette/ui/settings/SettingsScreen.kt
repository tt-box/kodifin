package io.github.jmorille.zapette.ui.settings

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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.jmorille.zapette.cast.CastMaxBitrate
import io.github.jmorille.zapette.cast.SubtitleBackground
import io.github.jmorille.zapette.cast.SubtitleColor
import io.github.jmorille.zapette.cast.SubtitleSize
import io.github.jmorille.zapette.data.AppSettings
import io.github.jmorille.zapette.data.SettingsStore

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
private fun SettingsContent(settings: AppSettings?, error: String?, actions: SettingsActions, onBack: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Réglages") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour") }
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
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }

                SectionTitle("Télécommande")
                SwitchRow(
                    title = "Garder l'écran allumé",
                    subtitle = "L'écran ne se met pas en veille tant que la télécommande est affichée.",
                    checked = settings.keepScreenOn,
                    onChange = actions.keepScreenOn,
                )

                HorizontalDivider()
                SectionTitle("Chromecast")
                ChoiceGroup(
                    title = "Débit vidéo maximal",
                    note = "Le récepteur Cast par défaut adapte lui-même le débit : la limite n'est appliquée " +
                        "que par les récepteurs qui la prennent en charge.",
                    values = CastMaxBitrate.entries,
                    selected = settings.cast.maxBitrate,
                    label = { it.label },
                    onSelect = actions.maxBitrate,
                )
                ChoiceGroup(
                    title = "Taille des sous-titres",
                    values = SubtitleSize.entries,
                    selected = settings.cast.subtitles.size,
                    label = { it.label },
                    onSelect = actions.subtitleSize,
                )
                ChoiceGroup(
                    title = "Couleur du texte",
                    values = SubtitleColor.entries,
                    selected = settings.cast.subtitles.textColor,
                    label = { it.label },
                    swatch = { Color(it.argb) },
                    onSelect = actions.subtitleColor,
                )
                ChoiceGroup(
                    title = "Fond des sous-titres",
                    values = SubtitleBackground.entries,
                    selected = settings.cast.subtitles.background,
                    label = { it.label },
                    swatch = { Color(it.argb) },
                    onSelect = actions.subtitleBackground,
                )
                SwitchRow(
                    title = "Déconnexion automatique",
                    subtitle = "Fermer la session Cast en quittant la télécommande (sinon la lecture continue).",
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
    label: (T) -> String,
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
                    label = { Text(label(value)) },
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
