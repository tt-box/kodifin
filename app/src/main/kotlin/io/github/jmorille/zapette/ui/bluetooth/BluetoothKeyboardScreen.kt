package io.github.jmorille.zapette.ui.bluetooth

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.jmorille.zapette.bluetooth.AndroidHidKeyboard
import io.github.jmorille.zapette.bluetooth.HidAvailability
import io.github.jmorille.zapette.bluetooth.HidKey
import io.github.jmorille.zapette.bluetooth.HidReports
import io.github.jmorille.zapette.bluetooth.KeyboardLayout
import io.github.jmorille.zapette.model.RemoteKey

/** Touches proposées à l'essai, par groupe : libellé et touche HID. */
private val MEDIA_KEYS = listOf(
    "Lecture / pause" to RemoteKey.PLAY_PAUSE, "Stop" to RemoteKey.STOP,
    "Précédent" to RemoteKey.PREVIOUS, "Suivant" to RemoteKey.NEXT,
    "Retour rapide" to RemoteKey.REWIND, "Avance rapide" to RemoteKey.FAST_FORWARD,
    "Volume −" to RemoteKey.VOLUME_DOWN, "Volume +" to RemoteKey.VOLUME_UP, "Sourdine" to RemoteKey.MUTE,
).map { (label, key) -> label to HidReports.keyFor(key) }

private val NAV_KEYS = listOf(
    "Retour" to HidReports.AC_BACK, "Accueil" to HidReports.AC_HOME, "Menu" to HidReports.CONSUMER_MENU,
    "Infos" to HidReports.APPLICATION,
)

/** Variantes à comparer : chaque TV ne reconnaît pas les mêmes codes. */
private val VARIANT_KEYS = listOf(
    "Échap (retour clavier)" to HidReports.ESCAPE,
    "Retour arrière" to HidReports.BACKSPACE,
    "Origine (clavier)" to HidReports.KEY_HOME,
    "Chaîne +" to HidReports.CHANNEL_UP,
    "Chaîne −" to HidReports.CHANNEL_DOWN,
    "Alimentation" to HidReports.POWER,
)

