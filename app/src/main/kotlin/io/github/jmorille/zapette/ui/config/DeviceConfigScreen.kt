package io.github.jmorille.zapette.ui.config

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import io.github.jmorille.zapette.container
import io.github.jmorille.zapette.model.Device
import io.github.jmorille.zapette.model.DeviceKind
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

/** Types configurables à la main (Chromecast est uniquement découvert via MediaRouter). */
private val MANUAL_KINDS = DeviceKind.entries.filter { it != DeviceKind.CHROMECAST }

private fun usesCredentials(kind: DeviceKind) = kind in setOf(DeviceKind.KODI, DeviceKind.JELLYFIN, DeviceKind.EMBY)

/** Validation pure, testée unitairement. */
object ConfigValidation {
    private val HOST = Regex("""^[A-Za-z0-9.\-:\[\]]+$""")

    fun error(kind: DeviceKind, host: String, port: String, location: String): String? = when {
        kind == DeviceKind.UPNP_RENDERER && !location.startsWith("http") -> "URL de description UPnP requise"
        kind != DeviceKind.UPNP_RENDERER && !HOST.matches(host.trim()) -> "Adresse invalide"
        kind != DeviceKind.UPNP_RENDERER && (port.toIntOrNull() ?: 0) !in 1..65535 -> "Port invalide"
        else -> null
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceConfigScreen(prefillJson: String?, onSaved: (String) -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefill = remember(prefillJson) {
        prefillJson?.let { runCatching { Json.decodeFromString(Device.serializer(), it) }.getOrNull() }
    }

    var kind by rememberSaveable { mutableStateOf(prefill?.kind ?: DeviceKind.KODI) }
    var name by rememberSaveable { mutableStateOf(prefill?.name.orEmpty()) }
    var host by rememberSaveable { mutableStateOf(prefill?.host.orEmpty()) }
    var port by rememberSaveable { mutableStateOf((prefill?.port ?: kind.defaultPort).toString()) }
    var location by rememberSaveable { mutableStateOf(prefill?.extras?.get("location").orEmpty()) }
    var username by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (prefill == null) "Ajouter un appareil" else prefill.name) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour") }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (prefill == null) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(MANUAL_KINDS) { k ->
                        FilterChip(
                            selected = k == kind,
                            onClick = { kind = k; port = k.defaultPort.toString() },
                            label = { Text(k.label) },
                        )
                    }
                }
            }
            OutlinedTextField(name, { name = it }, label = { Text("Nom") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            if (kind == DeviceKind.UPNP_RENDERER) {
                OutlinedTextField(
                    location, { location = it },
                    label = { Text("URL de description (http://…/description.xml)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                OutlinedTextField(host, { host = it }, label = { Text("Adresse IP ou nom") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(
                    port, { port = it.filter(Char::isDigit) },
                    label = { Text("Port") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (usesCredentials(kind)) {
                OutlinedTextField(username, { username = it }, label = { Text("Utilisateur") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(
                    password, { password = it },
                    label = { Text("Mot de passe") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (kind != DeviceKind.KODI) {
                    Text(
                        "Le mot de passe sert uniquement à obtenir un jeton ; il n'est pas conservé.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            if (kind == DeviceKind.FIRE_TV) {
                Text(
                    "Activez « Débogage ADB » dans les options développeur du téléviseur, puis acceptez la clé de Zapette à la première connexion.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (kind == DeviceKind.AIRPLAY) {
                Text("Compatible AirPlay 1 (lecture d'URL). Les récepteurs AirPlay 2 exigeant un appairage ne sont pas pris en charge.", style = MaterialTheme.typography.bodySmall)
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Button(
                onClick = {
                    error = ConfigValidation.error(kind, host, port, location)
                    if (error != null) return@Button
                    val device = buildDevice(prefill, kind, name, host, port, location, username, password)
                    scope.launch {
                        context.container.devices.save(device)
                        onSaved(device.id)
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Enregistrer et ouvrir") }
        }
    }
}

private fun buildDevice(
    prefill: Device?,
    kind: DeviceKind,
    name: String,
    host: String,
    port: String,
    location: String,
    username: String,
    password: String,
): Device {
    val extras = buildMap {
        prefill?.extras?.let(::putAll)
        if (kind == DeviceKind.UPNP_RENDERER) put("location", location.trim())
        if (usesCredentials(kind) && username.isNotBlank()) {
            put("username", username.trim())
            put("password", password)
        }
    }
    val effectiveHost = if (kind == DeviceKind.UPNP_RENDERER) {
        location.substringAfter("://").substringBefore('/').substringBeforeLast(':')
    } else {
        host.trim()
    }
    val effectivePort = if (kind == DeviceKind.UPNP_RENDERER) {
        location.substringAfter("://").substringBefore('/').substringAfterLast(':', "80").toIntOrNull() ?: 80
    } else {
        port.toInt()
    }
    return Device(
        id = prefill?.id ?: Device.manualId(kind, effectiveHost, effectivePort),
        name = name.ifBlank { "${kind.label} $effectiveHost" },
        kind = kind,
        host = effectiveHost,
        port = effectivePort,
        extras = extras,
    )
}
