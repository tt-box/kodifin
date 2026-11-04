package io.github.jmorille.zapette.ui.plex

import android.content.ActivityNotFoundException
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
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
import io.github.jmorille.zapette.plex.PlexHomeUser
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

    /**
     * Compte lié appartenant à un foyer Plex : choix du profil dont le jeton sera enregistré.
     * [busy] pendant la bascule ; [error] après un PIN refusé ou une erreur de plex.tv.
     */
    data class ChoosingUser(
        val users: List<PlexHomeUser>,
        val busy: Boolean = false,
        val error: String? = null,
    ) : PlexLinkState
}

/** Code de liaison plex.tv en attente de validation par l'utilisateur. */
interface PlexPinRequest {
    val code: String

    /** Jeton du compte une fois le code validé, sinon `null`. */
    suspend fun check(): String?
}

/** Dépendances du ViewModel, isolées pour les tests JVM (ni réseau ni DataStore). */
interface PlexLinkDependencies {
    /** Compte lié ? Le jeton lui-même n'est jamais relu ici (il est conservé chiffré). */
    val plexLinked: Flow<Boolean>

    /** Lie le compte avec [token] (chiffré par le dépôt avant écriture) ou le délie (`null`). */
    suspend fun setPlexToken(token: String?)
    suspend fun createPin(): PlexPinRequest

    /** Profils du foyer Plex (Plex Home) du compte [accountToken] ; vide hors foyer. */
    suspend fun homeUsers(accountToken: String): List<PlexHomeUser>

    /** Jeton propre au profil [user] ; [pin] uniquement pour un profil protégé. */
    suspend fun switchUser(accountToken: String, user: PlexHomeUser, pin: String?): String
}

fun AppContainer.plexLinkDependencies(): PlexLinkDependencies = object : PlexLinkDependencies {
    override val plexLinked: Flow<Boolean> get() = devices.plexLinked
    override suspend fun setPlexToken(token: String?) = devices.setPlexToken(token)

    private suspend fun account() = PlexAccount(http, PlexIdentity(devices.installationId()))

    override suspend fun homeUsers(accountToken: String): List<PlexHomeUser> = account().homeUsers(accountToken)

    override suspend fun switchUser(accountToken: String, user: PlexHomeUser, pin: String?): String =
        account().switchHomeUser(accountToken, user.uuid, pin)

    override suspend fun createPin(): PlexPinRequest {
        val account = account()
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
            if (deps.plexLinked.first()) _state.value = PlexLinkState.Linked
        }
    }

    private var linkJob: Job? = null

    /** Jeton du compte, gardé en mémoire le temps du choix du profil puis oublié. */
    private var accountToken: String? = null

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
                    // Le compte est lié tout de suite ; le choix d'un profil le remplacera.
                    deps.setPlexToken(token)
                    offerHomeUsers(token)
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

    /** Propose le choix du profil si le compte appartient à un foyer de plusieurs membres. */
    private suspend fun offerHomeUsers(token: String) {
        val users = try {
            withContext(io) { deps.homeUsers(token) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            emptyList() // Foyer illisible : le jeton du compte reste utilisé.
        }
        if (users.size > 1) {
            accountToken = token
            _state.value = PlexLinkState.ChoosingUser(users)
        } else {
            _state.value = PlexLinkState.Linked
        }
    }

    /**
     * Enregistre le jeton du profil [user] à la place de celui du compte. Un profil protégé
     * exige un PIN à quatre chiffres ; un PIN refusé laisse l'écran de choix ouvert.
     */
    fun chooseUser(user: PlexHomeUser, pin: String? = null) {
        val current = _state.value as? PlexLinkState.ChoosingUser ?: return
        val token = accountToken ?: return
        if (current.busy) return
        val code = pin?.trim()?.takeIf { user.isProtected }
        if (user.isProtected && (code == null || !PIN.matches(code))) {
            _state.value = current.copy(error = "Code PIN à 4 chiffres requis pour ${user.displayName}.")
            return
        }
        _state.value = current.copy(busy = true, error = null)
        linkJob = viewModelScope.launch {
            try {
                val userToken = withContext(io) { deps.switchUser(token, user, code) }
                deps.setPlexToken(userToken)
                accountToken = null
                _state.value = PlexLinkState.Linked
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = current.copy(busy = false, error = e.message ?: "plex.tv injoignable")
            }
        }
    }

    /** Garde le jeton du compte principal (déjà enregistré). */
    fun keepAccount() {
        val current = _state.value as? PlexLinkState.ChoosingUser ?: return
        if (current.busy) return
        accountToken = null
        _state.value = PlexLinkState.Linked
    }

    /**
     * Délie le compte. Une liaison ou une bascule en cours est d'abord annulée (et attendue) :
     * sinon un jeton obtenu juste après réenregistrerait ce qu'on vient d'effacer.
     */
    fun unlink() = viewModelScope.launch {
        linkJob?.let { it.cancel(); it.join() }
        linkJob = null
        accountToken = null
        deps.setPlexToken(null)
        _state.value = PlexLinkState.Idle
    }

    private companion object {
        const val POLL_INTERVAL_MS = 2_000L
        const val MAX_POLLS = 150
        val PIN = Regex("\\d{4}")
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
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(24.dp),
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
                is PlexLinkState.ChoosingUser -> PlexUserChooser(s, onChoose = vm::chooseUser, onKeepAccount = vm::keepAccount)
            }
        }
    }
}

