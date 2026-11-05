package io.github.jmorille.zapette.ui.devices

import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ImportExport
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.Delete
import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Speaker
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.jmorille.zapette.AppContainer
import io.github.jmorille.zapette.container
import io.github.jmorille.zapette.controller.ControllerFactory
import io.github.jmorille.zapette.model.Device
import io.github.jmorille.zapette.model.DeviceKind
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.runningReduce
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

data class DevicesUiState(
    val saved: List<Device> = emptyList(),
    val discovered: List<Device> = emptyList(),
    val scanning: Boolean = true,
)

/** Résultat courant de la découverte : `found == null` signifie « garder la liste précédente ». */
private data class Discovery(val found: List<Device>?, val scanning: Boolean)

/**
 * Combine appareils enregistrés et découverte réseau.
 * - La découverte n'émet qu'après la première recherche SSDP / NSD (plusieurs secondes) :
 *   sans valeur initiale, `combine` masquait les appareils enregistrés pendant ce temps.
 * - Une erreur de découverte ne doit pas faire planter l'écran (exception non interceptée
 *   dans `stateIn`) : la recherche s'arrête (`scanning = false`) et les appareils déjà
 *   trouvés restent affichés.
 * - Un appareil découvert puis enregistré n'apparaît plus que dans « Mes appareils ».
 */
fun devicesUiState(saved: Flow<List<Device>>, discovered: Flow<List<Device>>): Flow<DevicesUiState> = combine(
    saved,
    discovered
        .map { Discovery(it, scanning = true) }
        .onStart { emit(Discovery(emptyList(), scanning = true)) }
        // Fin normale de la recherche : la barre de progression disparaît, la liste reste.
        .onCompletion { cause -> if (cause == null) emit(Discovery(null, scanning = false)) }
        .catch { emit(Discovery(null, scanning = false)) }
        .runningReduce { previous, next -> if (next.found == null) next.copy(found = previous.found) else next },
) { savedList, discovery ->
    val savedIds = savedList.map { it.id }.toSet()
    DevicesUiState(
        saved = savedList,
        discovered = discovery.found.orEmpty().filterNot { it.id in savedIds },
        scanning = discovery.scanning,
    )
}

/** Dépendances du ViewModel, isolées pour les tests JVM (ni NSD ni DataStore). */
interface DevicesDependencies {
    val saved: Flow<List<Device>>
    fun scan(): Flow<List<Device>>
    suspend fun save(device: Device)
    suspend fun delete(id: String)
}

fun AppContainer.devicesDependencies(): DevicesDependencies = object : DevicesDependencies {
    override val saved: Flow<List<Device>> get() = devices.saved
    override fun scan(): Flow<List<Device>> = discovery.scan()
    override suspend fun save(device: Device) = devices.save(device)
    override suspend fun delete(id: String) = devices.delete(id)
}

