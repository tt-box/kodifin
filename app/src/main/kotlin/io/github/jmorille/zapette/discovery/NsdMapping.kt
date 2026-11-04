package io.github.jmorille.zapette.discovery

import io.github.jmorille.zapette.connection.DeviceOptions
import io.github.jmorille.zapette.model.Device
import io.github.jmorille.zapette.model.DeviceKind
import java.io.ByteArrayOutputStream
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

/** Correspondance services mDNS / DNS-SD → appareils (logique pure, testée unitairement). */
object NsdMapping {
    /** Kodi : serveur web (JSON-RPC sur HTTP). */
    const val KODI = "_xbmc-jsonrpc-h._tcp"

    /** Kodi : JSON-RPC TCP brut, dont le port est aussi celui du serveur WebSocket (9090 par défaut). */
    const val KODI_TCP = "_xbmc-jsonrpc._tcp"
    const val AIRPLAY = "_airplay._tcp"
    const val FIRE_TV = "_amzn-wplay._tcp"

    /** Android TV / Google TV : service « Android TV Remote » v2 (port 6466, protocole non utilisé ici). */
    const val ANDROID_TV_REMOTE = "_androidtvremote2._tcp"

    /** Android 11+ : débogage sans fil (ADB sur TLS, port aléatoire, appairage par code). */
    const val ADB_TLS = "_adb-tls-connect._tcp"

    /** Extra : port ADB-TLS annoncé (débogage sans fil), conservé pour information. */
    const val EXTRA_ADB_TLS_PORT = "adb_tls_port"

    /** Marqueur interne d'un appareil Kodi issu de [KODI_TCP], retiré par [consolidate]. */
    private const val KODI_TCP_MARKER = "_nsd_kodi_tcp"

    /**
     * Ordre significatif : à identifiant égal, le premier appareil donne son nom ([consolidate]) ;
     * le nom convivial d'Android TV Remote passe donc avant celui, technique, du débogage sans fil.
     */
    val serviceTypes = listOf(KODI, KODI_TCP, AIRPLAY, FIRE_TV, ANDROID_TV_REMOTE, ADB_TLS)

    /** Android renvoie parfois le type avec un point final ou un préfixe point. */
    fun normalize(type: String): String = type.trim('.').removeSuffix(".local")

    /**
     * Annonce DNS-SD → appareil :
     * - [KODI_TCP] donne un appareil Kodi portant l'extra [DeviceOptions.KODI_WEBSOCKET_PORT]
     *   (port annoncé) ; [consolidate] le fusionne avec l'annonce HTTP du même hôte.
     * - [FIRE_TV], [ANDROID_TV_REMOTE] et [ADB_TLS] donnent un appareil Fire TV / Android TV
     *   piloté en ADB TCP classique (5555). Le port ADB-TLS n'est pas utilisable tel quel (la
     *   bibliothèque ADB ne gère ni le TLS ni l'appairage par code) : il est conservé dans
     *   l'extra [EXTRA_ADB_TLS_PORT].
     */
    fun toDevice(serviceType: String, serviceName: String, host: String, port: Int): Device? {
        if (host.isBlank()) return null
        val type = normalize(serviceType)
        val kind = when (type) {
            KODI, KODI_TCP -> DeviceKind.KODI
            AIRPLAY -> DeviceKind.AIRPLAY
            FIRE_TV, ANDROID_TV_REMOTE, ADB_TLS -> DeviceKind.FIRE_TV
            else -> return null
        }
        val validPort = port.takeIf { it in 1..65535 }
        var name = unescape(serviceName)
        val (effectivePort, extras) = when (type) {
            KODI_TCP -> {
                val wsPort = validPort ?: return null
                DeviceKind.KODI.defaultPort to mapOf(DeviceOptions.KODI_WEBSOCKET_PORT to wsPort.toString(), KODI_TCP_MARKER to "1")
            }
            // Fire TV annonce son service de diffusion ; le pilotage passe par ADB (5555).
            FIRE_TV, ANDROID_TV_REMOTE -> DeviceKind.FIRE_TV.defaultPort to emptyMap()
            ADB_TLS -> {
                // Nom d'instance technique (« adb-<série>-<suffixe> ») : remplacé par un libellé lisible.
                name = "Android TV ($host)"
                DeviceKind.FIRE_TV.defaultPort to validPort?.let { mapOf(EXTRA_ADB_TLS_PORT to it.toString()) }.orEmpty()
            }
            else -> port to emptyMap()
        }
        return Device(
            id = "${kind.name}:$host:$effectivePort",
            name = name,
            kind = kind,
            host = host,
            port = effectivePort,
            extras = extras,
        )
    }

