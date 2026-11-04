package io.github.jmorille.zapette.ui.config

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.input.VisualTransformation
import io.github.jmorille.zapette.connection.AdvancedOptions
import io.github.jmorille.zapette.connection.ConnectionTestResult
import io.github.jmorille.zapette.connection.DeviceOptions
import io.github.jmorille.zapette.connection.testConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
    /** Options avancées ; `null` : celles de l'appareil pré-rempli sont conservées telles quelles. */
    options: AdvancedOptions? = null,
): DeviceDraft {
    // Changement de type : les extras (jeton Plex/Jellyfin, identifiant de session…) et
    // l'identifiant de l'appareil pré-rempli appartiennent à un autre protocole.
    val base = prefill?.takeIf { it.kind == kind }
    // Édition : même utilisateur et mot de passe laissé vide = identifiants inchangés
    // (jeton et mot de passe chiffré conservés).
    val sameAccount = base != null && username.trim() == base.extras["username"] && password.isEmpty()
    val newCredentials = usesCredentials(kind) && username.isNotBlank() && !sameAccount
    val extras = buildMap {
        base?.extras?.let { putAll(it - DeviceSecrets.PASSWORD) }
        if (kind == DeviceKind.UPNP_RENDERER) put("location", location.trim())
        if (newCredentials) {
            STALE_CREDENTIALS.forEach { remove(it) }
            put("username", username.trim())
        }
    }.let { if (options != null) DeviceOptions.apply(it, kind, options) else it }
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
fun DeviceConfigScreen(
    prefillJson: String?,
    onSaved: (String) -> Unit,
    onBack: () -> Unit,
    /** Identifiant d'un appareil enregistré à modifier (prioritaire sur [prefillJson]). */
    editDeviceId: String? = null,
) {
    val context = LocalContext.current
    if (editDeviceId != null) {
        // Appareil enregistré : chargé depuis le dépôt (les secrets ne transitent pas par la route).
        var loaded by remember { mutableStateOf<Device?>(null) }
        var missing by remember { mutableStateOf(false) }
        LaunchedEffect(editDeviceId) {
            val device = context.container.devices.get(editDeviceId)
            if (device == null) missing = true else loaded = device
        }
        when {
            loaded != null -> DeviceConfigForm(loaded, editing = true, onSaved = onSaved, onBack = onBack)
            missing -> Text("Appareil introuvable", Modifier.padding(16.dp))
            else -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        }
        return
    }
    val prefill = remember(prefillJson) {
        prefillJson?.let { runCatching { Json.decodeFromString(Device.serializer(), it) }.getOrNull() }
    }
    DeviceConfigForm(prefill, editing = false, onSaved = onSaved, onBack = onBack)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DeviceConfigForm(prefill: Device?, editing: Boolean, onSaved: (String) -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var kind by rememberSaveable { mutableStateOf(prefill?.kind ?: DeviceKind.KODI) }
    var name by rememberSaveable { mutableStateOf(prefill?.name.orEmpty()) }
    var host by rememberSaveable { mutableStateOf(prefill?.host.orEmpty()) }
    var port by rememberSaveable { mutableStateOf((prefill?.port ?: kind.defaultPort).toString()) }
    var location by rememberSaveable { mutableStateOf(prefill?.extras?.get("location").orEmpty()) }
    var username by rememberSaveable { mutableStateOf(if (editing) prefill?.extras?.get("username").orEmpty() else "") }
    var password by rememberSaveable { mutableStateOf("") }
    var passwordVisible by rememberSaveable { mutableStateOf(false) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    var testing by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<ConnectionTestResult?>(null) }
    val hasStoredPassword = editing && prefill?.extras?.containsKey(DeviceSecrets.ENCRYPTED_PASSWORD) == true

    // Options avancées : HTTPS, WebSocket Kodi, Wake-on-LAN.
    val initial = remember(prefill) { DeviceOptions.read(prefill) }
    var advancedOpen by rememberSaveable { mutableStateOf(false) }
    var https by rememberSaveable { mutableStateOf(initial.https) }
    var kodiWebSocket by rememberSaveable { mutableStateOf(initial.kodiWebSocket) }
    var kodiWebSocketPort by rememberSaveable { mutableStateOf(initial.kodiWebSocketPort) }
    var wolMac by rememberSaveable { mutableStateOf(initial.wolMac) }
    var wolBroadcast by rememberSaveable { mutableStateOf(initial.wolBroadcast) }
    var wolPort by rememberSaveable { mutableStateOf(initial.wolPort) }
    var wolAuto by rememberSaveable { mutableStateOf(initial.wolAuto) }
    val options = AdvancedOptions(https, kodiWebSocket, kodiWebSocketPort, wolMac, wolBroadcast, wolPort, wolAuto)

    /** Valide le formulaire et construit l'appareil, ou affiche l'erreur et renvoie `null`. */
    fun draftOrNull(): DeviceDraft? {
        error = ConfigValidation.error(kind, host, port, location) ?: DeviceOptions.error(kind, options)
        return if (error == null) buildDevice(prefill, kind, name, host, port, location, username, password, options) else null
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (editing) "Modifier ${prefill?.name.orEmpty()}" else prefill?.name ?: "Ajouter un appareil") },
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
                    placeholder = if (hasStoredPassword) ({ Text("Inchangé si laissé vide") }) else null,
                    singleLine = true,
                    visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { passwordVisible = !passwordVisible }) {
                            Icon(
                                if (passwordVisible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                contentDescription = if (passwordVisible) "Masquer le mot de passe" else "Afficher le mot de passe",
                            )
                        }
                    },
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
            if (kind != DeviceKind.CHROMECAST) {
                HorizontalDivider()
                Row(
                    Modifier.fillMaxWidth().clickable { advancedOpen = !advancedOpen }.padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Options avancées", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    Icon(
                        if (advancedOpen) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                        contentDescription = if (advancedOpen) "Replier" else "Déplier",
                    )
                }
                if (advancedOpen) {
                    if (DeviceOptions.supportsHttps(kind)) {
                        SwitchRow("HTTPS", "Connexion chiffrée (serveur derrière un proxy TLS)", https) { https = it }
                    }
                    if (DeviceOptions.supportsWebSocket(kind)) {
                        SwitchRow(
                            "WebSocket",
                            "Transport JSON-RPC par WebSocket, plus réactif (« Autoriser le contrôle à distance » dans Kodi)",
                            kodiWebSocket,
                        ) { kodiWebSocket = it }
                        if (kodiWebSocket) {
                            OutlinedTextField(
                                kodiWebSocketPort, { kodiWebSocketPort = it.filter(Char::isDigit) },
                                label = { Text("Port WebSocket") },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                    if (DeviceOptions.supportsWakeOnLan(kind)) {
                        Text("Wake-on-LAN", style = MaterialTheme.typography.titleSmall)
                        OutlinedTextField(
                            wolMac, { wolMac = it },
                            label = { Text("Adresse MAC (ex. 58:47:CA:7E:0D:48)") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        if (wolMac.isNotBlank()) {
                            OutlinedTextField(
                                wolBroadcast, { wolBroadcast = it },
                                label = { Text("Adresse de diffusion") },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                                modifier = Modifier.fillMaxWidth(),
                            )
                            OutlinedTextField(
                                wolPort, { wolPort = it.filter(Char::isDigit) },
                                label = { Text("Port Wake-on-LAN") },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier.fillMaxWidth(),
                            )
                            SwitchRow(
                                "Réveil automatique",
                                "Allume l'appareil à l'ouverture de la télécommande et patiente pendant son démarrage",
                                wolAuto,
                            ) { wolAuto = it }
                        }
                    }
                }
                HorizontalDivider()
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            testResult?.let { result ->
                Text(
                    result.message,
                    color = if (result is ConnectionTestResult.Success) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                )
            }
            OutlinedButton(
                onClick = {
                    val draft = draftOrNull() ?: return@OutlinedButton
                    testing = true
                    testResult = null
                    scope.launch {
                        try {
                            testResult = withContext(Dispatchers.IO) {
                                testConnection(draft.device) { context.container.controllers.create(it, draft.password) }
                            }
                        } finally {
                            testing = false
                        }
                    }
                },
                enabled = !testing && !saving,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (testing) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("Test en cours…")
                } else {
                    Text("Tester la connexion")
                }
            }
            Button(
                onClick = {
                    val draft = draftOrNull() ?: return@Button
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
            ) { Text(if (editing) "Enregistrer" else "Enregistrer et ouvrir") }
        }
    }
}

@Composable
private fun SwitchRow(title: String, description: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
