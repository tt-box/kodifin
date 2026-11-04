package io.github.jmorille.zapette.ui.config

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Surface
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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.input.VisualTransformation
import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.TextButton
import androidx.compose.ui.semantics.Role
import io.github.jmorille.zapette.connection.AdvancedOptions
import io.github.jmorille.zapette.connection.AndroidNetworkInfo
import io.github.jmorille.zapette.connection.ConnectionMode
import io.github.jmorille.zapette.connection.ConnectionTestResult
import io.github.jmorille.zapette.connection.DeviceOptions
import io.github.jmorille.zapette.connection.NetworkGuard
import io.github.jmorille.zapette.connection.Problem
import io.github.jmorille.zapette.connection.testConnection
import io.github.jmorille.zapette.model.InsecureTls
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
import io.github.jmorille.zapette.ui.config.accounts.MediaServerUserPicker
import io.github.jmorille.zapette.ui.config.accounts.QuickConnectPanel
import io.github.jmorille.zapette.model.Device
import io.github.jmorille.zapette.model.DeviceKind
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import java.net.URI
import kotlin.coroutines.cancellation.CancellationException

/**
 * Types configurables à la main. Chromecast compris : saisi par adresse IP, il est retrouvé
 * parmi les appareils Cast découverts par cette adresse (CastController.route).
 */
private val MANUAL_KINDS = DeviceKind.entries

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
    override fun toString(): String {
        val masked = device.copy(extras = device.extras.mapValues { (k, v) -> if (k in DeviceSecrets.SECRET_KEYS) "***" else v })
        return "DeviceDraft(device=$masked, password=${if (password == null) "null" else "***"})"
    }
}