/** Étape « Choisir l'utilisateur » : profils du foyer Plex, PIN demandé pour un profil protégé. */
@Composable
private fun PlexUserChooser(
    state: PlexLinkState.ChoosingUser,
    onChoose: (PlexHomeUser, String?) -> Unit,
    onKeepAccount: () -> Unit,
) {
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    // Le PIN n'est pas sauvegardé dans l'état d'instance (simple remember).
    var pin by remember { mutableStateOf("") }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Choisir l'utilisateur", style = MaterialTheme.typography.titleMedium)
        Text(
            "Ce compte appartient à un foyer Plex : la télécommande utilisera le profil choisi.",
            style = MaterialTheme.typography.bodyMedium,
        )
        state.users.forEach { user ->
            val isSelected = selected == user.uuid
            ListItem(
                headlineContent = { Text(user.displayName) },
                supportingContent = {
                    Text(
                        listOfNotNull(
                            "Administrateur".takeIf { user.admin },
                            "Profil restreint".takeIf { user.restricted },
                            "Protégé par un PIN".takeIf { user.isProtected },
                        ).joinToString(" · ").ifEmpty { "Profil" },
                    )
                },
                leadingContent = { TextAvatar(user.displayName) },
                trailingContent = if (user.isProtected) {
                    { Icon(Icons.Filled.Lock, contentDescription = null) }
                } else {
                    null
                },
                colors = if (isSelected) {
                    ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
                } else {
                    ListItemDefaults.colors()
                },
                modifier = Modifier.fillMaxWidth().selectable(selected = isSelected, enabled = !state.busy) {
                    selected = user.uuid
                    pin = ""
                    if (!user.isProtected) onChoose(user, null)
                },
            )
            if (isSelected && user.isProtected) {
                OutlinedTextField(
                    value = pin,
                    onValueChange = { value -> pin = value.filter(Char::isDigit).take(4) },
                    label = { Text("PIN de ${user.displayName}") },
                    singleLine = true,
                    enabled = !state.busy,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { onChoose(user, pin) }),
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(onClick = { onChoose(user, pin) }, enabled = !state.busy && pin.length == 4) { Text("Valider") }
            }
        }
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (state.busy) CircularProgressIndicator()
        TextButton(onClick = onKeepAccount, enabled = !state.busy) { Text("Garder le compte principal") }
    }
}

/** Avatar texte : initiale du nom dans un disque, sans image téléchargée. */
@Composable
private fun TextAvatar(name: String) {
    Box(
        Modifier.size(40.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            name.trim().firstOrNull()?.uppercase() ?: "?",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
        )
    }
}
