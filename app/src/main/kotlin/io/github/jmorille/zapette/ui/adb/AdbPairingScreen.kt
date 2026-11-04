package io.github.jmorille.zapette.ui.adb

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.jmorille.zapette.container
import io.github.jmorille.zapette.firetv.AdbPairingClient
import io.github.jmorille.zapette.firetv.DadbShell
import io.github.jmorille.zapette.model.Device
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Accès réseau et stockage de l'écran, remplaçables en test. */
interface AdbPairingDependencies {
    /** Services mDNS découverts (liste courante). */
    val services: Flow<List<AdbService>>

    /** Appairage SPAKE2 ; renvoie le GUID du téléviseur. Bloquant (appelé sur IO). */
    fun pair(host: String, port: Int, code: String): String

    /** Ouvre une connexion ADB TLS et exécute une commande anodine. */
    suspend fun checkConnection(host: String, port: Int)

    suspend fun save(device: Device)
}

/** Étape en cours de l'appairage. */
sealed interface AdbPairingStep {
    data object Idle : AdbPairingStep
    data object Pairing : AdbPairingStep
    data object Connecting : AdbPairingStep
    data class Done(val deviceId: String) : AdbPairingStep
}

data class AdbPairingState(
    val host: String = "",
    val pairingPort: String = "",
    val code: String = "",
    val connectPort: String = "",
    val name: String = "",
    val candidates: List<AdbCandidate> = emptyList(),
    val step: AdbPairingStep = AdbPairingStep.Idle,
    /** Vrai une fois le code accepté : on peut retenter la connexion sans réappairer. */
    val paired: Boolean = false,
    val guid: String? = null,
    val error: String? = null,
) {
    val busy get() = step == AdbPairingStep.Pairing || step == AdbPairingStep.Connecting
}