/** Extras liés à l'ancien compte, invalides dès que de nouveaux identifiants sont saisis. */
private val STALE_CREDENTIALS = setOf(
    DeviceSecrets.PASSWORD,
    DeviceSecrets.ENCRYPTED_PASSWORD,
    MediaServerController.EXTRA_TOKEN,
    DeviceSecrets.ENCRYPTED_TOKEN,
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
    /**
     * Plex : jeton propre à l'appareil saisi. `null` : inchangé ; vide : effacé ; sinon remplacé
     * (en clair dans le brouillon, chiffré par le dépôt à l'enregistrement).
     */
    plexToken: String? = null,
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
        .let { if (kind == DeviceKind.PLEX && plexToken != null) withPlexDeviceToken(it, plexToken) else it }
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

/** Remplace (ou efface si [token] est vide) le jeton Plex propre à l'appareil. */
private fun withPlexDeviceToken(extras: Map<String, String>, token: String): Map<String, String> {
    val cleared = extras - DeviceSecrets.TOKEN - DeviceSecrets.ENCRYPTED_TOKEN
    return if (token.isBlank()) cleared else cleared + (DeviceSecrets.TOKEN to token.trim())
}

/**
 * Quick Connect réussi : appareil à enregistrer avec le jeton obtenu, sans aucun mot de passe.
 * La session imposée (`sessionId`) n'est retirée que si la cible ou le compte change : même
 * type, hôte, port et utilisateur que l'appareil enregistré, elle reste valable.
 */
internal fun quickConnectDevice(prefill: Device?, draft: Device, token: String, userId: String, userName: String): Device {
    val sameTarget = prefill != null && prefill.kind == draft.kind && prefill.host == draft.host && prefill.port == draft.port
    val sameAccount = prefill?.extras?.get(MediaServerController.EXTRA_USER_ID)?.let { it == userId }
        ?: (prefill?.extras?.get(MediaServerController.EXTRA_USERNAME) == userName)
    val stale = setOf(DeviceSecrets.PASSWORD, DeviceSecrets.ENCRYPTED_PASSWORD, DeviceSecrets.ENCRYPTED_TOKEN) +
        if (sameTarget && sameAccount) emptySet() else setOf(MediaServerController.EXTRA_SESSION_ID)
    return draft.copy(
        extras = draft.extras - stale + mapOf(
            MediaServerController.EXTRA_TOKEN to token,
            MediaServerController.EXTRA_USER_ID to userId,
            MediaServerController.EXTRA_USERNAME to userName,
        ),
    )
}

/**
 * Contenu additionnel de la section « Identifiants » (sélecteur d'utilisateur, Quick Connect…)
 * fourni par un autre module : type, hôte, port et URL de base courants du formulaire.
 */
typealias AccountExtras = @Composable (kind: DeviceKind, host: String, port: Int, baseUrl: String) -> Unit

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceConfigScreen(
    prefillJson: String?,
    onSaved: (String) -> Unit,
    onBack: () -> Unit,
    /** Identifiant d'un appareil enregistré à modifier (prioritaire sur [prefillJson]). */
    editDeviceId: String? = null,
    /** Emplacement optionnel sous les identifiants ; `null` : rien n'est affiché. */
    accountExtras: AccountExtras? = null,
    /** Fire TV / Android TV : ouvre l'appairage en débogage sans fil (Android 11+). */
    onAdbPairing: () -> Unit = {},
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
            loaded != null -> DeviceConfigForm(loaded, editing = true, onSaved = onSaved, onBack = onBack, accountExtras = accountExtras, onAdbPairing = onAdbPairing)
            missing -> Text("Appareil introuvable", Modifier.padding(16.dp))
            else -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        }
        return
    }
    val prefill = remember(prefillJson) {
        prefillJson?.let { runCatching { Json.decodeFromString(Device.serializer(), it) }.getOrNull() }
    }
    DeviceConfigForm(prefill, editing = false, onSaved = onSaved, onBack = onBack, accountExtras = accountExtras, onAdbPairing = onAdbPairing)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DeviceConfigForm(
    prefill: Device?,
    editing: Boolean,
    onSaved: (String) -> Unit,
    onBack: () -> Unit,
    accountExtras: AccountExtras? = null,
    onAdbPairing: () -> Unit = {},
) {
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

    // Options : mode de connexion, délai, réseau, WebSocket Kodi, Wake-on-LAN…
    val initial = remember(prefill) { DeviceOptions.read(prefill) }
    var advancedOpen by rememberSaveable { mutableStateOf(false) }
    var mode by rememberSaveable { mutableStateOf(initial.mode) }
    var timeoutS by rememberSaveable { mutableStateOf(initial.timeoutS) }
    var wifiOnly by rememberSaveable { mutableStateOf(initial.wifiOnly) }
    var wifiSsids by rememberSaveable { mutableStateOf(initial.wifiSsids) }
    var kodiEventServer by rememberSaveable { mutableStateOf(initial.kodiEventServer) }
    var kodiEventServerPort by rememberSaveable { mutableStateOf(initial.kodiEventServerPort) }
    var upnpAltDidl by rememberSaveable { mutableStateOf(initial.upnpAltDidl) }
    var kodiWebSocket by rememberSaveable { mutableStateOf(initial.kodiWebSocket) }
    var kodiWebSocketPort by rememberSaveable { mutableStateOf(initial.kodiWebSocketPort) }
    var wolMac by rememberSaveable { mutableStateOf(initial.wolMac) }
    var wolBroadcast by rememberSaveable { mutableStateOf(initial.wolBroadcast) }
    var wolPort by rememberSaveable { mutableStateOf(initial.wolPort) }
    var wolAuto by rememberSaveable { mutableStateOf(initial.wolAuto) }
    var basePath by rememberSaveable { mutableStateOf(initial.basePath) }
    // Empreinte du certificat de confiance (mode non vérifié) et options propres à Plex.
    var tlsPin by rememberSaveable { mutableStateOf(initial.tlsPin) }
    var plexClientId by rememberSaveable { mutableStateOf(initial.plexClientId) }
    var plexTokenInput by rememberSaveable { mutableStateOf("") }
    var plexTokenCleared by rememberSaveable { mutableStateOf(false) }
    val hasStoredPlexToken = editing && prefill?.kind == DeviceKind.PLEX && prefill.extras.keys.any {
        it == DeviceSecrets.TOKEN || it == DeviceSecrets.ENCRYPTED_TOKEN
    }
    val options = AdvancedOptions(
        mode = mode, timeoutS = timeoutS, wifiOnly = wifiOnly, wifiSsids = wifiSsids,
        kodiEventServer = kodiEventServer, kodiEventServerPort = kodiEventServerPort, upnpAltDidl = upnpAltDidl,
        kodiWebSocket = kodiWebSocket, kodiWebSocketPort = kodiWebSocketPort,
        wolMac = wolMac, wolBroadcast = wolBroadcast, wolPort = wolPort, wolAuto = wolAuto, basePath = basePath,
        tlsPin = tlsPin, plexClientId = plexClientId,
    )
    val plexToken: String? = when {
        kind != DeviceKind.PLEX -> null
        plexTokenInput.isNotBlank() -> plexTokenInput.trim()
        plexTokenCleared -> ""
        else -> null
    }

    // Lecture du SSID : autorisation de localisation demandée seulement quand l'utilisateur
    // restreint l'appareil à des réseaux Wi-Fi précis.
    val network = remember { AndroidNetworkInfo(context) }
    var locationGranted by remember { mutableStateOf(network.hasLocationPermission()) }
    var fillSsidAfterGrant by remember { mutableStateOf(false) }
    var ssidNotice by remember { mutableStateOf<String?>(null) }

    fun fillCurrentSsid() {
        val ssid = network.currentSsid()
        ssidNotice = when {
            !network.isWifiConnected() -> "Aucun réseau Wi-Fi connecté"
            ssid == null && !network.isLocationEnabled() -> "Activez la localisation pour lire le nom du réseau"
            ssid == null -> "Nom du réseau illisible"
            else -> null.also { wifiSsids = NetworkGuard.addSsid(wifiSsids, ssid) }
        }
    }

    val locationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        locationGranted = result[Manifest.permission.ACCESS_FINE_LOCATION] == true
        if (fillSsidAfterGrant && locationGranted) fillCurrentSsid()
        if (!locationGranted) ssidNotice = "Autorisation refusée : la restriction de réseau ne pourra pas être vérifiée"
        fillSsidAfterGrant = false
    }

    fun requestLocation(thenFill: Boolean) {
        fillSsidAfterGrant = thenFill
        locationPermission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
    }

    /** Valide le formulaire et construit l'appareil, ou affiche l'erreur et renvoie `null`. */
    fun draftOrNull(): DeviceDraft? {
        error = ConfigValidation.error(kind, host, port, location) ?: DeviceOptions.error(kind, options)
        return if (error == null) buildDevice(prefill, kind, name, host, port, location, username, password, options, plexToken) else null
    }

    fun test() {
        val draft = draftOrNull() ?: return
        testing = true
        testResult = null
        scope.launch {
            try {
                testResult = withContext(Dispatchers.IO) {
                    testConnection(
                        draft.device,
                        // Empreinte lue par une simple poignée de main TLS, sans requête HTTP.
                        fingerprint = { d ->
                            if (DeviceOptions.connectionMode(d).secure) {
                                InsecureTls.peerFingerprint(d.host, d.port, DeviceOptions.timeoutSeconds(d) * 1000)
                            } else {
                                null
                            }
                        },
                    ) { context.container.controllers.create(it, draft.password) }
                }
            } finally {
                testing = false
            }
        }
    }

    // Identifiant d'installation (en-tête MediaBrowser DeviceId) pour les appels de compte.
    var installationId by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { installationId = context.container.devices.installationId() }

    /** Quick Connect réussi : enregistre l'appareil avec le jeton, sans aucun mot de passe. */
    fun saveWithToken(token: String, userId: String, userName: String) {
        val draft = draftOrNull() ?: return
        // Jeton en clair dans ce brouillon seulement : le dépôt le chiffre avant écriture.
        val device = quickConnectDevice(prefill, draft.device, token, userId, userName)
        username = userName
        password = ""
        saving = true
        scope.launch {
            try {
                context.container.devices.save(device, null)
                onSaved(device.id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = "Enregistrement impossible : ${e.message.orEmpty()}"
            } finally {
                saving = false
            }
        }
    }

    fun save() {
        val draft = draftOrNull() ?: return
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
    }

    // --- Sections du formulaire, disposées en une ou deux colonnes selon la largeur.
    val deviceSection: @Composable () -> Unit = {
        FormSection("Appareil") {
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
            } else {
                Text(kind.label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            OutlinedTextField(name, { name = it }, label = { Text("Nom") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        }
    }

    val connectionSection: @Composable () -> Unit = {
        FormSection("Connexion") {
            if (kind == DeviceKind.UPNP_RENDERER) {
                OutlinedTextField(
                    location, { location = it },
                    label = { Text("URL de description (http://…/description.xml)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                // Hôte et port sur la même ligne : le port est court, l'adresse prend la place.
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
                    OutlinedTextField(
                        host, { host = it },
                        label = { Text("Adresse IP ou nom") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        port, { port = it.filter(Char::isDigit) },
                        label = { Text("Port") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.width(110.dp),
                    )
                }
            }
            if (DeviceOptions.supportsHttps(kind)) {
                ConnectionModeChooser(mode) { mode = it }
                if (mode == ConnectionMode.HTTPS_INSECURE) {
                    TrustedCertificate(tlsPin, onForget = { tlsPin = "" })
                }
            } else if (kind == DeviceKind.PLEX && mode.secure) {
                // Réglage hérité d'une version antérieure : lu et reconduit, mais plus proposé.
                WarningCard(
                    "Mode HTTPS hérité : l'API de contrôle du lecteur Plex (port 32500) ne le prend pas en charge " +
                        "en pratique, et ce mode n'est plus proposé.",
                ) {
                    TextButton(onClick = { mode = ConnectionMode.HTTP; tlsPin = "" }) { Text("Passer en HTTP") }
                }
            }
            when (kind) {
                DeviceKind.FIRE_TV -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Hint("Activez « Débogage ADB » dans les options développeur du téléviseur, puis acceptez la clé de Zapette à la première connexion.")
                    OutlinedButton(onClick = onAdbPairing, modifier = Modifier.fillMaxWidth()) {
                        Text("Android 11+ : appairer en débogage sans fil (code à 6 chiffres)")
                    }
                }
                DeviceKind.AIRPLAY -> Hint(
                    "Compatible AirPlay 1 (lecture d'URL). Les récepteurs AirPlay 2 exigeant un appairage ne sont pas pris en charge.",
                )
                DeviceKind.CHROMECAST -> Hint(
                    "Saisie par adresse IP : le Chromecast doit aussi être visible par la découverte Cast (même réseau) ; " +
                        "l'adresse sert à le retrouver parmi les appareils découverts.",
                )
                else -> Unit
            }
        }
    }

    val credentialsSection: @Composable () -> Unit = {
        if (usesCredentials(kind) || kind == DeviceKind.AIRPLAY) {
            FormSection("Identifiants") {
                if (kind != DeviceKind.AIRPLAY) {
                    OutlinedTextField(username, { username = it }, label = { Text("Utilisateur") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                }
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
                Hint(
                    if (kind == DeviceKind.AIRPLAY) {
                        "Facultatif : mot de passe AirPlay du récepteur. Conservé chiffré (Android Keystore)."
                    } else if (kind == DeviceKind.KODI) {
                        "Le mot de passe est conservé chiffré (Android Keystore) sur cet appareil."
                    } else {
                        "Le mot de passe sert uniquement à obtenir un jeton ; il n'est pas conservé."
                    },
                )
                val portNumber = port.toIntOrNull()
                val serverUrl = portNumber?.let {
                    DeviceOptions.formBaseUrl(mode, host, it, if (DeviceOptions.supportsBasePath(kind)) basePath else "")
                }
                if (accountExtras != null && portNumber != null && serverUrl != null) {
                    accountExtras(kind, host.trim(), portNumber, serverUrl)
                }
                val id = installationId
                if ((kind == DeviceKind.JELLYFIN || kind == DeviceKind.EMBY) && serverUrl != null && id != null &&
                    DeviceOptions.error(kind, options) == null
                ) {
                    // Client de l'appareil : même délai et même mode TLS (auto-signé) que la connexion.
                    val draftDevice = buildDevice(prefill, kind, name, host, port, location, username, "", options).device
                    val accountHttp = remember(serverUrl, timeoutS, mode) { context.container.controllers.httpFor(draftDevice) }
                    MediaServerUserPicker(kind, serverUrl, accountHttp, id, onUserSelected = { n, needsPassword ->
                        username = n
                        if (!needsPassword) password = ""
                    })
                    if (kind == DeviceKind.JELLYFIN) {
                        QuickConnectPanel(serverUrl, accountHttp, id, onAuthenticated = ::saveWithToken)
                    }
                }
            }
        }
    }

    val advancedFields: @Composable () -> Unit = {
        if (DeviceOptions.supportsTimeout(kind)) {
            OutlinedTextField(
                timeoutS, { timeoutS = it.filter(Char::isDigit).take(2) },
                label = { Text("Délai de connexion (s)") },
                supportingText = { Text("De 1 à 60 s ; 5 s par défaut. À augmenter pour un appareil lent à répondre.") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (DeviceOptions.supportsBasePath(kind)) {
            OutlinedTextField(
                basePath, { basePath = it },
                label = { Text("Chemin de base (ex. /jellyfin)") },
                supportingText = { Text("Vide : détecté automatiquement à la connexion") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth(),
            )
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
                Hint(
                    "Le serveur WebSocket de Kodi (port 9090) n'a pas d'authentification : le mot de passe HTTP n'y " +
                        "est pas utilisé. Le flux n'est pas chiffré, même en mode HTTPS : à réserver à un réseau de confiance.",
                )
            }
        }
        if (DeviceOptions.supportsEventServer(kind)) {
            SwitchRow(
                "Event Server (UDP)",
                "Touches envoyées par l'Event Server de Kodi (« Autoriser le contrôle à distance par des applications »)",
                kodiEventServer,
            ) { kodiEventServer = it }
            if (kodiEventServer) {
                OutlinedTextField(
                    kodiEventServerPort, { kodiEventServerPort = it.filter(Char::isDigit) },
                    label = { Text("Port Event Server") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        if (DeviceOptions.supportsPlexOptions(kind)) {
            Text("Plex", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 4.dp))
            OutlinedTextField(
                plexClientId, { plexClientId = it.trim() },
                label = { Text("Identifiant client (clientIdentifier)") },
                supportingText = { Text("Vide : identifiant du lecteur détecté à la connexion") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                plexTokenInput, { plexTokenInput = it; if (it.isNotBlank()) plexTokenCleared = false },
                label = { Text("Jeton Plex propre à l'appareil") },
                placeholder = if (hasStoredPlexToken && !plexTokenCleared) ({ Text("Inchangé si laissé vide") }) else null,
                supportingText = {
                    Text("Facultatif : prioritaire sur le jeton du compte lié. Conservé chiffré (Android Keystore).")
                },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth(),
            )
            if (hasStoredPlexToken && !plexTokenCleared && plexTokenInput.isBlank()) {
                TextButton(onClick = { plexTokenCleared = true }) { Text("Supprimer le jeton enregistré") }
            } else if (plexTokenCleared) {
                Hint("Le jeton enregistré sera supprimé.")
            }
        }
        if (DeviceOptions.supportsAltDidl(kind)) {
            SwitchRow(
                "Métadonnées DLNA alternatives",
                "Pour les renderers capricieux qui refusent la lecture d'une URL",
                upnpAltDidl,
            ) { upnpAltDidl = it }
        }
        if (DeviceOptions.supportsNetworkRestriction(kind)) {
            Text("Réseau", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 4.dp))
            SwitchRow("Wi-Fi uniquement", "Ne tente pas la connexion hors Wi-Fi (données mobiles, VPN…)", wifiOnly) { wifiOnly = it }
            OutlinedTextField(
                wifiSsids, { wifiSsids = it },
                label = { Text("Réseaux Wi-Fi autorisés (un par ligne)") },
                supportingText = { Text("Vide : tous les réseaux. Renseigné : connexion seulement sur ces réseaux.") },
                minLines = 1,
                maxLines = 4,
                modifier = Modifier.fillMaxWidth(),
            )
            TextButton(onClick = { if (locationGranted) fillCurrentSsid() else requestLocation(thenFill = true) }) {
                Text("Utiliser le réseau actuel")
            }
            if (wifiSsids.isNotBlank() && !locationGranted) {
                // Explication avant la demande : Android associe la lecture du SSID à la localisation.
                WarningCard(
                    "Android ne donne le nom du réseau Wi-Fi qu'aux applications autorisées à accéder à la " +
                        "localisation précise. Zapette ne lit ni ne conserve votre position. Sans cette autorisation, " +
                        "la restriction ne peut pas être vérifiée et la connexion reste permise.",
                ) {
                    TextButton(onClick = { requestLocation(thenFill = false) }) { Text("Autoriser") }
                }
            } else if (wifiSsids.isNotBlank() && !network.isLocationEnabled()) {
                Hint("Localisation désactivée : le nom du réseau ne peut pas être vérifié.")
            }
            ssidNotice?.let { Hint(it) }
        }
        if (DeviceOptions.supportsWakeOnLan(kind)) {
            Text("Wake-on-LAN", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 4.dp))
            OutlinedTextField(
                wolMac, { wolMac = it },
                label = { Text("Adresse MAC (ex. 58:47:CA:7E:0D:48)") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                modifier = Modifier.fillMaxWidth(),
            )
            if (wolMac.isNotBlank()) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        wolBroadcast, { wolBroadcast = it },
                        label = { Text("Adresse de diffusion") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        wolPort, { wolPort = it.filter(Char::isDigit) },
                        label = { Text("Port") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.width(110.dp),
                    )
                }
                SwitchRow(
                    "Réveil automatique",
                    "Allume l'appareil à l'ouverture de la télécommande et patiente pendant son démarrage",
                    wolAuto,
                ) { wolAuto = it }
            }
        }
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
        // Actions toujours visibles, même avec le clavier ouvert ou un long formulaire.
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
                Column(
                    Modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    testResult?.let { result ->
                        val sha = result.certificateSha256?.let(InsecureTls::normalizeFingerprint)
                        TestResultView(
                            result,
                            onUseInsecure = if (
                                result is ConnectionTestResult.Failure && result.problem == Problem.CERTIFICATE &&
                                DeviceOptions.supportsHttps(kind) && mode != ConnectionMode.HTTPS_INSECURE
                            ) {
                                // Confiance au premier usage : le certificat affiché est aussitôt épinglé.
                                { mode = ConnectionMode.HTTPS_INSECURE; tlsPin = sha.orEmpty(); testResult = null }
                            } else {
                                null
                            },
                            onTrust = if (
                                sha != null && mode == ConnectionMode.HTTPS_INSECURE && DeviceOptions.supportsHttps(kind) &&
                                InsecureTls.normalizeFingerprint(tlsPin) != sha
                            ) {
                                { tlsPin = sha; testResult = null }
                            } else {
                                null
                            },
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedButton(onClick = ::test, enabled = !testing && !saving, modifier = Modifier.weight(1f)) {
                            if (testing) {
                                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.width(8.dp))
                                Text("Test…")
                            } else {
                                Text("Tester la connexion")
                            }
                        }
                        Button(onClick = ::save, enabled = !saving, modifier = Modifier.weight(1f)) {
                            Text(if (editing) "Enregistrer" else "Enregistrer et ouvrir")
                        }
                    }
                }
            }
        },
    ) { padding ->
        BoxWithConstraints(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            val wide = maxWidth >= 720.dp
            Column(
                Modifier
                    .widthIn(max = if (wide) 1100.dp else 640.dp)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                if (wide) {
                    // Tablette : connexion à gauche, options avancées toujours visibles à droite.
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            deviceSection()
                            connectionSection()
                            credentialsSection()
                        }
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            if (kind != DeviceKind.CHROMECAST) FormSection("Options avancées") { advancedFields() }
                        }
                    }
                } else {
                    // Téléphone : une colonne, options avancées repliables.
                    deviceSection()
                    connectionSection()
                    credentialsSection()
                    if (kind != DeviceKind.CHROMECAST) {
                        FormSection(
                            "Options avancées",
                            expanded = advancedOpen,
                            onToggle = { advancedOpen = !advancedOpen },
                        ) { advancedFields() }
                    }
                }
            }
        }
    }
}

/** Bloc titré du formulaire ; repliable si [onToggle] est fourni. */
@Composable
private fun FormSection(
    title: String,
    expanded: Boolean = true,
    onToggle: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                Modifier.fillMaxWidth().then(if (onToggle != null) Modifier.clickable(onClick = onToggle) else Modifier),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                if (onToggle != null) {
                    Icon(
                        if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                        contentDescription = if (expanded) "Replier" else "Déplier",
                    )
                }
            }
            if (expanded) content()
        }
    }
}

/** Choix HTTP / HTTPS / HTTPS non vérifié, avec avertissement pour ce dernier. */
@Composable
private fun ConnectionModeChooser(mode: ConnectionMode, onChange: (ConnectionMode) -> Unit) {
    Text("Mode de connexion", style = MaterialTheme.typography.titleSmall)
    Column(Modifier.selectableGroup()) {
        ConnectionMode.entries.forEach { m ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .selectable(selected = m == mode, onClick = { onChange(m) }, role = Role.RadioButton)
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = m == mode, onClick = null)
                Spacer(Modifier.width(8.dp))
                Text(m.label, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
    if (mode == ConnectionMode.HTTPS_INSECURE) {
        WarningCard(
            "Le certificat du serveur n'est pas vérifié par une autorité : à réserver à un serveur domestique au " +
                "certificat auto-signé. Testez la connexion, comparez l'empreinte affichée avec celle du serveur, " +
                "puis faites confiance à ce certificat : seul lui sera ensuite accepté.",
        )
    }
}

/** Certificat épinglé (mode non vérifié), ou avertissement si aucun ne l'est encore. */
@Composable
private fun TrustedCertificate(pin: String, onForget: () -> Unit) {
    if (pin.isBlank()) {
        WarningCard(
            "Aucun certificat de confiance enregistré : tout certificat est accepté, une interception reste " +
                "possible. Testez la connexion puis choisissez « Faire confiance à ce certificat ».",
        )
    } else {
        Hint("Certificat de confiance (SHA-256) : $pin")
        TextButton(onClick = onForget) { Text("Oublier ce certificat") }
    }
}

@Composable
private fun WarningCard(text: String, action: (@Composable () -> Unit)? = null) {
    OutlinedCard(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.Top) {
            Icon(Icons.Filled.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.onErrorContainer)
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer)
                action?.invoke()
            }
        }
    }
}

/** Résultat du test : titre, conseil, empreinte du certificat et actions de confiance. */
@Composable
private fun TestResultView(result: ConnectionTestResult, onUseInsecure: (() -> Unit)?, onTrust: (() -> Unit)?) {
    val success = result is ConnectionTestResult.Success
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            result.message,
            style = MaterialTheme.typography.bodyMedium,
            color = if (success) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
        )
        (result as? ConnectionTestResult.Failure)?.advice?.let { Hint(it) }
        result.certificateSha256?.let { Hint("Empreinte SHA-256 du certificat : $it") }
        onUseInsecure?.let { TextButton(onClick = it) { Text("Faire confiance à ce certificat (mode non vérifié)") } }
        onTrust?.let {
            val changed = (result as? ConnectionTestResult.Failure)?.problem == Problem.CERTIFICATE_CHANGED
            TextButton(onClick = it) { Text(if (changed) "Faire confiance au nouveau certificat" else "Faire confiance à ce certificat") }
        }
    }
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
