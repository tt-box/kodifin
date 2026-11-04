package io.github.jmorille.zapette.discovery

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import androidx.annotation.RequiresApi
import io.github.jmorille.zapette.cast.castDevices
import io.github.jmorille.zapette.mediaserver.MediaServerDiscovery
import io.github.jmorille.zapette.mediaserver.MediaServerFlavor
import io.github.jmorille.zapette.model.Device
import io.github.jmorille.zapette.model.fetchString
import io.github.jmorille.zapette.plex.PlexController
import io.github.jmorille.zapette.roku.RokuController
import io.github.jmorille.zapette.upnp.Ssdp
import io.github.jmorille.zapette.upnp.UpnpRendererController
import io.github.jmorille.zapette.upnp.UpnpXml
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.resume

/**
 * Agrège toutes les sources de découverte locales : mDNS (Kodi, AirPlay, Fire TV),
 * SSDP (renderers UPnP, Roku), UDP 7359 (Jellyfin/Emby), GDM (Plex) et MediaRouter (Cast).
 */
class DiscoveryManager(private val context: Context, private val http: OkHttpClient) {

    private val nsd = context.getSystemService(NsdManager::class.java)
    private val resolveLock = Mutex()

    fun scan(): Flow<List<Device>> = channelFlow {
        val bySource = ConcurrentHashMap<String, List<Device>>()
        suspend fun update(source: String, devices: List<Device>) {
            bySource[source] = devices
            send(bySource.values.flatten().distinctBy { it.id }.sortedBy { it.name.lowercase() })
        }

        // Contexte applicatif : un WifiManager obtenu depuis une activité la ferait fuir.
        val lock = context.applicationContext.getSystemService(WifiManager::class.java)
            ?.createMulticastLock("zapette-discovery")
            ?.apply { setReferenceCounted(false) }
        try {
            lock?.acquire()

            NsdMapping.serviceTypes.forEach { type ->
                launch { nsdDevices(type).catch { emit(emptyList()) }.collect { update(type, it) } }
            }
            launch { castDevices(context).catch { emit(emptyList()) }.collect { update("cast", it) } }
            launch { periodic { upnpRenderers() }.collect { update("upnp", it) } }
            launch {
                periodic { Ssdp.search(Ssdp.ST_ROKU).mapNotNull(RokuController::deviceFrom) }.collect { update("roku", it) }
            }
            launch {
                periodic { MediaServerDiscovery.discover(MediaServerFlavor.JELLYFIN) }.collect { update("jellyfin", it) }
            }
            launch { periodic { MediaServerDiscovery.discover(MediaServerFlavor.EMBY) }.collect { update("emby", it) } }
            launch { periodic { PlexController.discover() }.collect { update("plex", it) } }

            awaitClose()
        } finally {
            // Libéré aussi si le flux échoue ou est annulé avant awaitClose (batterie).
            runCatching { if (lock?.isHeld == true) lock.release() }
        }
    }

    /** Relance une découverte ponctuelle toutes les 15 s tant que l'écran est affiché. */
    private fun periodic(block: suspend () -> List<Device>): Flow<List<Device>> = flow {
        while (true) {
            emit(attempt { block() } ?: emptyList())
            delay(PERIOD_MS)
        }
    }

    private suspend fun upnpRenderers(): List<Device> = Ssdp.search(Ssdp.ST_MEDIA_RENDERER).mapNotNull { response ->
        val location = response.location ?: return@mapNotNull null
        attempt {
            val xml = http.fetchString(Request.Builder().url(location).build())
            UpnpRendererController.deviceFrom(response, UpnpXml.parseDescription(xml, location))
        }
    }