    /**
     * Regroupe les appareils de toutes les sources :
     * - un Kodi issu de [KODI_TCP] apporte son port WebSocket au(x) Kodi HTTP du même hôte puis
     *   disparaît ; sans annonce HTTP, il reste seul (port HTTP par défaut) ;
     * - à identifiant égal, le premier appareil est gardé et les extras sont réunis (les siens
     *   l'emportent).
     */
    fun consolidate(devices: List<Device>): List<Device> {
        val (kodiTcp, others) = devices.partition { it.kind == DeviceKind.KODI && KODI_TCP_MARKER in it.extras }
        val wsPortByHost = LinkedHashMap<String, String>()
        kodiTcp.forEach { d -> d.extras[DeviceOptions.KODI_WEBSOCKET_PORT]?.let { wsPortByHost.putIfAbsent(d.host, it) } }
        val httpKodiHosts = others.filter { it.kind == DeviceKind.KODI }.mapTo(HashSet()) { it.host }
        val enriched = others.map { d ->
            val wsPort = wsPortByHost[d.host]
            if (d.kind == DeviceKind.KODI && wsPort != null && DeviceOptions.KODI_WEBSOCKET_PORT !in d.extras) {
                d.copy(extras = d.extras + (DeviceOptions.KODI_WEBSOCKET_PORT to wsPort))
            } else {
                d
            }
        }
        val orphans = kodiTcp.filter { it.host !in httpKodiHosts }.map { it.copy(extras = it.extras - KODI_TCP_MARKER) }
        val byId = LinkedHashMap<String, Device>()
        (enriched + orphans).forEach { d ->
            val first = byId[d.id]
            byId[d.id] = if (first == null) d else first.copy(extras = d.extras + first.extras)
        }
        return byId.values.toList()
    }

    /**
     * Choisit l'adresse à utiliser parmi celles annoncées : IPv4 d'abord (identifiant stable,
     * URL simple), puis IPv6 routable. Les IPv6 lien-local sont écartées : sans leur zone
     * (`%wlan0`) elles sont injoignables, et avec elle l'URL construite par [Device.baseUrl] est invalide.
     */
    fun preferredHost(addresses: List<InetAddress>): String? {
        addresses.firstOrNull { it is Inet4Address }?.let { return it.hostAddress }
        return addresses
            .firstOrNull { it is Inet6Address && !it.isLinkLocalAddress && !it.isLoopbackAddress }
            ?.hostAddress?.substringBefore('%')
    }

    /**
     * DNS-SD échappe les caractères en `\DDD` (octet décimal, UTF-8 pour les accents) et
     * `\.` / `\\` pour les caractères réservés.
     */
    fun unescape(name: String): String {
        if ('\\' !in name) return name
        val out = StringBuilder()
        val pending = ByteArrayOutputStream()
        fun flushBytes() {
            if (pending.size() > 0) out.append(pending.toString(Charsets.UTF_8.name()))
            pending.reset()
        }
        var i = 0
        while (i < name.length) {
            val digits = if (name[i] == '\\' && i + 3 < name.length) name.substring(i + 1, i + 4) else null
            when {
                digits != null && digits.all { it in '0'..'9' } && digits.toInt() <= 255 -> {
                    pending.write(digits.toInt())
                    i += 4
                }
                name[i] == '\\' && i + 1 < name.length -> {
                    flushBytes()
                    out.append(name[i + 1])
                    i += 2
                }
                else -> {
                    flushBytes()
                    out.append(name[i])
                    i++
                }
            }
        }
        flushBytes()
        return out.toString()
    }
}
