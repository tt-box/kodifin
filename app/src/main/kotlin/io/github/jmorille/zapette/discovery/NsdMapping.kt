package io.github.jmorille.zapette.discovery

import io.github.jmorille.zapette.model.Device
import io.github.jmorille.zapette.model.DeviceKind

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

    /** DNS-SD échappe les caractères spéciaux en `\DDD` décimal. */
    fun unescape(name: String): String = Regex("""\\(\d{3})""").replace(name) { it.groupValues[1].toInt().toChar().toString() }
}
