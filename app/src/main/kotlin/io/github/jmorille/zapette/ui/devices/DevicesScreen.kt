package io.github.jmorille.zapette.ui.devices

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
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Speaker
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.filled.Videocam
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
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
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
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

data class DevicesUiState(
    val saved: List<Device> = emptyList(),
    val discovered: List<Device> = emptyList(),
    val scanning: Boolean = true,
)

class DevicesViewModel(private val container: AppContainer) : ViewModel() {
    val state: StateFlow<DevicesUiState> = combine(
        container.devices.saved,
        container.discovery.scan(),
    ) { saved, found ->
        val savedIds = saved.map { it.id }.toSet()
        DevicesUiState(saved = saved, discovered = found.filterNot { it.id in savedIds }, scanning = true)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DevicesUiState())

    /** Enregistre l'appareil découvert s'il n'exige aucun réglage ; renvoie `false` sinon. */
    suspend fun adopt(device: Device): Boolean {
        if (ControllerFactory.needsSetup(device)) return false
        container.devices.save(device)
        return true
    }

    fun delete(device: Device) = viewModelScope.launch { container.devices.delete(device.id) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DevicesScreen(
    sharedUrl: String?,
    onOpen: (String) -> Unit,
    onConfigure: (String?) -> Unit,
    onPlexLink: () -> Unit,
) {
    val context = LocalContext.current
    val vm: DevicesViewModel = viewModel(factory = viewModelFactory { initializer { DevicesViewModel(context.container) } })
    val state by vm.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Zapette") },
                actions = {
                    IconButton(onClick = onPlexLink) { Icon(Icons.Filled.Link, contentDescription = "Lier un compte Plex") }
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
                DeviceRow(device, onClick = { onOpen(device.id) }, onDelete = { vm.delete(device) })
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
private fun DeviceRow(device: Device, onClick: () -> Unit, onDelete: (() -> Unit)? = null) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        ListItem(
            leadingContent = { Icon(iconFor(device.kind), contentDescription = null) },
            headlineContent = { Text(device.name) },
            supportingContent = { Text("${device.kind.label} · ${device.host}") },
            trailingContent = onDelete?.let {
                { IconButton(onClick = it) { Icon(Icons.Filled.Delete, contentDescription = "Supprimer") } }
            },
        )
    }
}

private fun iconFor(kind: DeviceKind): ImageVector = when (kind) {
    DeviceKind.CHROMECAST, DeviceKind.AIRPLAY -> Icons.Filled.Cast
    DeviceKind.UPNP_RENDERER -> Icons.Filled.Speaker
    DeviceKind.JELLYFIN, DeviceKind.EMBY, DeviceKind.PLEX -> Icons.Filled.Videocam
    else -> Icons.Filled.Tv
}
