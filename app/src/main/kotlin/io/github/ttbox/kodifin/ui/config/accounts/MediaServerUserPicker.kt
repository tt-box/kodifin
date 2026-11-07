package io.github.ttbox.kodifin.ui.config.accounts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.ttbox.kodifin.R
import io.github.ttbox.kodifin.model.DeviceKind
import io.github.ttbox.kodifin.ui.text.asString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient

/**
 * Liste des utilisateurs publics d'un serveur Jellyfin/Emby (`GET /Users/Public`), à la demande :
 * aucun appel réseau tant que l'utilisateur n'a pas appuyé sur le bouton (pas de requête à chaque
 * caractère saisi dans l'adresse). Un choix appelle [onUserSelected] avec le nom et l'indication
 * « mot de passe requis », pour remplir le champ Utilisateur du formulaire.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MediaServerUserPicker(
    kind: DeviceKind,
    baseUrl: String,
    http: OkHttpClient,
    deviceId: String,
    onUserSelected: (name: String, hasPassword: Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    var state by remember(kind, baseUrl) { mutableStateOf<PublicUsersState>(PublicUsersState.Idle) }
    var attempt by remember(kind, baseUrl) { mutableIntStateOf(0) }
    var selected by rememberSaveable(kind, baseUrl) { mutableStateOf<String?>(null) }

    LaunchedEffect(kind, baseUrl, attempt) {
        if (attempt == 0) return@LaunchedEffect
        state = PublicUsersState.Loading
        state = withContext(Dispatchers.IO) { loadPublicUsers(mediaServerClient(kind, baseUrl, http, deviceId)) }
    }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { attempt++ }, enabled = state != PublicUsersState.Loading) {
                Text(
                    stringResource(
                        if (state is PublicUsersState.Loaded) R.string.config_users_refresh else R.string.config_users_choose_from_server,
                    ),
                )
            }
            if (state == PublicUsersState.Loading) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        }
        when (val s = state) {
            is PublicUsersState.Loaded -> if (s.users.isEmpty()) {
                Text(
                    stringResource(R.string.config_users_none_public),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    s.users.forEach { user ->
                        FilterChip(
                            selected = selected == user.id,
                            onClick = {
                                selected = user.id
                                onUserSelected(user.name, user.requiresPassword)
                            },
                            label = { Text(user.name) },
                            leadingIcon = {
                                Icon(
                                    if (user.requiresPassword) Icons.Filled.Lock else Icons.Filled.Person,
                                    contentDescription = stringResource(
                                        if (user.requiresPassword) R.string.config_user_password_required else R.string.config_user_no_password,
                                    ),
                                    modifier = Modifier.size(FilterChipDefaults.IconSize),
                                )
                            },
                        )
                    }
                }
                val chosen = s.users.firstOrNull { it.id == selected }
                if (chosen != null) {
                    Text(
                        if (chosen.requiresPassword) {
                            stringResource(R.string.config_user_password_required_for, chosen.name)
                        } else {
                            stringResource(R.string.config_user_no_password_leave_empty, chosen.name)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            is PublicUsersState.Failed -> Text(s.message.asString(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            PublicUsersState.Idle, PublicUsersState.Loading -> Unit
        }
    }
}
