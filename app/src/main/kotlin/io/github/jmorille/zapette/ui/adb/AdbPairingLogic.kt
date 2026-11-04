package io.github.jmorille.zapette.ui.adb

import io.github.jmorille.zapette.firetv.DadbShell
import io.github.jmorille.zapette.model.Device
import io.github.jmorille.zapette.model.DeviceKind

/** Types mDNS publiés par « Débogage sans fil » (Android 11+). */
object AdbServiceTypes {
    const val PAIRING = "_adb-tls-pairing._tcp"
    const val CONNECT = "_adb-tls-connect._tcp"
}

/** Service ADB annoncé sur le réseau local. */
data class AdbService(val type: String, val name: String, val host: String, val port: Int) {
    val isPairing get() = type == AdbServiceTypes.PAIRING
}

/** Téléviseur regroupant ses services (même adresse) : ports d'appairage et de connexion. */
data class AdbCandidate(val host: String, val name: String, val pairingPort: Int?, val connectPort: Int?)

/** Logique pure de l'écran d'appairage (testée en JVM). */
object AdbPairingLogic {
    private val CODE = Regex("^\\d{6}$")

    /** Regroupe les services par adresse ; les téléviseurs prêts à l'appairage d'abord. */
    fun candidates(services: Collection<AdbService>): List<AdbCandidate> =
        services.groupBy { it.host }.map { (host, list) ->
            AdbCandidate(
                host = host,
                name = list.firstOrNull { !it.isPairing }?.name ?: list.first().name,
                pairingPort = list.lastOrNull { it.isPairing }?.port,
                connectPort = list.lastOrNull { !it.isPairing }?.port,
            )
        }.sortedWith(compareBy({ it.pairingPort == null }, { it.host }))

    fun parsePort(text: String): Int? = text.trim().toIntOrNull()?.takeIf { it in 1..65535 }

    /** Message d'erreur du formulaire, ou `null` s'il est complet. [pairing] : code exigé. */
    fun validate(host: String, pairingPort: String, code: String, connectPort: String, pairing: Boolean): String? = when {
        host.isBlank() || host.trim().any { it.isWhitespace() || it == '/' } -> "Adresse IP invalide"
        pairing && parsePort(pairingPort) == null -> "Port d'appairage invalide"
        pairing && !CODE.matches(code.trim()) -> "Le code d'appairage comporte 6 chiffres"
        parsePort(connectPort) == null -> "Port de connexion invalide (affiché sous « Débogage sans fil » : adresse IP et port)"
        else -> null
    }

    /** Appareil à enregistrer : Fire TV / Android TV en ADB TLS sur le port de connexion. */
    fun device(host: String, connectPort: Int, name: String, guid: String?): Device {
        val h = host.trim()
        return Device(
            id = Device.manualId(DeviceKind.FIRE_TV, h, connectPort),
            name = name.trim().ifEmpty { "Android TV ($h)" },
            kind = DeviceKind.FIRE_TV,
            host = h,
            port = connectPort,
            extras = buildMap {
                put(DadbShell.EXTRA_TLS, "true")
                guid?.takeIf { it.isNotBlank() }?.let { put(DadbShell.EXTRA_GUID, it) }
            },
        )
    }
}
