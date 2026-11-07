package io.github.ttbox.kodifin.ui

import io.github.ttbox.kodifin.ui.bluetooth.BluetoothKeyboardScreen
import io.github.ttbox.kodifin.ui.adb.AdbPairingScreen
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.ttbox.kodifin.container
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
import io.github.ttbox.kodifin.ui.config.DeviceConfigScreen
import io.github.ttbox.kodifin.ui.devices.DevicesScreen
import io.github.ttbox.kodifin.ui.plex.PlexLinkScreen
import io.github.ttbox.kodifin.ui.remote.RemoteScreen
import io.github.ttbox.kodifin.ui.settings.SettingsScreen
import io.github.ttbox.kodifin.ui.transfer.TransferScreen
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

/** Modification d'un appareil enregistré, chargé par son identifiant (secrets hors de la route). */
@Serializable
data class EditRoute(val deviceId: String)

@Serializable
data class RemoteRoute(val deviceId: String, val sharedUrl: String? = null)

@Serializable
object PlexLinkRoute

/** Appairage ADB en débogage sans fil (Android 11+). */
@Serializable
object AdbPairingRoute

/** Essai du pilotage par Bluetooth (la tablette se déclare comme clavier). */
@Serializable
object BluetoothKeyboardRoute

/** Réglages globaux (écran allumé, Chromecast). */
@Serializable
object SettingsRoute

/** Export / import des appareils ; [deviceId] : appareil présélectionné pour le QR code. */
@Serializable
data class TransferRoute(val deviceId: String? = null)

/**
 * Vrai si l'écran est au premier plan et stable : ignore les doubles appuis pendant une
 * transition (sinon deux « Retour » rapides dépilent aussi l'écran d'accueil et laissent
 * un écran vide, et deux appuis sur un appareil empilent deux télécommandes).
 */
private val NavBackStackEntry.isResumed: Boolean
    get() = lifecycle.currentState == Lifecycle.State.RESUMED

@Composable
fun KodifinNavHost(sharedUrl: String?, onSharedUrlConsumed: () -> Unit) {
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
                onEdit = { id -> if (entry.isResumed) nav.navigate(EditRoute(id)) },
                onPlexLink = { if (entry.isResumed) nav.navigate(PlexLinkRoute) },
                onSettings = { if (entry.isResumed) nav.navigate(SettingsRoute) },
                onTransfer = { id -> if (entry.isResumed) nav.navigate(TransferRoute(id)) },
                onBluetooth = { if (entry.isResumed) nav.navigate(BluetoothKeyboardRoute) },
            )
        }
        composable<BluetoothKeyboardRoute> { entry ->
            BluetoothKeyboardScreen(onBack = { if (entry.isResumed) nav.popBackStack() })
        }
        composable<ConfigRoute> { entry ->
            DeviceConfigScreen(
                prefillJson = entry.toRoute<ConfigRoute>().deviceJson,
                onAdbPairing = { if (entry.isResumed) nav.navigate(AdbPairingRoute) },
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
        composable<EditRoute> { entry ->
            DeviceConfigScreen(
                prefillJson = null,
                editDeviceId = entry.toRoute<EditRoute>().deviceId,
                onAdbPairing = { if (entry.isResumed) nav.navigate(AdbPairingRoute) },
                // Nouvelle télécommande : la connexion repart avec la configuration modifiée.
                onSaved = { id -> nav.navigate(RemoteRoute(id)) { popUpTo(DevicesRoute) } },
                onBack = { if (entry.isResumed) nav.popBackStack() },
            )
        }
        composable<RemoteRoute> { entry ->
            val route = entry.toRoute<RemoteRoute>()
            val keepScreenOn by LocalContext.current.container.settings.keepScreenOn.collectAsStateWithLifecycle(false)
            RemoteScreen(
                keepScreenOn = keepScreenOn,
                onEdit = { if (entry.isResumed) nav.navigate(EditRoute(route.deviceId)) },
                // Autre télécommande : remplace celle-ci (Retour ramène toujours à l'accueil).
                onSwitch = { id ->
                    if (entry.isResumed && id != route.deviceId) nav.navigate(RemoteRoute(id)) { popUpTo(DevicesRoute) }
                },
                deviceId = route.deviceId,
                sharedUrl = route.sharedUrl,
                onBack = { if (entry.isResumed) nav.popBackStack() },
            )
        }
        composable<AdbPairingRoute> { entry ->
            AdbPairingScreen(
                onPaired = { id ->
                    nav.navigate(RemoteRoute(id, sharedUrl)) { popUpTo(DevicesRoute) }
                    onSharedUrlConsumed()
                },
                onBack = { if (entry.isResumed) nav.popBackStack() },
            )
        }
        composable<SettingsRoute> { entry ->
            SettingsScreen(store = LocalContext.current.container.settings, onBack = { if (entry.isResumed) nav.popBackStack() })
        }
        composable<TransferRoute> { entry ->
            TransferScreen(
                deviceId = entry.toRoute<TransferRoute>().deviceId,
                onBack = { if (entry.isResumed) nav.popBackStack() },
            )
        }
        composable<PlexLinkRoute> { entry ->
            PlexLinkScreen(onDone = { if (entry.isResumed) nav.popBackStack() })
        }
    }
}
