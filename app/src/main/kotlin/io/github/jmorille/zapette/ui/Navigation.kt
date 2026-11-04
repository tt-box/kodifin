package io.github.jmorille.zapette.ui

import androidx.compose.runtime.Composable
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

/** [deviceJson] : appareil pré-rempli (découvert) au format JSON, ou `null` pour une saisie manuelle. */
@Serializable
data class ConfigRoute(val deviceJson: String? = null)

@Serializable
data class RemoteRoute(val deviceId: String, val sharedUrl: String? = null)

@Serializable
object PlexLinkRoute

@Composable
fun ZapetteNavHost(sharedUrl: String?, onSharedUrlConsumed: () -> Unit) {
    val nav = rememberNavController()
    NavHost(navController = nav, startDestination = DevicesRoute) {
        composable<DevicesRoute> {
            DevicesScreen(
                sharedUrl = sharedUrl,
                onOpen = { id ->
                    nav.navigate(RemoteRoute(id, sharedUrl))
                    onSharedUrlConsumed()
                },
                onConfigure = { json -> nav.navigate(ConfigRoute(json)) },
                onPlexLink = { nav.navigate(PlexLinkRoute) },
            )
        }
        composable<ConfigRoute> { entry ->
            DeviceConfigScreen(
                prefillJson = entry.toRoute<ConfigRoute>().deviceJson,
                onSaved = { id ->
                    nav.navigate(RemoteRoute(id)) { popUpTo(DevicesRoute) }
                },
                onBack = { nav.popBackStack() },
            )
        }
        composable<RemoteRoute> { entry ->
            val route = entry.toRoute<RemoteRoute>()
            RemoteScreen(deviceId = route.deviceId, sharedUrl = route.sharedUrl, onBack = { nav.popBackStack() })
        }
        composable<PlexLinkRoute> {
            PlexLinkScreen(onDone = { nav.popBackStack() })
        }
    }
}