    private fun nsdDevices(serviceType: String): Flow<List<Device>> = callbackFlow {
        val found = ConcurrentHashMap<String, Device>()
        // Services annoncés et non perdus : une résolution tardive ne ressuscite pas un service disparu.
        val present = ConcurrentHashMap.newKeySet<String>()
        val watchers = ConcurrentHashMap<String, AutoCloseable>()

        fun publish() {
            trySend(found.values.toList())
        }

        fun resolved(name: String, info: NsdServiceInfo, addresses: List<java.net.InetAddress>) {
            if (name !in present) return
            val host = NsdMapping.preferredHost(addresses)
            val device = host?.let { NsdMapping.toDevice(serviceType, info.serviceName ?: name, it, info.port) }
            if (device != null) found[name] = device else found.remove(name)
            publish()
        }

        fun lost(name: String) {
            present.remove(name)
            watchers.remove(name)?.let { runCatching { it.close() } }
            found.remove(name)
            publish()
        }

        val listener = object : NsdManager.DiscoveryListener {
            override fun onServiceFound(info: NsdServiceInfo) {
                val name = info.serviceName ?: return
                if (!present.add(name)) return
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    // Android 14+ : suivi continu (changement d'adresse) sans la limite d'une résolution à la fois.
                    runCatching {
                        watchers[name] = Api34.watch(
                            nsd, info,
                            onUpdate = { resolved(name, it, Api34.addresses(it)) },
                            onLost = { lost(name) },
                        )
                    }
                } else {
                    launch {
                        val r = resolveLegacy(info) ?: return@launch
                        @Suppress("DEPRECATION")
                        resolved(name, r, listOfNotNull(r.host))
                    }
                }
            }

            override fun onServiceLost(info: NsdServiceInfo) {
                lost(info.serviceName ?: return)
            }

            override fun onDiscoveryStarted(serviceType: String) = Unit
            override fun onDiscoveryStopped(serviceType: String) = Unit
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                close()
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
        }
        nsd.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, listener)
        awaitClose {
            runCatching { nsd.stopServiceDiscovery(listener) }
            watchers.values.forEach { runCatching { it.close() } }
            watchers.clear()
        }
    }

    /**
     * Avant Android 14, `resolveService` n'accepte qu'une résolution à la fois : on sérialise.
     * Le délai évite qu'une résolution sans réponse bloque toutes les suivantes.
     */
    @Suppress("DEPRECATION")
    private suspend fun resolveLegacy(info: NsdServiceInfo): NsdServiceInfo? = resolveLock.withLock {
        withTimeoutOrNull(RESOLVE_TIMEOUT_MS) {
            suspendCancellableCoroutine { cont ->
                nsd.resolveService(info, object : NsdManager.ResolveListener {
                    override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                        if (cont.isActive) cont.resume(serviceInfo)
                    }

                    override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                        if (cont.isActive) cont.resume(null)
                    }
                })
            }
        }
    }

    /** API Android 14+ isolée pour ne pas charger ces classes sur les versions antérieures. */
    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private object Api34 {
        private val direct = Executor { it.run() }

        fun addresses(info: NsdServiceInfo): List<java.net.InetAddress> = info.hostAddresses

        fun watch(
            nsd: NsdManager,
            info: NsdServiceInfo,
            onUpdate: (NsdServiceInfo) -> Unit,
            onLost: () -> Unit,
        ): AutoCloseable {
            val callback = object : NsdManager.ServiceInfoCallback {
                override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) = Unit
                override fun onServiceUpdated(serviceInfo: NsdServiceInfo) = onUpdate(serviceInfo)
                override fun onServiceLost() = onLost()
                override fun onServiceInfoCallbackUnregistered() = Unit
            }
            nsd.registerServiceInfoCallback(info, direct, callback)
            return AutoCloseable { runCatching { nsd.unregisterServiceInfoCallback(callback) } }
        }
    }

    private companion object {
        const val PERIOD_MS = 15_000L
        const val RESOLVE_TIMEOUT_MS = 10_000L

        /** Comme runCatching, sans avaler l'annulation de la coroutine (sinon la boucle survit à l'écran). */
        suspend fun <T> attempt(block: suspend () -> T): T? = try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }
}
