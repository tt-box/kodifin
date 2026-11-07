package io.github.ttbox.kodifin

import io.github.ttbox.kodifin.data.LocalShare
import io.github.ttbox.kodifin.data.AndroidLocalShare
import android.app.Application
import android.content.Context
import androidx.datastore.preferences.preferencesDataStoreFile
import io.github.ttbox.kodifin.controller.AndroidPlatformControllers
import io.github.ttbox.kodifin.controller.ControllerFactory
import io.github.ttbox.kodifin.data.DeviceRepository
import io.github.ttbox.kodifin.data.KeystoreSecretStore
import io.github.ttbox.kodifin.data.SettingsRepository
import io.github.ttbox.kodifin.data.SecretStore
import io.github.ttbox.kodifin.discovery.androidDiscoveryManager
import io.github.ttbox.kodifin.model.kodifinHttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.OkHttpClient

/**
 * Conteneur d'injection manuel : pas de framework DI, pas de SDK d'analyse,
 * pas de rapport de plantage. Rien ne quitte le réseau local sauf plex.tv
 * (liaison de compte, à la demande de l'utilisateur).
 */
class AppContainer(context: Context) {
    private val appContext = context.applicationContext

    /** Redirections suivies uniquement vers le même hôte : jetons et mots de passe ne fuient pas. */
    val http: OkHttpClient = kodifinHttpClient()

    /**
     * Portée liée au processus, pour les travaux qui doivent survivre à un écran
     * (fermeture des connexions quand un ViewModel est détruit).
     */
    val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Chiffrement des mots de passe conservés (Kodi), clé non exportable de l'Android Keystore. */
    private val secrets: SecretStore = KeystoreSecretStore()

    /** Même fichier que l'ancien délégué `preferencesDataStore("kodifin")` : aucune donnée perdue. */
    val devices = DeviceRepository(
        DeviceRepository.createStore { appContext.preferencesDataStoreFile("kodifin") },
        secrets,
    )
    /** Réglages globaux (écran allumé, Chromecast), fichier distinct des appareils. */
    val settings = SettingsRepository(
        SettingsRepository.createStore { appContext.preferencesDataStoreFile(SettingsRepository.FILE_NAME) },
    )
    val discovery = androidDiscoveryManager(appContext, http)
    val controllers = ControllerFactory(http, devices, secrets, AndroidPlatformControllers(appContext, settings, http))

    /** Partage de fichiers du téléphone vers les lecteurs du réseau local (serveur HTTP local). */
    val localShare: LocalShare by lazy { AndroidLocalShare(appContext) }
}

class KodifinApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}

val Context.container: AppContainer get() = (applicationContext as KodifinApp).container
