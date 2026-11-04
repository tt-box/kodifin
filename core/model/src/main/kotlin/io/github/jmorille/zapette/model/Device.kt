package io.github.jmorille.zapette.model

import kotlinx.serialization.Serializable

/** Famille de protocole pilotée par l'application. */
@Serializable
enum class DeviceKind(val label: String, val defaultPort: Int) {
    KODI("Kodi", 8080),
    JELLYFIN("Jellyfin", 8096),
    EMBY("Emby", 8096),
    PLEX("Plex", 32500),
    UPNP_RENDERER("UPnP / DLNA", 0),
    CHROMECAST("Chromecast", 8009),
    AIRPLAY("AirPlay", 7000),
    ROKU("Roku", 8060),
    FIRE_TV("Fire TV", 5555),
}

/**
 * Appareil connu (détecté ou saisi). [extras] porte les données propres au protocole
 * (URL de description UPnP, identifiant de session, jeton…).
 */
@Serializable
data class Device(
    val id: String,
    val name: String,
    val kind: DeviceKind,
    val host: String,
    val port: Int = kind.defaultPort,
    val extras: Map<String, String> = emptyMap(),
) {
    val baseUrl: String get() = "http://${hostForUrl()}:$port"

    private fun hostForUrl(): String = if (':' in host && !host.startsWith("[")) "[$host]" else host

    companion object {
        /** Identifiant stable pour un appareil saisi manuellement. */
        fun manualId(kind: DeviceKind, host: String, port: Int): String = "${kind.name}:$host:$port"
    }
}
