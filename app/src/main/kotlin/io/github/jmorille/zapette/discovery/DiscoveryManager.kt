package io.github.jmorille.zapette.discovery

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
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
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.ConcurrentHashMap
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

        val lock = context.getSystemService(WifiManager::class.java)
            ?.createMulticastLock("zapette-discovery")
            ?.apply { setReferenceCounted(false); acquire() }

        NsdMapping.serviceTypes.forEach { type ->
            launch { nsdDevices(type).catch { emit(emptyList()) }.collect { update(type, it) } }
        }
        launch { castDevices(context).catch { emit(emptyList()) }.collect { update("cast", it) } }
        periodic("upnp") { upnpRenderers() }.let { flow -> launch { flow.collect { update("upnp", it) } } }
        periodic("roku") { Ssdp.search(Ssdp.ST_ROKU).mapNotNull(RokuController::deviceFrom) }
            .let { flow -> launch { flow.collect { update("roku", it) } } }
        periodic("jellyfin") { MediaServerDiscovery.discover(MediaServerFlavor.JELLYFIN) }
            .let { flow -> launch { flow.collect { update("jellyfin", it) } } }
        periodic("emby") { MediaServerDiscovery.discover(MediaServerFlavor.EMBY) }
            .let { flow -> launch { flow.collect { update("emby", it) } } }
        periodic("plex") { PlexController.discover() }.let { flow -> launch { flow.collect { update("plex", it) } } }

        awaitClose { runCatching { lock?.release() } }
    }

    /** Relance une découverte ponctuelle toutes les 15 s tant que l'écran est affiché. */
    private fun periodic(@Suppress("UNUSED_PARAMETER") name: String, block: suspend () -> List<Device>): Flow<List<Device>> = flow {
        while (true) {
            emit(runCatching { block() }.getOrDefault(emptyList()))
            delay(15_000)
        }
    }

    private suspend fun upnpRenderers(): List<Device> = Ssdp.search(Ssdp.ST_MEDIA_RENDERER).mapNotNull { response ->
        val location = response.location ?: return@mapNotNull null
        runCatching {
            val xml = http.fetchString(Request.Builder().url(location).build())
            UpnpRendererController.deviceFrom(response, UpnpXml.parseDescription(xml, location))
        }.getOrNull()
    }

    private fun nsdDevices(serviceType: String): Flow<List<Device>> = callbackFlow {
        val found = ConcurrentHashMap<String, Device>()
        val listener = object : NsdManager.DiscoveryListener {
            override fun onServiceFound(info: NsdServiceInfo) {
                launch {
                    val resolved = resolve(info) ?: return@launch
                    @Suppress("DEPRECATION")
                    val host = resolved.host?.hostAddress.orEmpty()
                    NsdMapping.toDevice(serviceType, resolved.serviceName, host, resolved.port)?.let {
                        found[info.serviceName] = it
                        trySend(found.values.toList())
                    }
                }
            }

            override fun onServiceLost(info: NsdServiceInfo) {
                found.remove(info.serviceName)
                trySend(found.values.toList())
            }

            override fun onDiscoveryStarted(serviceType: String) = Unit
            override fun onDiscoveryStopped(serviceType: String) = Unit
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) { close() }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
        }
        nsd.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, listener)
        awaitClose { runCatching { nsd.stopServiceDiscovery(listener) } }
    }

    /** `resolveService` n'accepte qu'une résolution à la fois avant Android 14 : on sérialise. */
    @Suppress("DEPRECATION")
    private suspend fun resolve(info: NsdServiceInfo): NsdServiceInfo? = resolveLock.withLock {
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
