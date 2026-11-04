package io.github.jmorille.zapette.ui.plex

import android.content.ActivityNotFoundException
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.jmorille.zapette.AppContainer
import io.github.jmorille.zapette.container
import io.github.jmorille.zapette.plex.PlexAccount
import io.github.jmorille.zapette.plex.PlexIdentity
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException

sealed interface PlexLinkState {
    data object Idle : PlexLinkState
    data object Loading : PlexLinkState
    data class WaitingForCode(val code: String) : PlexLinkState
    data object Linked : PlexLinkState
    data class Failed(val message: String) : PlexLinkState
}

/** Code de liaison plex.tv en attente de validation par l'utilisateur. */
interface PlexPinRequest {
    val code: String

    /** Jeton du compte une fois le code validé, sinon `null`. */
    suspend fun check(): String?
}

/** Dépendances du ViewModel, isolées pour les tests JVM (ni réseau ni DataStore). */
interface PlexLinkDependencies {
    val plexToken: Flow<String?>
    suspend fun setPlexToken(token: String?)
    suspend fun createPin(): PlexPinRequest
}

fun AppContainer.plexLinkDependencies(): PlexLinkDependencies = object : PlexLinkDependencies {
    override val plexToken: Flow<String?> get() = devices.plexToken
    override suspend fun setPlexToken(token: String?) = devices.setPlexToken(token)
    override suspend fun createPin(): PlexPinRequest {
        val account = PlexAccount(http, PlexIdentity(devices.installationId()))
        val pin = account.createPin()
        return object : PlexPinRequest {
            override val code: String = pin.code
            override suspend fun check(): String? = account.checkPin(pin)
        }
    }
}

class PlexLinkViewModel(
    private val deps: PlexLinkDependencies,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val pollIntervalMs: Long = POLL_INTERVAL_MS,
    private val maxPolls: Int = MAX_POLLS,
) : ViewModel() {
    private val _state = MutableStateFlow<PlexLinkState>(PlexLinkState.Idle)
    val state: StateFlow<PlexLinkState> = _state

    init {
        viewModelScope.launch {
            if (deps.plexToken.first() != null) _state.value = PlexLinkState.Linked
        }
    }

    private var linkJob: Job? = null

    /** Démarre la liaison ; un second appel pendant l'attente du code est ignoré. */
    fun start() {
        if (linkJob?.isActive == true) return
        linkJob = viewModelScope.launch { link() }
    }

    private suspend fun link() {
        _state.value = PlexLinkState.Loading
        try {
            val pin = withContext(io) { deps.createPin() }
            _state.value = PlexLinkState.WaitingForCode(pin.code)
            repeat(maxPolls) { // 150 × 2 s : environ 5 minutes
                delay(pollIntervalMs)
                val token = withContext(io) { pin.check() }
                if (token != null) {
                    deps.setPlexToken(token)
                    _state.value = PlexLinkState.Linked
                    return
                }
            }
            _state.value = PlexLinkState.Failed("Code expiré, recommencez.")
        } catch (e: CancellationException) {
            // Écran quitté ou liaison abandonnée : l'annulation n'est pas un échec de liaison.
            throw e
        } catch (e: Exception) {
            _state.value = PlexLinkState.Failed(e.message ?: "plex.tv injoignable")
        }
    }

    /**
     * Délie le compte. Une liaison en cours est d'abord annulée (et attendue) : sinon un code
     * validé juste après réenregistrerait le jeton qu'on vient d'effacer.
     */
    fun unlink() = viewModelScope.launch {
        linkJob?.let { it.cancel(); it.join() }
        linkJob = null
        deps.setPlexToken(null)
        _state.value = PlexLinkState.Idle
    }

    private companion object {
        const val POLL_INTERVAL_MS = 2_000L
        const val MAX_POLLS = 150
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlexLinkScreen(onDone: () -> Unit) {
    val context = LocalContext.current
    val vm: PlexLinkViewModel = viewModel(factory = viewModelFactory { initializer { PlexLinkViewModel(context.container.plexLinkDependencies()) } })
    val state by vm.state.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Compte Plex") },
                navigationIcon = {
                    IconButton(onClick = onDone) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour") }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                "Certains lecteurs Plex exigent un jeton de compte. Zapette utilise le code de liaison " +
                    "plex.tv : votre mot de passe n'est jamais saisi dans l'application.",
                style = MaterialTheme.typography.bodyMedium,
            )
            when (val s = state) {
                PlexLinkState.Idle -> Button(onClick = { vm.start() }) { Text("Obtenir un code") }
                PlexLinkState.Loading -> CircularProgressIndicator()
                is PlexLinkState.WaitingForCode -> {
                    Text("Saisissez ce code sur plex.tv/link :", style = MaterialTheme.typography.bodyLarge)
                    Text(s.code, style = MaterialTheme.typography.displayMedium)
                    OutlinedButton(onClick = {
                        // Aucun navigateur installé (profil restreint, TV) : ne pas planter.
                        try {
                            context.startActivity(Intent(Intent.ACTION_VIEW, "https://plex.tv/link".toUri()))
                        } catch (_: ActivityNotFoundException) {
                            Toast.makeText(context, "Aucun navigateur : ouvrez plex.tv/link sur un autre appareil.", Toast.LENGTH_LONG).show()
                        }
                    }) { Text("Ouvrir plex.tv/link") }
                    CircularProgressIndicator()
                }
                PlexLinkState.Linked -> {
                    Text("Compte Plex lié.", style = MaterialTheme.typography.titleMedium)
                    OutlinedButton(onClick = { vm.unlink() }) { Text("Délier") }
                }
                is PlexLinkState.Failed -> {
                    Text(s.message, color = MaterialTheme.colorScheme.error)
                    Button(onClick = { vm.start() }) { Text("Réessayer") }
                }
            }
        }
    }
}
