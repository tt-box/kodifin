package io.github.jmorille.zapette

import android.app.Application
import android.content.Context
import androidx.datastore.preferences.preferencesDataStoreFile
import io.github.jmorille.zapette.controller.AndroidPlatformControllers
import io.github.jmorille.zapette.controller.ControllerFactory
import io.github.jmorille.zapette.data.DeviceRepository
import io.github.jmorille.zapette.data.KeystoreSecretStore
import io.github.jmorille.zapette.data.SecretStore
import io.github.jmorille.zapette.discovery.DiscoveryManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Conteneur d'injection manuel : pas de framework DI, pas de SDK d'analyse,
 * pas de rapport de plantage. Rien ne quitte le réseau local sauf plex.tv
 * (liaison de compte, à la demande de l'utilisateur).
 */
class AppContainer(context: Context) {
    private val appContext = context.applicationContext

    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .build()

    /**
     * Portée liée au processus, pour les travaux qui doivent survivre à un écran
     * (fermeture des connexions quand un ViewModel est détruit).
     */
    val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Chiffrement des mots de passe conservés (Kodi), clé non exportable de l'Android Keystore. */
    private val secrets: SecretStore = KeystoreSecretStore()

    /** Même fichier que l'ancien délégué `preferencesDataStore("zapette")` : aucune donnée perdue. */
    val devices = DeviceRepository(
        DeviceRepository.createStore { appContext.preferencesDataStoreFile("zapette") },
        secrets,
    )
    val discovery = DiscoveryManager(appContext, http)
    val controllers = ControllerFactory(http, devices, secrets, AndroidPlatformControllers(appContext))
}

class ZapetteApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}

val Context.container: AppContainer get() = (applicationContext as ZapetteApp).container
