package io.github.jmorille.zapette.ui.plex

import android.content.Intent
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface PlexLinkState {
    data object Idle : PlexLinkState
    data object Loading : PlexLinkState
    data class WaitingForCode(val code: String) : PlexLinkState
    data object Linked : PlexLinkState
    data class Failed(val message: String) : PlexLinkState
}

class PlexLinkViewModel(private val container: AppContainer) : ViewModel() {
    private val _state = MutableStateFlow<PlexLinkState>(PlexLinkState.Idle)
    val state: StateFlow<PlexLinkState> = _state

    init {
        viewModelScope.launch {
            if (container.devices.plexToken.first() != null) _state.value = PlexLinkState.Linked
        }
    }

    fun start() = viewModelScope.launch {
        _state.value = PlexLinkState.Loading
        try {
            val account = PlexAccount(container.http, PlexIdentity(container.devices.installationId()))
            val pin = withContext(Dispatchers.IO) { account.createPin() }
            _state.value = PlexLinkState.WaitingForCode(pin.code)
            repeat(150) { // ~5 minutes
                delay(2_000)
                val token = withContext(Dispatchers.IO) { account.checkPin(pin) }
                if (token != null) {
                    container.devices.setPlexToken(token)
                    _state.value = PlexLinkState.Linked
                    return@launch
                }
            }
            _state.value = PlexLinkState.Failed("Code expiré, recommencez.")
        } catch (e: Exception) {
            _state.value = PlexLinkState.Failed(e.message ?: "plex.tv injoignable")
        }
    }

    fun unlink() = viewModelScope.launch {
        container.devices.setPlexToken(null)
        _state.value = PlexLinkState.Idle
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlexLinkScreen(onDone: () -> Unit) {
    val context = LocalContext.current
    val vm: PlexLinkViewModel = viewModel(factory = viewModelFactory { initializer { PlexLinkViewModel(context.container) } })
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
                        context.startActivity(Intent(Intent.ACTION_VIEW, "https://plex.tv/link".toUri()))
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
