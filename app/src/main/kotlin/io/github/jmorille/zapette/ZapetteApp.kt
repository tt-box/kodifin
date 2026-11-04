package io.github.jmorille.zapette

import android.app.Application
import android.content.Context
import io.github.jmorille.zapette.controller.ControllerFactory
import io.github.jmorille.zapette.data.DeviceRepository
import io.github.jmorille.zapette.discovery.DiscoveryManager
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

    val devices = DeviceRepository(appContext)
    val discovery = DiscoveryManager(appContext, http)
    val controllers = ControllerFactory(appContext, http, devices)
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