/**
 * Essai du pilotage par Bluetooth : la tablette se déclare comme clavier, la TV l'appaire depuis
 * son menu Bluetooth, puis chaque touche peut être testée.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun BluetoothKeyboardScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val vm: BluetoothKeyboardViewModel = viewModel(
        factory = viewModelFactory { initializer { BluetoothKeyboardViewModel(AndroidHidKeyboard(context)) } },
    )
    val state by vm.state.collectAsStateWithLifecycle()
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.refresh() }

    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { vm.refresh() }
    val system = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { vm.refresh() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Clavier Bluetooth (essai)") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour") }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                "La tablette se présente à la TV comme un clavier Bluetooth avec touches multimédia " +
                    "(profil standard HID). Sur la TV Samsung : Paramètres › Connexion › Gestionnaire de " +
                    "périphériques externes › Gestionnaire de périphériques d'entrée › Liste des périphériques " +
                    "Bluetooth, puis choisir cette tablette.",
                style = MaterialTheme.typography.bodyMedium,
            )

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("État", style = MaterialTheme.typography.titleMedium)
                    Text(statusText(state), style = MaterialTheme.typography.bodyMedium)
                    state.link.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        when (state.availability) {
                            HidAvailability.NO_PERMISSION -> Button(onClick = {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                    permissions.launch(arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE))
                                }
                            }) { Text("Autoriser les appareils à proximité") }
                            HidAvailability.BLUETOOTH_OFF -> Button(onClick = {
                                runCatching { system.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)) }
                            }) { Text("Activer le Bluetooth") }
                            HidAvailability.READY -> {
                                OutlinedButton(onClick = {
                                    val intent = Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE)
                                        .putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, DISCOVERABLE_SECONDS)
                                    runCatching { system.launch(intent) }
                                }) { Text("Rendre la tablette visible (2 min)") }
                                if (state.link.connected != null) OutlinedButton(onClick = vm::disconnect) { Text("Déconnecter") }
                            }
                            else -> Unit
                        }
                    }
                }
            }

            if (state.availability == HidAvailability.READY && state.hosts.isNotEmpty()) {
                Text("Appareils appairés", style = MaterialTheme.typography.titleMedium)
                state.hosts.forEach { host ->
                    val connected = state.link.connected?.address == host.address
                    ListItem(
                        headlineContent = { Text(host.name) },
                        supportingContent = { Text(if (connected) "Connecté" else host.address) },
                        trailingContent = {
                            if (!connected) TextButton(onClick = { vm.connect(host) }, enabled = state.link.registered) { Text("Connecter") }
                        },
                    )
                }
            }

            Text("Navigation", style = MaterialTheme.typography.titleMedium)
            DPad(enabled = state.canSend, onKey = vm::press)
            KeyGroup(NAV_KEYS, state.canSend, vm::press)

            Text("Multimédia", style = MaterialTheme.typography.titleMedium)
            KeyGroup(MEDIA_KEYS, state.canSend, vm::press)

            Text("Variantes à comparer", style = MaterialTheme.typography.titleMedium)
            KeyGroup(VARIANT_KEYS, state.canSend, vm::press)

            Text("Saisie de texte", style = MaterialTheme.typography.titleMedium)
            Text(
                "Choisir la disposition réglée sur la TV (Paramètres du clavier).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SingleChoiceSegmentedButtonRow {
                KeyboardLayout.entries.forEachIndexed { index, layout ->
                    SegmentedButton(
                        selected = state.layout == layout,
                        onClick = { vm.setLayout(layout) },
                        shape = SegmentedButtonDefaults.itemShape(index, KeyboardLayout.entries.size),
                    ) { Text(layout.label) }
                }
            }
            var text by rememberSaveable { mutableStateOf("") }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(text, { text = it }, label = { Text("Texte") }, singleLine = true, modifier = Modifier.weight(1f))
                Button(onClick = { vm.type(text) }, enabled = state.canSend && text.isNotEmpty()) { Text("Envoyer") }
            }

            state.feedback?.let { Text(it, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary) }
        }
    }
}

private fun statusText(state: BluetoothKeyboardUiState): String = when (state.availability) {
    HidAvailability.UNSUPPORTED -> "Le mode clavier Bluetooth n'est pas disponible sur cet appareil (Android 9 ou plus requis)."
    HidAvailability.NO_BLUETOOTH -> "Cet appareil n'a pas de Bluetooth."
    HidAvailability.NO_PERMISSION -> "La permission « Appareils à proximité » est nécessaire."
    HidAvailability.BLUETOOTH_OFF -> "Le Bluetooth est désactivé."
    HidAvailability.READY -> {
        val link = state.link
        when {
            link.connected != null -> "Connecté à ${link.connected.name} : les touches sont envoyées."
            link.connecting != null -> "Connexion à ${link.connecting.name}…"
            link.registered -> "Déclarée comme clavier. Appairez la tablette depuis la TV, ou connectez un appareil déjà appairé."
            else -> "Déclaration du clavier…"
        }
    }
}

@Composable
private fun DPad(enabled: Boolean, onKey: (String, HidKey) -> Unit) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        PadButton(Icons.Filled.KeyboardArrowUp, "Haut", RemoteKey.UP, enabled, onKey)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PadButton(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Gauche", RemoteKey.LEFT, enabled, onKey)
            FilledTonalButton(onClick = { onKey("OK", HidReports.keyFor(RemoteKey.SELECT)) }, enabled = enabled, modifier = Modifier.widthIn(min = 88.dp)) {
                Text("OK")
            }
            PadButton(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Droite", RemoteKey.RIGHT, enabled, onKey)
        }
        PadButton(Icons.Filled.KeyboardArrowDown, "Bas", RemoteKey.DOWN, enabled, onKey)
        Spacer(Modifier.size(4.dp))
    }
}

@Composable
private fun PadButton(icon: ImageVector, label: String, key: RemoteKey, enabled: Boolean, onKey: (String, HidKey) -> Unit) {
    FilledTonalIconButton(onClick = { onKey(label, HidReports.keyFor(key)) }, enabled = enabled, modifier = Modifier.size(64.dp)) {
        Icon(icon, contentDescription = label, modifier = Modifier.size(36.dp))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun KeyGroup(keys: List<Pair<String, HidKey>>, enabled: Boolean, onKey: (String, HidKey) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        keys.forEach { (label, key) ->
            OutlinedButton(onClick = { onKey(label, key) }, enabled = enabled) { Text(label) }
        }
    }
}

private const val DISCOVERABLE_SECONDS = 120
