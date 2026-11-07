package io.github.ttbox.kodifin.ui.config

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
import androidx.compose.ui.text.style.TextOverflow
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
import io.github.ttbox.kodifin.connection.AdvancedOptions
import io.github.ttbox.kodifin.connection.usesCredentials
import io.github.ttbox.kodifin.connection.quickConnectDevice
import io.github.ttbox.kodifin.connection.buildDevice
import io.github.ttbox.kodifin.connection.DeviceDraft
import io.github.ttbox.kodifin.connection.ConfigValidation
import io.github.ttbox.kodifin.connection.AndroidNetworkInfo
import io.github.ttbox.kodifin.connection.ConnectionMode
import io.github.ttbox.kodifin.connection.ConnectionTestResult
import io.github.ttbox.kodifin.text.UiText
import io.github.ttbox.kodifin.ui.text.asString
import io.github.ttbox.kodifin.text.resolve
import io.github.ttbox.kodifin.connection.DeviceOptions
import io.github.ttbox.kodifin.connection.NetworkGuard
import io.github.ttbox.kodifin.connection.Problem
import io.github.ttbox.kodifin.connection.testConnection
import io.github.ttbox.kodifin.model.InsecureTls
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
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import io.github.ttbox.kodifin.R
import io.github.ttbox.kodifin.container
import io.github.ttbox.kodifin.data.DeviceSecrets
import io.github.ttbox.kodifin.mediaserver.MediaServerController
import io.github.ttbox.kodifin.ui.config.accounts.MediaServerUserPicker
import io.github.ttbox.kodifin.ui.config.accounts.QuickConnectPanel
import io.github.ttbox.kodifin.model.Device
import io.github.ttbox.kodifin.model.DeviceKind
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlin.coroutines.cancellation.CancellationException

/**
 * Types configurables à la main. Chromecast compris : saisi par adresse IP, il est retrouvé
 * parmi les appareils Cast découverts par cette adresse (CastController.route).
 */