class AdbPairingViewModel(
    private val deps: AdbPairingDependencies,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
    private val _state = MutableStateFlow(AdbPairingState())
    val state: StateFlow<AdbPairingState> = _state.asStateFlow()
    private var job: Job? = null

    init {
        viewModelScope.launch {
            runCatching {
                deps.services.collect { services ->
                    _state.update { it.copy(candidates = AdbPairingLogic.candidates(services)) }
                }
            }
        }
    }

    fun edit(transform: AdbPairingState.() -> AdbPairingState) {
        _state.update { current ->
            val next = current.transform()
            // Changer d'adresse oblige à réappairer.
            if (next.host.trim() != current.host.trim()) next.copy(paired = false, guid = null, error = null) else next.copy(error = null)
        }
    }

    /** Préremplit depuis un téléviseur découvert (ports d'appairage et de connexion). */
    fun choose(candidate: AdbCandidate) = edit {
        copy(
            host = candidate.host,
            pairingPort = candidate.pairingPort?.toString() ?: pairingPort,
            connectPort = candidate.connectPort?.toString() ?: connectPort,
        )
    }

    /** Appaire (si nécessaire), vérifie la connexion TLS puis enregistre l'appareil. */
    fun submit() {
        if (job?.isActive == true) return
        val s = _state.value
        AdbPairingLogic.validate(s.host, s.pairingPort, s.code, s.connectPort, pairing = !s.paired)?.let { message ->
            _state.update { it.copy(error = message) }
            return
        }
        val host = s.host.trim()
        val connectPort = checkNotNull(AdbPairingLogic.parsePort(s.connectPort))
        job = viewModelScope.launch {
            try {
                var guid = s.guid
                if (!s.paired) {
                    _state.update { it.copy(step = AdbPairingStep.Pairing, error = null) }
                    val pairingPort = checkNotNull(AdbPairingLogic.parsePort(s.pairingPort))
                    guid = withContext(io) { deps.pair(host, pairingPort, s.code.trim()) }
                    _state.update { it.copy(paired = true, guid = guid, code = "") }
                }
                _state.update { it.copy(step = AdbPairingStep.Connecting, error = null) }
                deps.checkConnection(host, connectPort)
                val device = AdbPairingLogic.device(host, connectPort, s.name, guid)
                deps.save(device)
                _state.update { it.copy(step = AdbPairingStep.Done(device.id)) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val paired = _state.value.paired
                val message = listOfNotNull(e.message ?: e.javaClass.simpleName, e.cause?.message).distinct().joinToString(" — ")
                _state.update {
                    it.copy(
                        step = AdbPairingStep.Idle,
                        error = if (paired) "Appairé, mais connexion impossible sur le port $connectPort : $message" else message,
                    )
                }
            }
        }
    }
}

/** Implémentation réelle : clé ADB de l'application (filesDir/adb), dépôt des appareils. */
private fun androidDependencies(context: Context): AdbPairingDependencies {
    val app = context.applicationContext
    val keyDir = File(app.filesDir, "adb")
    val discovery = AdbServiceDiscovery(app)
    return object : AdbPairingDependencies {
        override val services = discovery.services()
        override fun pair(host: String, port: Int, code: String): String =
            AdbPairingClient(keyDir).pair(host, port, code).deviceGuid

        override suspend fun checkConnection(host: String, port: Int) {
            val shell = DadbShell(host, port, keyDir, DadbShell.DEFAULT_TIMEOUT_MS, tls = true)
            try {
                shell.shell("echo ok")
            } finally {
                shell.close()
            }
        }

        override suspend fun save(device: Device) = app.container.devices.save(device)
    }
}

/**
 * Appairage « Débogage sans fil » (Android 11+ / Google TV) : IP, port et code à 6 chiffres
 * affichés sur le téléviseur (Options pour les développeurs › Débogage sans fil › Associer
 * l'appareil avec un code d'association), puis port de connexion (écran « Débogage sans fil »).
 * [onPaired] reçoit l'identifiant de l'appareil enregistré.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdbPairingScreen(onPaired: (String) -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val vm: AdbPairingViewModel = viewModel(factory = viewModelFactory { initializer { AdbPairingViewModel(androidDependencies(context)) } })
    val state by vm.state.collectAsStateWithLifecycle()

    val done = state.step as? AdbPairingStep.Done
    LaunchedEffect(done) { done?.let { onPaired(it.deviceId) } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Appairage sans fil (ADB)") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour") }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "Sur le téléviseur (Android 11 ou plus) : Options pour les développeurs › Débogage sans fil › " +
                    "« Associer l'appareil avec un code d'association ». Laissez cette fenêtre ouverte et saisissez " +
                    "l'adresse, le port et le code affichés.",
                style = MaterialTheme.typography.bodyMedium,
            )
            if (state.candidates.isEmpty()) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Text("Recherche des téléviseurs en débogage sans fil…", style = MaterialTheme.typography.bodySmall)
                }
            } else {
                Text("Détectés sur le réseau", style = MaterialTheme.typography.titleSmall)
                state.candidates.forEach { candidate ->
                    ListItem(
                        headlineContent = { Text(candidate.host) },
                        supportingContent = {
                            Text(
                                listOfNotNull(
                                    candidate.pairingPort?.let { "appairage : $it" },
                                    candidate.connectPort?.let { "connexion : $it" },
                                ).joinToString(" · "),
                            )
                        },
                        modifier = Modifier.fillMaxWidth(),
                        trailingContent = {
                            Button(onClick = { vm.choose(candidate) }, enabled = !state.busy) { Text("Choisir") }
                        },
                    )
                }
            }
            OutlinedTextField(
                state.host, { v -> vm.edit { copy(host = v) } },
                label = { Text("Adresse IP") }, singleLine = true, enabled = !state.busy,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), modifier = Modifier.fillMaxWidth(),
            )
            if (!state.paired) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        state.pairingPort, { v -> vm.edit { copy(pairingPort = v.filter(Char::isDigit).take(5)) } },
                        label = { Text("Port d'appairage") }, singleLine = true, enabled = !state.busy,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        state.code, { v -> vm.edit { copy(code = v.filter(Char::isDigit).take(6)) } },
                        label = { Text("Code (6 chiffres)") }, singleLine = true, enabled = !state.busy,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword), modifier = Modifier.weight(1f),
                    )
                }
            } else {
                Text("Appairage réussi. Vérifiez le port de connexion puis réessayez.", color = MaterialTheme.colorScheme.primary)
            }
            OutlinedTextField(
                state.connectPort, { v -> vm.edit { copy(connectPort = v.filter(Char::isDigit).take(5)) } },
                label = { Text("Port de connexion") },
                supportingText = { Text("Affiché sur l'écran « Débogage sans fil » (adresse IP et port), différent du port d'appairage.") },
                singleLine = true, enabled = !state.busy,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                state.name, { v -> vm.edit { copy(name = v) } },
                label = { Text("Nom (facultatif)") }, singleLine = true, enabled = !state.busy, modifier = Modifier.fillMaxWidth(),
            )
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Button(onClick = vm::submit, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
                when (state.step) {
                    AdbPairingStep.Pairing -> Text("Appairage…")
                    AdbPairingStep.Connecting -> Text("Connexion…")
                    else -> Text(if (state.paired) "Se connecter" else "Associer et se connecter")
                }
            }
        }
    }
}
