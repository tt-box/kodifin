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
import io.github.jmorille.zapette.data.DeviceSecrets
import io.github.jmorille.zapette.mediaserver.MediaServerController
import io.github.jmorille.zapette.model.Device
import io.github.jmorille.zapette.model.DeviceKind
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import java.net.URI
import kotlin.coroutines.cancellation.CancellationException

/** Types configurables à la main (Chromecast est uniquement découvert via MediaRouter). */
private val MANUAL_KINDS = DeviceKind.entries.filter { it != DeviceKind.CHROMECAST }

private fun usesCredentials(kind: DeviceKind) = kind in setOf(DeviceKind.KODI, DeviceKind.JELLYFIN, DeviceKind.EMBY)

/** Validation pure, testée unitairement. */
object ConfigValidation {
    private val HOST = Regex("""^[A-Za-z0-9.\-:\[\]]+$""")

    fun error(kind: DeviceKind, host: String, port: String, location: String): String? = when {
        kind == DeviceKind.UPNP_RENDERER && locationEndpoint(location) == null -> "URL de description UPnP requise"
        kind != DeviceKind.UPNP_RENDERER && !HOST.matches(host.trim()) -> "Adresse invalide"
        kind != DeviceKind.UPNP_RENDERER && (port.toIntOrNull() ?: 0) !in 1..65535 -> "Port invalide"
        else -> null
    }

    /**
     * Hôte et port d'une URL de description UPnP (`http(s)://hôte[:port]/…`), ou `null` si
     * l'URL est invalide. Gère IPv6 (`[fe80::1]`) et le port implicite (80 / 443), ce que
     * le découpage manuel sur « : » faisait mal.
     */
    fun locationEndpoint(location: String): Pair<String, Int>? {
        val uri = runCatching { URI(location.trim()) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") return null
        val host = uri.host?.takeIf { it.isNotEmpty() } ?: return null
        val port = if (uri.port in 1..65535) uri.port else if (scheme == "https") 443 else 80
        return host to port
    }
}

/**
 * Résultat du formulaire : l'appareil à enregistrer, sans aucun mot de passe, et le mot de
 * passe saisi à part (`null` si les identifiants ne changent pas). Le dépôt décide de son sort :
 * chiffré pour Kodi, gardé en mémoire jusqu'à l'obtention du jeton pour Jellyfin/Emby.
 */
internal data class DeviceDraft(val device: Device, val password: String?) {
    override fun toString() = "DeviceDraft(device=$device, password=${if (password == null) "null" else "***"})"
}

/** Extras liés à l'ancien compte, invalides dès que de nouveaux identifiants sont saisis. */
private val STALE_CREDENTIALS = setOf(
    DeviceSecrets.PASSWORD,
    DeviceSecrets.ENCRYPTED_PASSWORD,
    MediaServerController.EXTRA_TOKEN,
    MediaServerController.EXTRA_USER_ID,
)

/** Construction de l'appareil à partir du formulaire (logique pure, testée unitairement). */
internal fun buildDevice(
    prefill: Device?,
    kind: DeviceKind,
    name: String,
    host: String,
    port: String,
    location: String,
    username: String,
    password: String,
): DeviceDraft {
    // Changement de type : les extras (jeton Plex/Jellyfin, identifiant de session…) et
    // l'identifiant de l'appareil pré-rempli appartiennent à un autre protocole.
    val base = prefill?.takeIf { it.kind == kind }
    val newCredentials = usesCredentials(kind) && username.isNotBlank()
    val extras = buildMap {
        base?.extras?.let { putAll(it - DeviceSecrets.PASSWORD) }
        if (kind == DeviceKind.UPNP_RENDERER) put("location", location.trim())
        if (newCredentials) {
            STALE_CREDENTIALS.forEach { remove(it) }
            put("username", username.trim())
        }
    }
    val endpoint = if (kind == DeviceKind.UPNP_RENDERER) ConfigValidation.locationEndpoint(location) else null
    val effectiveHost = endpoint?.first ?: host.trim()
    val effectivePort = endpoint?.second ?: port.toInt()
    val device = Device(
        id = base?.id ?: Device.manualId(kind, effectiveHost, effectivePort),
        name = name.trim().ifBlank { "${kind.label} $effectiveHost" },
        kind = kind,
        host = effectiveHost,
        port = effectivePort,
        extras = extras,
    )
    return DeviceDraft(device, password.takeIf { newCredentials })
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
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }

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
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                OutlinedTextField(
                    host, { host = it },
                    label = { Text("Adresse IP ou nom") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth(),
                )
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
                Text(
                    if (kind == DeviceKind.KODI) {
                        "Le mot de passe est conservé chiffré (Android Keystore) sur ce téléphone."
                    } else {
                        "Le mot de passe sert uniquement à obtenir un jeton ; il n'est pas conservé."
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
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
                    val draft = buildDevice(prefill, kind, name, host, port, location, username, password)
                    // Désactive le bouton : un double appui enregistrait et ouvrait deux télécommandes.
                    saving = true
                    scope.launch {
                        try {
                            context.container.devices.save(draft.device, draft.password)
                            onSaved(draft.device.id)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            error = "Enregistrement impossible : ${e.message.orEmpty()}"
                        } finally {
                            saving = false
                        }
                    }
                },
                enabled = !saving,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Enregistrer et ouvrir") }
        }
    }
}
