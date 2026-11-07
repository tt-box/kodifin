package io.github.ttbox.kodifin.discovery

import io.github.ttbox.kodifin.model.Device
import kotlinx.coroutines.flow.Flow

/**
 * Agrège toutes les sources de découverte locales : mDNS (Kodi HTTP et TCP/WebSocket, AirPlay,
 * Fire TV, Android TV Remote, débogage ADB sans fil),
 * SSDP IPv4 et IPv6 (renderers UPnP, Roku), UDP 7359 (Jellyfin/Emby), GDM (Plex) et MediaRouter (Cast).
 *
 * La logique (fusion, périodicité, état NSD) est dans [mergeSources], [periodic] et
 * [NsdSource], testés sans Android ; les sources réelles sont créées par
 * [androidDiscoveryManager] (AndroidDiscovery.kt).
 */
class DiscoveryManager internal constructor(
    private val sources: List<DiscoverySource>,
    private val multicastLock: () -> MulticastLockPort?,
) {
    fun scan(): Flow<List<Device>> = mergeSources(sources, multicastLock)
}