class DevicesViewModel(private val deps: DevicesDependencies) : ViewModel() {
    val state: StateFlow<DevicesUiState> = devicesUiState(deps.saved, deps.scan())
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DevicesUiState())

    /** Enregistre l'appareil découvert s'il n'exige aucun réglage ; renvoie `false` sinon. */
    suspend fun adopt(device: Device): Boolean {
        if (ControllerFactory.needsSetup(device)) return false
        deps.save(device)
        return true
    }

    fun delete(device: Device) = viewModelScope.launch { deps.delete(device.id) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DevicesScreen(
    sharedUrl: String?,
    onOpen: (String) -> Unit,
    onConfigure: (String?) -> Unit,
    onPlexLink: () -> Unit,
    onEdit: (String) -> Unit = {},
    onSettings: () -> Unit = {},
    onTransfer: (String?) -> Unit = {},
) {
    val context = LocalContext.current
    val vm: DevicesViewModel = viewModel(factory = viewModelFactory { initializer { DevicesViewModel(context.container.devicesDependencies()) } })
    val state by vm.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    // Suppression confirmée : elle efface aussi les identifiants enregistrés de l'appareil.
    var toDelete by rememberSaveable { mutableStateOf<String?>(null) }
    toDelete?.let { id ->
        // Après une rotation, la liste peut être encore vide le temps du rechargement.
        val device = state.saved.firstOrNull { it.id == id }
        if (device != null) {
            AlertDialog(
                onDismissRequest = { toDelete = null },
                title = { Text("Supprimer « ${device.name} » ?") },
                text = { Text("L'appareil et ses identifiants enregistrés seront effacés.") },
                confirmButton = {
                    TextButton(onClick = { vm.delete(device); toDelete = null }) { Text("Supprimer") }
                },
                dismissButton = { TextButton(onClick = { toDelete = null }) { Text("Annuler") } },
            )
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Zapette") },
                actions = {
                    IconButton(onClick = { onTransfer(null) }) {
                        Icon(Icons.Filled.ImportExport, contentDescription = "Importer / exporter les appareils")
                    }
                    IconButton(onClick = onPlexLink) { Icon(Icons.Filled.Link, contentDescription = "Lier un compte Plex") }
                    IconButton(onClick = onSettings) { Icon(Icons.Filled.Settings, contentDescription = "Réglages") }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { onConfigure(null) }) {
                Icon(Icons.Filled.Add, contentDescription = "Ajouter un appareil")
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (sharedUrl != null) {
                item {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp)) {
                            Text("Lien à envoyer", style = MaterialTheme.typography.titleSmall)
                            Text(sharedUrl, style = MaterialTheme.typography.bodySmall, maxLines = 2)
                            Text("Choisissez l'appareil de destination.", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
            item { SectionTitle("Mes appareils") }
            if (state.saved.isEmpty()) {
                item { Text("Aucun appareil enregistré.", style = MaterialTheme.typography.bodyMedium) }
            }
            items(state.saved, key = { "s-" + it.id }) { device ->
                DeviceRow(device, onClick = { onOpen(device.id) }, onEdit = { onEdit(device.id) }, onShare = { onTransfer(device.id) }, onDelete = { toDelete = device.id })
            }
            item {
                SectionTitle("Sur le réseau")
                if (state.scanning) LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 4.dp))
            }
            items(state.discovered, key = { "d-" + it.id }) { device ->
                DeviceRow(device, onClick = {
                    scope.launch {
                        if (vm.adopt(device)) onOpen(device.id) else onConfigure(Json.encodeToString(Device.serializer(), device))
                    }
                })
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
}

@Composable
private fun DeviceRow(device: Device, onClick: () -> Unit, onEdit: (() -> Unit)? = null, onShare: (() -> Unit)? = null, onDelete: (() -> Unit)? = null) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        ListItem(
            leadingContent = { Icon(deviceIcon(device.kind), contentDescription = null) },
            headlineContent = { Text(device.name) },
            supportingContent = { Text("${device.kind.label} · ${device.host}") },
            trailingContent = if (onEdit == null && onDelete == null) null else {
                {
                    Row {
                        onEdit?.let { IconButton(onClick = it) { Icon(Icons.Filled.Edit, contentDescription = "Modifier ${device.name}") } }
                        onShare?.let { IconButton(onClick = it) { Icon(Icons.Filled.QrCode, contentDescription = "QR code de ${device.name}") } }
                        onDelete?.let { IconButton(onClick = it) { Icon(Icons.Filled.Delete, contentDescription = "Supprimer ${device.name}") } }
                    }
                }
            },
        )
    }
}

/** Icône de la nature d'un appareil (accueil, sélecteur de télécommandes). */
internal fun deviceIcon(kind: DeviceKind): ImageVector = when (kind) {
    DeviceKind.CHROMECAST, DeviceKind.AIRPLAY -> Icons.Filled.Cast
    DeviceKind.UPNP_RENDERER -> Icons.Filled.Speaker
    DeviceKind.JELLYFIN, DeviceKind.EMBY, DeviceKind.PLEX -> Icons.Filled.Videocam
    else -> Icons.Filled.Tv
}
