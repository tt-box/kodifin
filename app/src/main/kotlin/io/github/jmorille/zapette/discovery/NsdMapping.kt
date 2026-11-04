package io.github.jmorille.zapette.discovery

import io.github.jmorille.zapette.model.Device
import io.github.jmorille.zapette.model.DeviceKind
import java.io.ByteArrayOutputStream
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

/** Correspondance services mDNS / DNS-SD → appareils (logique pure, testée unitairement). */
object NsdMapping {
    const val KODI = "_xbmc-jsonrpc-h._tcp"
    const val AIRPLAY = "_airplay._tcp"
    const val FIRE_TV = "_amzn-wplay._tcp"

    val serviceTypes = listOf(KODI, AIRPLAY, FIRE_TV)

    /** Android renvoie parfois le type avec un point final ou un préfixe point. */
    fun normalize(type: String): String = type.trim('.').removeSuffix(".local")

    fun toDevice(serviceType: String, serviceName: String, host: String, port: Int): Device? {
        if (host.isBlank()) return null
        val kind = when (normalize(serviceType)) {
            KODI -> DeviceKind.KODI
            AIRPLAY -> DeviceKind.AIRPLAY
            FIRE_TV -> DeviceKind.FIRE_TV
            else -> return null
        }
        // Fire TV annonce son service de diffusion ; le pilotage passe par ADB (5555).
        val effectivePort = if (kind == DeviceKind.FIRE_TV) DeviceKind.FIRE_TV.defaultPort else port
        return Device(
            id = "${kind.name}:$host:$effectivePort",
            name = unescape(serviceName),
            kind = kind,
            host = host,
            port = effectivePort,
        )
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
