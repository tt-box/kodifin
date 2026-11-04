package io.github.jmorille.zapette.connection

import io.github.jmorille.zapette.mediaserver.MediaServerController
import io.github.jmorille.zapette.model.Device
import io.github.jmorille.zapette.model.DeviceKind

/**
 * Options avancées de connexion saisies dans le formulaire (valeurs textuelles, telles que
 * tapées). Elles sont stockées dans [Device.extras] par [DeviceOptions.apply].
 */
data class AdvancedOptions(
    /** HTTPS au lieu de HTTP (Kodi, Jellyfin, Emby derrière un proxy TLS). */
    val https: Boolean = false,
    /** Kodi : transport JSON-RPC par WebSocket (événements en temps réel) au lieu de HTTP. */
    val kodiWebSocket: Boolean = false,
    val kodiWebSocketPort: String = DeviceOptions.DEFAULT_KODI_WS_PORT.toString(),
    /** Wake-on-LAN : adresse MAC de la machine à réveiller (vide = désactivé). */
    val wolMac: String = "",
    val wolBroadcast: String = DeviceOptions.DEFAULT_BROADCAST,
    val wolPort: String = DeviceOptions.DEFAULT_WOL_PORT.toString(),
    /** Envoie le signal de réveil avant chaque connexion, puis patiente pendant le démarrage. */
    val wolAuto: Boolean = false,
    /** Jellyfin/Emby : chemin de base de l'API (« URL de base » du serveur), ex. `/jellyfin`. */
    val basePath: String = "",
)

/** Destination d'un paquet magique Wake-on-LAN. */
data class WakeTarget(val mac: ByteArray, val broadcast: String, val port: Int) {
    override fun equals(other: Any?) =
        other is WakeTarget && mac.contentEquals(other.mac) && broadcast == other.broadcast && port == other.port

    override fun hashCode() = (mac.contentHashCode() * 31 + broadcast.hashCode()) * 31 + port
}

/** Lecture, validation et application des options avancées (logique pure, testée unitairement). */
object DeviceOptions {
    /** Même clé que la découverte Jellyfin/Emby (`MediaServerDiscovery.EXTRA_SCHEME`). */
    const val SCHEME = "scheme"
    const val KODI_WEBSOCKET = "kodi_ws"
    const val KODI_WEBSOCKET_PORT = "kodi_ws_port"
    const val WOL_MAC = "wol_mac"
    const val WOL_BROADCAST = "wol_broadcast"
    const val WOL_PORT = "wol_port"
    const val WOL_AUTO = "wol_auto"
    /** Même clé que `MediaServerController.EXTRA_BASE_PATH`. */
    const val BASE_PATH = MediaServerController.EXTRA_BASE_PATH

    const val DEFAULT_KODI_WS_PORT = 9090
    const val DEFAULT_WOL_PORT = 9
    const val DEFAULT_BROADCAST = "255.255.255.255"

    private val KEYS = setOf(SCHEME, KODI_WEBSOCKET, KODI_WEBSOCKET_PORT, WOL_MAC, WOL_BROADCAST, WOL_PORT, WOL_AUTO, BASE_PATH)
    private val IPV4 = Regex("""^(25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)(\.(25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)){3}$""")

    fun supportsHttps(kind: DeviceKind) = kind == DeviceKind.KODI || kind == DeviceKind.JELLYFIN || kind == DeviceKind.EMBY
    fun supportsWebSocket(kind: DeviceKind) = kind == DeviceKind.KODI
    fun supportsBasePath(kind: DeviceKind) = kind == DeviceKind.JELLYFIN || kind == DeviceKind.EMBY

    /** Chromecast se réveille par le réseau Cast ; tous les autres peuvent être une machine à allumer. */
    fun supportsWakeOnLan(kind: DeviceKind) = kind != DeviceKind.CHROMECAST

    /** Options d'un appareil existant (valeurs par défaut si [device] est `null`). */
    fun read(device: Device?): AdvancedOptions {
        val e = device?.extras ?: return AdvancedOptions()
        return AdvancedOptions(
            https = e[SCHEME] == "https",
            kodiWebSocket = e[KODI_WEBSOCKET] == "true",
            kodiWebSocketPort = e[KODI_WEBSOCKET_PORT] ?: DEFAULT_KODI_WS_PORT.toString(),
            wolMac = e[WOL_MAC].orEmpty(),
            wolBroadcast = e[WOL_BROADCAST] ?: DEFAULT_BROADCAST,
            wolPort = e[WOL_PORT] ?: DEFAULT_WOL_PORT.toString(),
            wolAuto = e[WOL_AUTO] == "true",
            basePath = e[BASE_PATH].orEmpty(),
        )
    }

