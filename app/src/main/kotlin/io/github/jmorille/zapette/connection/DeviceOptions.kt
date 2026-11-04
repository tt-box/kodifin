package io.github.jmorille.zapette.connection

import io.github.jmorille.zapette.mediaserver.MediaServerController
import io.github.jmorille.zapette.model.Device
import io.github.jmorille.zapette.model.DeviceKind
import io.github.jmorille.zapette.model.DEFAULT_CONNECT_TIMEOUT_S
import io.github.jmorille.zapette.model.MAX_CONNECT_TIMEOUT_S
import io.github.jmorille.zapette.model.MIN_CONNECT_TIMEOUT_S

/** Mode de connexion HTTP d'un appareil. */
enum class ConnectionMode(val label: String) {
    HTTP("HTTP"),
    HTTPS("HTTPS"),

    /** Certificat auto-signé accepté sans vérification (choix explicite, risque d'interception). */
    HTTPS_INSECURE("HTTPS – certificat non vérifié (auto-signé)"),
    ;

    val secure: Boolean get() = this != HTTP
}

/**
 * Options avancées de connexion saisies dans le formulaire (valeurs textuelles, telles que
 * tapées). Elles sont stockées dans [Device.extras] par [DeviceOptions.apply].
 */
data class AdvancedOptions(
    /** HTTP, HTTPS ou HTTPS sans vérification du certificat (Kodi, Jellyfin, Emby, Plex). */
    val mode: ConnectionMode = ConnectionMode.HTTP,
    /** Délai de connexion en secondes (1 à 60), tel que saisi. */
    val timeoutS: String = DEFAULT_CONNECT_TIMEOUT_S.toString(),
    /** Connexion autorisée uniquement en Wi-Fi. */
    val wifiOnly: Boolean = false,
    /** Réseaux Wi-Fi (SSID) autorisés, un par ligne ; vide = tous. */
    val wifiSsids: String = "",
    /** Kodi : Event Server UDP (comportement fourni par un autre module). */
    val kodiEventServer: Boolean = false,
    val kodiEventServerPort: String = DeviceOptions.DEFAULT_KODI_EVENTSERVER_PORT.toString(),
    /** UPnP/DLNA : métadonnées DIDL-Lite alternatives pour les renderers capricieux. */
    val upnpAltDidl: Boolean = false,
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
) {
    /** Compatibilité avec l'ancien interrupteur « HTTPS ». */
    val https: Boolean get() = mode.secure
}

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
    /** `"true"` : certificat TLS non vérifié (avec [SCHEME] = `https`). */
    const val TLS_INSECURE = "tls_insecure"
    const val TIMEOUT = "timeout_s"
    const val WIFI_ONLY = "wifi_only"
    /** SSID autorisés, séparés par des retours à la ligne. */
    const val WIFI_SSIDS = "wifi_ssids"
    const val KODI_EVENTSERVER = "kodi_eventserver"
    const val KODI_EVENTSERVER_PORT = "kodi_eventserver_port"
    const val UPNP_ALT_DIDL = "upnp_alt_didl"
    const val KODI_WEBSOCKET = "kodi_ws"
    const val KODI_WEBSOCKET_PORT = "kodi_ws_port"
    const val WOL_MAC = "wol_mac"
    const val WOL_BROADCAST = "wol_broadcast"
    const val WOL_PORT = "wol_port"
    const val WOL_AUTO = "wol_auto"
    /** Même clé que `MediaServerController.EXTRA_BASE_PATH`. */
    const val BASE_PATH = MediaServerController.EXTRA_BASE_PATH

    const val DEFAULT_KODI_WS_PORT = 9090
    const val DEFAULT_KODI_EVENTSERVER_PORT = 9777
    /** Un SSID fait au plus 32 octets. */
    private const val MAX_SSID_BYTES = 32
    const val DEFAULT_WOL_PORT = 9
    const val DEFAULT_BROADCAST = "255.255.255.255"

    private val KEYS = setOf(
        SCHEME, TLS_INSECURE, TIMEOUT, WIFI_ONLY, WIFI_SSIDS, KODI_EVENTSERVER, KODI_EVENTSERVER_PORT, UPNP_ALT_DIDL,
        KODI_WEBSOCKET, KODI_WEBSOCKET_PORT, WOL_MAC, WOL_BROADCAST, WOL_PORT, WOL_AUTO, BASE_PATH,
    )
    private val IPV4 = Regex("""^(25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)(\.(25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)){3}$""")

    fun supportsHttps(kind: DeviceKind) =
        kind == DeviceKind.KODI || kind == DeviceKind.JELLYFIN || kind == DeviceKind.EMBY || kind == DeviceKind.PLEX

    /** Chromecast passe par le SDK Cast : ni délai ni restriction réseau propres. */
    fun supportsTimeout(kind: DeviceKind) = kind != DeviceKind.CHROMECAST
    fun supportsNetworkRestriction(kind: DeviceKind) = kind != DeviceKind.CHROMECAST
    fun supportsEventServer(kind: DeviceKind) = kind == DeviceKind.KODI
    fun supportsAltDidl(kind: DeviceKind) = kind == DeviceKind.UPNP_RENDERER
    fun supportsWebSocket(kind: DeviceKind) = kind == DeviceKind.KODI
    fun supportsBasePath(kind: DeviceKind) = kind == DeviceKind.JELLYFIN || kind == DeviceKind.EMBY

    /** Chromecast se réveille par le réseau Cast ; tous les autres peuvent être une machine à allumer. */
    fun supportsWakeOnLan(kind: DeviceKind) = kind != DeviceKind.CHROMECAST

    /** Options d'un appareil existant (valeurs par défaut si [device] est `null`). */
    fun read(device: Device?): AdvancedOptions {
        val e = device?.extras ?: return AdvancedOptions()
        return AdvancedOptions(
            mode = connectionMode(device),
            timeoutS = timeoutSeconds(device).toString(),
            wifiOnly = e[WIFI_ONLY] == "true",
            wifiSsids = parseSsids(e[WIFI_SSIDS].orEmpty()).joinToString("\n"),
            kodiEventServer = e[KODI_EVENTSERVER] == "true",
            kodiEventServerPort = e[KODI_EVENTSERVER_PORT] ?: DEFAULT_KODI_EVENTSERVER_PORT.toString(),
            upnpAltDidl = e[UPNP_ALT_DIDL] == "true",
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
        supportsTimeout(kind) && o.timeoutS.trim().toIntOrNull() !in MIN_CONNECT_TIMEOUT_S..MAX_CONNECT_TIMEOUT_S ->
            "Délai de connexion invalide (de $MIN_CONNECT_TIMEOUT_S à $MAX_CONNECT_TIMEOUT_S s)"
        supportsNetworkRestriction(kind) && parseSsids(o.wifiSsids).any { it.toByteArray(Charsets.UTF_8).size > MAX_SSID_BYTES } ->
            "Nom de réseau Wi-Fi trop long (32 octets au plus)"
        supportsEventServer(kind) && o.kodiEventServer && o.kodiEventServerPort.trim().toIntOrNull() !in 1..65535 ->
            "Port Event Server invalide"
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
        if (supportsHttps(kind) && o.mode.secure) {
            put(SCHEME, "https")
            if (o.mode == ConnectionMode.HTTPS_INSECURE) put(TLS_INSECURE, "true")
        }
        if (supportsTimeout(kind)) {
            o.timeoutS.trim().toIntOrNull()
                ?.takeIf { it in MIN_CONNECT_TIMEOUT_S..MAX_CONNECT_TIMEOUT_S && it != DEFAULT_CONNECT_TIMEOUT_S }
                ?.let { put(TIMEOUT, it.toString()) }
        }
        if (supportsNetworkRestriction(kind)) {
            if (o.wifiOnly) put(WIFI_ONLY, "true")
            parseSsids(o.wifiSsids).takeIf { it.isNotEmpty() }?.let { put(WIFI_SSIDS, it.joinToString("\n")) }
        }
        if (supportsEventServer(kind) && o.kodiEventServer) {
            put(KODI_EVENTSERVER, "true")
            put(KODI_EVENTSERVER_PORT, o.kodiEventServerPort.trim())
        }
        if (supportsAltDidl(kind) && o.upnpAltDidl) put(UPNP_ALT_DIDL, "true")
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

    /** Mode de connexion enregistré ; un ancien `scheme=https` seul donne [ConnectionMode.HTTPS]. */
    fun connectionMode(device: Device): ConnectionMode = when {
        !supportsHttps(device.kind) || device.extras[SCHEME] != "https" -> ConnectionMode.HTTP
        device.extras[TLS_INSECURE] == "true" -> ConnectionMode.HTTPS_INSECURE
        else -> ConnectionMode.HTTPS
    }

    /** Certificat non vérifié demandé pour cet appareil (jamais pour les autres). */
    fun insecureTls(device: Device): Boolean = connectionMode(device) == ConnectionMode.HTTPS_INSECURE

    /** Délai de connexion de l'appareil en secondes (défaut si absent ou invalide). */
    fun timeoutSeconds(device: Device?): Int = device?.extras?.get(TIMEOUT)?.toIntOrNull()
        ?.takeIf { it in MIN_CONNECT_TIMEOUT_S..MAX_CONNECT_TIMEOUT_S } ?: DEFAULT_CONNECT_TIMEOUT_S

    /** Restriction réseau (Wi-Fi uniquement, SSID autorisés) de l'appareil. */
    fun networkPolicy(device: Device): NetworkPolicy {
        if (!supportsNetworkRestriction(device.kind)) return NetworkPolicy()
        return NetworkPolicy(device.extras[WIFI_ONLY] == "true", parseSsids(device.extras[WIFI_SSIDS].orEmpty()).toSet())
    }

    /** SSID saisis : un par ligne, espaces de bord et guillemets retirés, doublons et lignes vides ignorés. */
    fun parseSsids(text: String): List<String> =
        text.lines().map { it.trim().removeSurrounding("\"") }.filter { it.isNotEmpty() }.distinct()

    /**
     * URL de base saisie dans le formulaire (`https://hôte:port/chemin`), ou `null` si l'hôte
     * ou le port est invalide. Sert aux composants additionnels de la section Identifiants.
     */
    fun formBaseUrl(mode: ConnectionMode, host: String, port: Int, basePath: String = ""): String? {
        val h = host.trim()
        if (h.isEmpty() || port !in 1..65535 || h.any { it.isWhitespace() || it == '/' }) return null
        val bracketed = if (':' in h && !h.startsWith("[")) "[$h]" else h
        val scheme = if (mode.secure) "https" else "http"
        return "$scheme://$bracketed:$port" + MediaServerController.normalizeBasePath(basePath).orEmpty()
    }

    /** URL HTTP(S) de base, selon le mode de connexion. */
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
