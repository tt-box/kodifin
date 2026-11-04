package io.github.jmorille.zapette.discovery

import android.content.Context
import android.net.nsd.NsdManager
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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Agrège toutes les sources de découverte locales : mDNS (Kodi HTTP et TCP/WebSocket, AirPlay,
 * Fire TV, Android TV Remote, débogage ADB sans fil),
 * SSDP IPv4 et IPv6 (renderers UPnP, Roku), UDP 7359 (Jellyfin/Emby), GDM (Plex) et MediaRouter (Cast).
 *
 * La logique (fusion, périodicité, état NSD) est dans [mergeSources], [periodic] et
 * [NsdSource], testés sans Android ; cette classe ne fait que le câblage.
 */
class DiscoveryManager internal constructor(
    private val sources: List<DiscoverySource>,
    private val multicastLock: () -> MulticastLockPort?,
) {

    constructor(context: Context, http: OkHttpClient) : this(
        sources = defaultSources(context, http),
        // Contexte applicatif : un WifiManager obtenu depuis une activité la ferait fuir.
        multicastLock = {
            context.applicationContext.getSystemService(WifiManager::class.java)
                ?.createMulticastLock("zapette-discovery")
                ?.apply { setReferenceCounted(false) }
                ?.let(::AndroidMulticastLock)
        },
    )

    fun scan(): Flow<List<Device>> = mergeSources(sources, multicastLock)

    internal companion object {
        /** Sources réelles, une par protocole. */
        fun defaultSources(
            context: Context,
            http: OkHttpClient,
            periodMs: Long = DEFAULT_PERIOD_MS,
            resolveTimeoutMs: Long = DEFAULT_RESOLVE_TIMEOUT_MS,
        ): List<DiscoverySource> {
            val nsd: NsdManager? = context.getSystemService(NsdManager::class.java)
            // Une seule résolution NSD à la fois pour tout le processus (avant Android 14).
            val resolveLock = Mutex()
            val nsdSources = nsd?.let { manager ->
                val backend = AndroidNsdBackend(manager)
                NsdMapping.serviceTypes.map { NsdSource(backend, it, resolveLock, resolveTimeoutMs) }
            }.orEmpty()
            return nsdSources + listOf(
                DiscoverySource { castDevices(context) },
                periodicSource(periodMs) { upnpRenderers(http) },
                periodicSource(periodMs) { Ssdp.search(Ssdp.ST_ROKU, ipv6 = true).mapNotNull(RokuController::deviceFrom) },
                periodicSource(periodMs) { MediaServerDiscovery.discover(MediaServerFlavor.JELLYFIN) },
                periodicSource(periodMs) { MediaServerDiscovery.discover(MediaServerFlavor.EMBY) },
                periodicSource(periodMs) { PlexController.discover() },
            )
        }

        private suspend fun upnpRenderers(http: OkHttpClient): List<Device> =
            Ssdp.search(Ssdp.ST_MEDIA_RENDERER, ipv6 = true).mapNotNull { response ->
                val location = response.location ?: return@mapNotNull null
                attempt {
                    val xml = http.fetchString(Request.Builder().url(location).build())
                    UpnpRendererController.deviceFrom(response, UpnpXml.parseDescription(xml, location))
                }
            }
    }
}
