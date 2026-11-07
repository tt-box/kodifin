package io.github.ttbox.kodifin.discovery

import android.content.Context
import android.net.nsd.NsdManager
import android.net.wifi.WifiManager
import io.github.ttbox.kodifin.cast.castDevices
import io.github.ttbox.kodifin.mediaserver.MediaServerDiscovery
import io.github.ttbox.kodifin.mediaserver.MediaServerFlavor
import io.github.ttbox.kodifin.model.Device
import io.github.ttbox.kodifin.model.fetchString
import io.github.ttbox.kodifin.plex.PlexController
import io.github.ttbox.kodifin.roku.RokuController
import io.github.ttbox.kodifin.model.Ssdp
import io.github.ttbox.kodifin.upnp.UpnpRendererController
import io.github.ttbox.kodifin.upnp.UpnpXml
import kotlinx.coroutines.sync.Mutex
import okhttp3.OkHttpClient
import okhttp3.Request

/*
 * Câblage Android de la découverte (NsdManager, WifiManager, MediaRouter) : adaptateur mince,
 * exclu de la couverture. La logique testable reste dans DiscoveryManager et les sources.
 */

/** Gestionnaire de découverte branché sur les services réels d'Android. */
fun androidDiscoveryManager(context: Context, http: OkHttpClient): DiscoveryManager = DiscoveryManager(
    sources = androidSources(context, http),
    // Contexte applicatif : un WifiManager obtenu depuis une activité la ferait fuir.
    multicastLock = {
        context.applicationContext.getSystemService(WifiManager::class.java)
            ?.createMulticastLock("kodifin-discovery")
            ?.apply { setReferenceCounted(false) }
            ?.let(::AndroidMulticastLock)
    },
)

/** Sources réelles, une par protocole. */
private fun androidSources(
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
