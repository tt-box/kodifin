package io.github.jmorille.zapette.ui.config.accounts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.jmorille.zapette.mediaserver.QuickConnect
import io.github.jmorille.zapette.model.DeviceKind
import kotlinx.coroutines.Dispatchers
import okhttp3.OkHttpClient

/**
 * Connexion Jellyfin sans mot de passe (Quick Connect) : affiche un code à saisir dans
 * l'interface Jellyfin d'un appareil déjà connecté (Paramètres › Quick Connect), puis
 * appelle [onAuthenticated] avec le jeton, l'identifiant et le nom de l'utilisateur.
 *
 * Le sondage s'arrête si le panneau quitte l'écran ; le jeton n'est jamais affiché.
 */
@Composable
fun QuickConnectPanel(
    baseUrl: String,
    http: OkHttpClient,
    deviceId: String,
    onAuthenticated: (token: String, userId: String, userName: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var state by remember(baseUrl) { mutableStateOf<QuickConnectState>(QuickConnectState.Idle) }
    var attempt by remember(baseUrl) { mutableIntStateOf(0) }
    val callback by rememberUpdatedState(onAuthenticated)

    LaunchedEffect(baseUrl, attempt) {
        if (attempt == 0) return@LaunchedEffect
        val client = mediaServerClient(DeviceKind.JELLYFIN, baseUrl, http, deviceId)
        val login = runQuickConnect(client, QuickConnect(client), Dispatchers.IO) { next -> state = next }
        login?.let { callback(it.token, it.userId, it.userName) }
    }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        when (val s = state) {
            QuickConnectState.Idle -> OutlinedButton(onClick = { attempt++ }) { Text("Se connecter avec Quick Connect") }
            QuickConnectState.Starting -> CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
            is QuickConnectState.WaitingForApproval -> {
                Text(
                    "Sur un appareil déjà connecté à Jellyfin, ouvrez Paramètres › Quick Connect et saisissez :",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    s.code,
                    style = MaterialTheme.typography.displaySmall,
                    modifier = Modifier.semantics { contentDescription = "Code Quick Connect ${s.code.toList().joinToString(" ")}" },
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Text("En attente de validation…", style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { state = QuickConnectState.Idle; attempt = 0 }) { Text("Annuler") }
                }
            }
            is QuickConnectState.Authenticated ->
                Text("Connecté en tant que ${s.userName}, sans mot de passe.", style = MaterialTheme.typography.bodyMedium)
            is QuickConnectState.Failed -> {
                Text(s.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                OutlinedButton(onClick = { attempt++ }) { Text("Réessayer Quick Connect") }
            }
        }
    }
}