private val MANUAL_KINDS = DeviceKind.entries

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
            missing -> Text(stringResource(R.string.config_device_not_found), Modifier.padding(16.dp))
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
    val resources = LocalResources.current
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
    var ssidNotice by remember { mutableStateOf<UiText?>(null) }

    fun fillCurrentSsid() {
        val ssid = network.currentSsid()
        ssidNotice = when {
            !network.isWifiConnected() -> UiText.Res(R.string.config_ssid_no_wifi)
            ssid == null && !network.isLocationEnabled() -> UiText.Res(R.string.config_ssid_enable_location)
            ssid == null -> UiText.Res(R.string.config_ssid_unreadable)
            else -> null.also { wifiSsids = NetworkGuard.addSsid(wifiSsids, ssid) }
        }
    }

    val locationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        locationGranted = result[Manifest.permission.ACCESS_FINE_LOCATION] == true
        if (fillSsidAfterGrant && locationGranted) fillCurrentSsid()
        if (!locationGranted) ssidNotice = UiText.Res(R.string.config_ssid_permission_denied)
        fillSsidAfterGrant = false
    }

    fun requestLocation(thenFill: Boolean) {
        fillSsidAfterGrant = thenFill
        locationPermission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
    }

    /** Valide le formulaire et construit l'appareil, ou affiche l'erreur et renvoie `null`. */
    fun draftOrNull(): DeviceDraft? {
        error = (ConfigValidation.error(kind, host, port, location) ?: DeviceOptions.error(kind, options))?.resolve(resources)
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
                error = resources.getString(R.string.config_save_failed, e.message.orEmpty())
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
                error = resources.getString(R.string.config_save_failed, e.message.orEmpty())
            } finally {
                saving = false
            }
        }
    }

    // --- Sections du formulaire, disposées en une ou deux colonnes selon la largeur.
    val deviceSection: @Composable () -> Unit = {
        FormSection(stringResource(R.string.config_section_device)) {
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
            OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.config_name)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        }
    }

    val connectionSection: @Composable () -> Unit = {
        FormSection(stringResource(R.string.config_section_connection)) {
            if (kind == DeviceKind.UPNP_RENDERER) {
                OutlinedTextField(
                    location, { location = it },
                    label = { Text(stringResource(R.string.config_upnp_location)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                // Hôte et port sur la même ligne : le port est court, l'adresse prend la place.
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
                    OutlinedTextField(
                        host, { host = it },
                        label = { Text(stringResource(R.string.config_host)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        port, { port = it.filter(Char::isDigit) },
                        label = { Text(stringResource(R.string.config_port)) },
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
                WarningCard(stringResource(R.string.config_plex_legacy_https)) {
                    TextButton(onClick = { mode = ConnectionMode.HTTP; tlsPin = "" }) { Text(stringResource(R.string.config_switch_to_http)) }
                }
            }
            when (kind) {
                DeviceKind.FIRE_TV -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Hint(stringResource(R.string.config_hint_fire_tv_adb))
                    OutlinedButton(onClick = onAdbPairing, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.config_adb_wireless_pairing))
                    }
                }
                DeviceKind.AIRPLAY -> Hint(stringResource(R.string.config_hint_airplay))
                DeviceKind.CHROMECAST -> Hint(stringResource(R.string.config_hint_chromecast))
                else -> Unit
            }
        }
    }

    val credentialsSection: @Composable () -> Unit = {
        if (usesCredentials(kind) || kind == DeviceKind.AIRPLAY) {
            FormSection(stringResource(R.string.config_section_credentials)) {
                if (kind != DeviceKind.AIRPLAY) {
                    OutlinedTextField(username, { username = it }, label = { Text(stringResource(R.string.config_username)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                }
                OutlinedTextField(
                    password, { password = it },
                    label = { Text(stringResource(R.string.config_password)) },
                    placeholder = if (hasStoredPassword) ({ Text(stringResource(R.string.config_unchanged_if_empty)) }) else null,
                    singleLine = true,
                    visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { passwordVisible = !passwordVisible }) {
                            Icon(
                                if (passwordVisible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                contentDescription = stringResource(if (passwordVisible) R.string.config_hide_password else R.string.config_show_password),
                            )
                        }
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                )
                Hint(
                    stringResource(
                        if (kind == DeviceKind.AIRPLAY) {
                            R.string.config_hint_password_airplay
                        } else if (kind == DeviceKind.KODI) {
                            R.string.config_hint_password_kodi
                        } else {
                            R.string.config_hint_password_media_server
                        },
                    ),
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
                label = { Text(stringResource(R.string.config_timeout)) },
                supportingText = { Text(stringResource(R.string.config_timeout_help)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (DeviceOptions.supportsBasePath(kind)) {
            OutlinedTextField(
                basePath, { basePath = it },
                label = { Text(stringResource(R.string.config_base_path)) },
                supportingText = { Text(stringResource(R.string.config_base_path_help)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (DeviceOptions.supportsWebSocket(kind)) {
            SwitchRow(
                stringResource(R.string.config_websocket),
                stringResource(R.string.config_websocket_help),
                kodiWebSocket,
            ) { kodiWebSocket = it }
            if (kodiWebSocket) {
                OutlinedTextField(
                    kodiWebSocketPort, { kodiWebSocketPort = it.filter(Char::isDigit) },
                    label = { Text(stringResource(R.string.config_websocket_port)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                Hint(stringResource(R.string.config_websocket_warning))
            }
        }
        if (DeviceOptions.supportsEventServer(kind)) {
            SwitchRow(
                stringResource(R.string.config_event_server),
                stringResource(R.string.config_event_server_help),
                kodiEventServer,
            ) { kodiEventServer = it }
            if (kodiEventServer) {
                OutlinedTextField(
                    kodiEventServerPort, { kodiEventServerPort = it.filter(Char::isDigit) },
                    label = { Text(stringResource(R.string.config_event_server_port)) },
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
                label = { Text(stringResource(R.string.config_plex_client_id)) },
                supportingText = { Text(stringResource(R.string.config_plex_client_id_help)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                plexTokenInput, { plexTokenInput = it; if (it.isNotBlank()) plexTokenCleared = false },
                label = { Text(stringResource(R.string.config_plex_device_token)) },
                placeholder = if (hasStoredPlexToken && !plexTokenCleared) ({ Text(stringResource(R.string.config_unchanged_if_empty)) }) else null,
                supportingText = {
                    Text(stringResource(R.string.config_plex_device_token_help))
                },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth(),
            )
            if (hasStoredPlexToken && !plexTokenCleared && plexTokenInput.isBlank()) {
                TextButton(onClick = { plexTokenCleared = true }) { Text(stringResource(R.string.config_plex_delete_token)) }
            } else if (plexTokenCleared) {
                Hint(stringResource(R.string.config_plex_token_will_be_deleted))
            }
        }
        if (DeviceOptions.supportsAltDidl(kind)) {
            SwitchRow(
                stringResource(R.string.config_upnp_alt_didl),
                stringResource(R.string.config_upnp_alt_didl_help),
                upnpAltDidl,
            ) { upnpAltDidl = it }
        }
        if (DeviceOptions.supportsNetworkRestriction(kind)) {
            Text(stringResource(R.string.config_network), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 4.dp))
            SwitchRow(stringResource(R.string.config_wifi_only), stringResource(R.string.config_wifi_only_help), wifiOnly) { wifiOnly = it }
            OutlinedTextField(
                wifiSsids, { wifiSsids = it },
                label = { Text(stringResource(R.string.config_wifi_ssids)) },
                supportingText = { Text(stringResource(R.string.config_wifi_ssids_help)) },
                minLines = 1,
                maxLines = 4,
                modifier = Modifier.fillMaxWidth(),
            )
            TextButton(onClick = { if (locationGranted) fillCurrentSsid() else requestLocation(thenFill = true) }) {
                Text(stringResource(R.string.config_use_current_network))
            }
            if (wifiSsids.isNotBlank() && !locationGranted) {
                // Explication avant la demande : Android associe la lecture du SSID à la localisation.
                WarningCard(stringResource(R.string.config_location_rationale)) {
                    TextButton(onClick = { requestLocation(thenFill = false) }) { Text(stringResource(R.string.config_allow)) }
                }
            } else if (wifiSsids.isNotBlank() && !network.isLocationEnabled()) {
                Hint(stringResource(R.string.config_location_disabled))
            }
            ssidNotice?.let { Hint(it.asString()) }
        }
        if (DeviceOptions.supportsWakeOnLan(kind)) {
            Text(stringResource(R.string.config_wake_on_lan), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 4.dp))
            OutlinedTextField(
                wolMac, { wolMac = it },
                label = { Text(stringResource(R.string.config_wol_mac)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                modifier = Modifier.fillMaxWidth(),
            )
            if (wolMac.isNotBlank()) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        wolBroadcast, { wolBroadcast = it },
                        label = { Text(stringResource(R.string.config_wol_broadcast)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        wolPort, { wolPort = it.filter(Char::isDigit) },
                        label = { Text(stringResource(R.string.config_port)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.width(110.dp),
                    )
                }
                SwitchRow(
                    stringResource(R.string.config_wol_auto),
                    stringResource(R.string.config_wol_auto_help),
                    wolAuto,
                ) { wolAuto = it }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (editing) {
                            stringResource(R.string.config_title_edit, prefill?.name.orEmpty())
                        } else {
                            prefill?.name ?: stringResource(R.string.config_title_add)
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.config_back)) }
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
                                Text(stringResource(R.string.config_testing))
                            } else {
                                Text(stringResource(R.string.config_test_connection))
                            }
                        }
                        Button(onClick = ::save, enabled = !saving, modifier = Modifier.weight(1f)) {
                            Text(stringResource(if (editing) R.string.config_save else R.string.config_save_and_open))
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
                            if (kind != DeviceKind.CHROMECAST) FormSection(stringResource(R.string.config_section_advanced)) { advancedFields() }
                        }
                    }
                } else {
                    // Téléphone : une colonne, options avancées repliables.
                    deviceSection()
                    connectionSection()
                    credentialsSection()
                    if (kind != DeviceKind.CHROMECAST) {
                        FormSection(
                            stringResource(R.string.config_section_advanced),
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
                        contentDescription = stringResource(if (expanded) R.string.config_collapse else R.string.config_expand),
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
    Text(stringResource(R.string.config_connection_mode), style = MaterialTheme.typography.titleSmall)
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
                Text(m.text.asString(), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
    if (mode == ConnectionMode.HTTPS_INSECURE) {
        WarningCard(stringResource(R.string.config_insecure_mode_warning))
    }
}

/** Certificat épinglé (mode non vérifié), ou avertissement si aucun ne l'est encore. */
@Composable
private fun TrustedCertificate(pin: String, onForget: () -> Unit) {
    if (pin.isBlank()) {
        WarningCard(stringResource(R.string.config_no_trusted_certificate))
    } else {
        Hint(stringResource(R.string.config_trusted_certificate, pin))
        TextButton(onClick = onForget) { Text(stringResource(R.string.config_forget_certificate)) }
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
            result.message.asString(),
            style = MaterialTheme.typography.bodyMedium,
            color = if (success) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
        )
        (result as? ConnectionTestResult.Failure)?.advice?.let { Hint(it.asString()) }
        result.certificateSha256?.let { Hint(stringResource(R.string.config_certificate_fingerprint, it)) }
        onUseInsecure?.let { TextButton(onClick = it) { Text(stringResource(R.string.config_trust_certificate_insecure)) } }
        onTrust?.let {
            val changed = (result as? ConnectionTestResult.Failure)?.problem == Problem.CERTIFICATE_CHANGED
            TextButton(onClick = it) { Text(stringResource(if (changed) R.string.config_trust_new_certificate else R.string.config_trust_certificate)) }
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