    /** Message d'erreur pour des options invalides, ou `null`. */
    fun error(kind: DeviceKind, o: AdvancedOptions): String? = when {
        supportsWebSocket(kind) && o.kodiWebSocket && o.kodiWebSocketPort.toIntOrNull() !in 1..65535 ->
            "Port WebSocket invalide"
        supportsBasePath(kind) && o.basePath.isNotBlank() && MediaServerController.normalizeBasePath(o.basePath) == null ->
            "Chemin de base invalide (ex. /jellyfin)"
        !supportsWakeOnLan(kind) -> null
        o.wolMac.isBlank() && o.wolAuto -> "Adresse MAC requise pour le réveil automatique"
        o.wolMac.isBlank() -> null
        WakeOnLan.parseMac(o.wolMac) == null -> "Adresse MAC invalide (ex. 58:47:CA:7E:0D:48)"
        !IPV4.matches(o.wolBroadcast.trim()) -> "Adresse de diffusion invalide (IPv4, ex. 192.168.1.255)"
        o.wolPort.toIntOrNull() !in 1..65535 -> "Port Wake-on-LAN invalide"
        else -> null
    }

    /**
     * Extras de l'appareil avec les options appliquées : les clés d'options sont réécrites,
     * seules les options utiles au type [kind] et différentes du défaut sont conservées.
     */
    fun apply(extras: Map<String, String>, kind: DeviceKind, o: AdvancedOptions): Map<String, String> = buildMap {
        putAll(extras - KEYS)
        if (supportsHttps(kind) && o.https) put(SCHEME, "https")
        if (supportsBasePath(kind)) MediaServerController.normalizeBasePath(o.basePath)?.let { put(BASE_PATH, it) }
        if (supportsWebSocket(kind) && o.kodiWebSocket) {
            put(KODI_WEBSOCKET, "true")
            put(KODI_WEBSOCKET_PORT, o.kodiWebSocketPort.trim())
        }
        val mac = WakeOnLan.parseMac(o.wolMac)
        if (supportsWakeOnLan(kind) && mac != null) {
            put(WOL_MAC, WakeOnLan.formatMac(mac))
            if (o.wolBroadcast.trim() != DEFAULT_BROADCAST) put(WOL_BROADCAST, o.wolBroadcast.trim())
            if (o.wolPort.trim() != DEFAULT_WOL_PORT.toString()) put(WOL_PORT, o.wolPort.trim())
            if (o.wolAuto) put(WOL_AUTO, "true")
        }
    }

    /** URL HTTP(S) de base, selon l'option HTTPS. */
    fun httpBaseUrl(device: Device): String =
        if (device.extras[SCHEME] == "https") "https" + device.baseUrl.removePrefix("http") else device.baseUrl

    /** URL WebSocket JSON-RPC de Kodi si l'option est activée, sinon `null`. */
    fun kodiWebSocketUrl(device: Device): String? {
        if (device.kind != DeviceKind.KODI || device.extras[KODI_WEBSOCKET] != "true") return null
        val port = device.extras[KODI_WEBSOCKET_PORT]?.toIntOrNull()?.takeIf { it in 1..65535 } ?: DEFAULT_KODI_WS_PORT
        val scheme = if (device.extras[SCHEME] == "https") "wss" else "ws"
        val host = if (':' in device.host && !device.host.startsWith("[")) "[${device.host}]" else device.host
        return "$scheme://$host:$port/jsonrpc"
    }

    /** Cible Wake-on-LAN de l'appareil, ou `null` si aucune adresse MAC valide n'est configurée. */
    fun wakeTarget(device: Device?): WakeTarget? {
        if (device == null || !supportsWakeOnLan(device.kind)) return null
        val mac = WakeOnLan.parseMac(device.extras[WOL_MAC].orEmpty()) ?: return null
        val broadcast = device.extras[WOL_BROADCAST]?.trim()?.takeIf(IPV4::matches) ?: DEFAULT_BROADCAST
        val port = device.extras[WOL_PORT]?.toIntOrNull()?.takeIf { it in 1..65535 } ?: DEFAULT_WOL_PORT
        return WakeTarget(mac, broadcast, port)
    }

    /** Réveil automatique demandé (et possible) avant la connexion. */
    fun autoWake(device: Device): Boolean = device.extras[WOL_AUTO] == "true" && wakeTarget(device) != null
}
