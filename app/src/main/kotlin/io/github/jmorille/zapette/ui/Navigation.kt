package io.github.jmorille.zapette.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.navigation.NavBackStackEntry
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import io.github.jmorille.zapette.ui.config.DeviceConfigScreen
import io.github.jmorille.zapette.ui.devices.DevicesScreen
import io.github.jmorille.zapette.ui.plex.PlexLinkScreen
import io.github.jmorille.zapette.ui.remote.RemoteScreen
import kotlinx.serialization.Serializable

@Serializable
object DevicesRoute

/**
 * [deviceJson] : appareil pré-rempli (découvert) au format JSON, ou `null` pour une saisie manuelle.
 * Un appareil découvert ne porte aucun secret (ni mot de passe ni jeton) ; quelques centaines
 * d'octets, encodés par Navigation dans la route.
 */
@Serializable
data class ConfigRoute(val deviceJson: String? = null)

@Serializable
data class RemoteRoute(val deviceId: String, val sharedUrl: String? = null)

@Serializable
object PlexLinkRoute

/**
 * Vrai si l'écran est au premier plan et stable : ignore les doubles appuis pendant une
 * transition (sinon deux « Retour » rapides dépilent aussi l'écran d'accueil et laissent
 * un écran vide, et deux appuis sur un appareil empilent deux télécommandes).
 */
private val NavBackStackEntry.isResumed: Boolean
    get() = lifecycle.currentState == Lifecycle.State.RESUMED

@Composable
fun ZapetteNavHost(sharedUrl: String?, onSharedUrlConsumed: () -> Unit) {
    val nav = rememberNavController()

    // Un nouveau lien partagé (onNewIntent) alors qu'un autre écran est ouvert : on revient à
    // la liste pour choisir l'appareil. Mémorisé pour ne pas refaire ce retour à la rotation.
    var handledUrl by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(sharedUrl) {
        if (sharedUrl != null && sharedUrl != handledUrl) {
            handledUrl = sharedUrl
            nav.popBackStack(DevicesRoute, inclusive = false)
        }
    }

    NavHost(navController = nav, startDestination = DevicesRoute) {
        composable<DevicesRoute> { entry ->
            DevicesScreen(
                sharedUrl = sharedUrl,
                onOpen = { id ->
                    if (entry.isResumed) {
                        nav.navigate(RemoteRoute(id, sharedUrl))
                        onSharedUrlConsumed()
                    }
                },
                onConfigure = { json -> if (entry.isResumed) nav.navigate(ConfigRoute(json)) },
                onPlexLink = { if (entry.isResumed) nav.navigate(PlexLinkRoute) },
            )
        }
        composable<ConfigRoute> { entry ->
            DeviceConfigScreen(
                prefillJson = entry.toRoute<ConfigRoute>().deviceJson,
                onSaved = { id ->
                    // Appelé une seule fois (bouton désactivé pendant l'enregistrement), y compris
                    // si l'application est passée en arrière-plan entre-temps : pas de garde ici.
                    // Le lien partagé en attente suit l'appareil qu'on vient de configurer.
                    nav.navigate(RemoteRoute(id, sharedUrl)) { popUpTo(DevicesRoute) }
                    onSharedUrlConsumed()
                },
                onBack = { if (entry.isResumed) nav.popBackStack() },
            )
        }
        composable<RemoteRoute> { entry ->
            val route = entry.toRoute<RemoteRoute>()
            RemoteScreen(
                deviceId = route.deviceId,
                sharedUrl = route.sharedUrl,
                onBack = { if (entry.isResumed) nav.popBackStack() },
            )
        }
        composable<PlexLinkRoute> { entry ->
            PlexLinkScreen(onDone = { if (entry.isResumed) nav.popBackStack() })
        }
    }
}
