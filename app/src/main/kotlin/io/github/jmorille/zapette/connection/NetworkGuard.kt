package io.github.jmorille.zapette.connection

/**
 * Restriction réseau d'un appareil : [wifiOnly] exige le Wi-Fi ; une liste [allowedSsids]
 * non vide l'exige aussi et limite la connexion à ces réseaux.
 */
data class NetworkPolicy(val wifiOnly: Boolean = false, val allowedSsids: Set<String> = emptySet()) {
    val requiresWifi: Boolean get() = wifiOnly || allowedSsids.isNotEmpty()
}

/** État du réseau courant, lu par la plateforme (implémentation Android : [AndroidNetworkInfo]). */
interface NetworkInfo {
    /** Le réseau actif passe par le Wi-Fi. */
    fun isWifiConnected(): Boolean

    /**
     * SSID du Wi-Fi courant (sans guillemets), ou `null` s'il est illisible : pas de Wi-Fi,
     * autorisation de localisation refusée ou localisation désactivée.
     */
    fun currentSsid(): String?

    /** Vrai si [currentSsid] peut aboutir (autorisation de localisation accordée et localisation active). */
    fun canReadSsid(): Boolean
}

/** Verdict de [NetworkGuard.check]. */
sealed interface NetworkCheck {
    /** Connexion autorisée ; [warning] signale une vérification impossible (SSID illisible). */
    data class Allowed(val warning: String? = null) : NetworkCheck

    /** Connexion refusée sans rien tenter. */
    data class Blocked(val message: String) : NetworkCheck
}

/** Garde réseau appliquée avant chaque connexion (logique pure, testée unitairement). */
object NetworkGuard {
    const val WIFI_REQUIRED = "Wi-Fi requis pour cet appareil"

    fun check(policy: NetworkPolicy, info: NetworkInfo?): NetworkCheck {
        if (!policy.requiresWifi || info == null) return NetworkCheck.Allowed()
        if (!info.isWifiConnected()) return NetworkCheck.Blocked(WIFI_REQUIRED)
        if (policy.allowedSsids.isEmpty()) return NetworkCheck.Allowed()
        // SSID illisible (autorisation ou localisation) : on ne bloque pas, on prévient.
        val ssid = info.currentSsid()
            ?: return NetworkCheck.Allowed(
                if (info.canReadSsid()) {
                    "Réseau Wi-Fi non identifié : restriction de réseau non vérifiée"
                } else {
                    "Réseau Wi-Fi non vérifié : autorisez la localisation (et activez-la) pour appliquer la restriction"
                },
            )
        return if (ssid in policy.allowedSsids) {
            NetworkCheck.Allowed()
        } else {
            NetworkCheck.Blocked("Réseau « $ssid » non autorisé pour cet appareil")
        }
    }

    /** Normalise un SSID Android (`"Maison"` entre guillemets, `<unknown ssid>`), `null` si inconnu. */
    fun normalizeSsid(raw: String?): String? {
        val s = raw?.trim() ?: return null
        if (s.isEmpty() || s == UNKNOWN_SSID || s == "0x") return null
        return s.removeSurrounding("\"").takeIf { it.isNotEmpty() }
    }

    /** Valeur renvoyée par Android quand le SSID est masqué (pas d'autorisation). */
    const val UNKNOWN_SSID = "<unknown ssid>"

    /** Ajoute [ssid] à la liste saisie (une ligne par réseau) s'il n'y figure pas déjà. */
    fun addSsid(text: String, ssid: String): String {
        val list = DeviceOptions.parseSsids(text)
        return if (ssid in list) list.joinToString("\n") else (list + ssid).joinToString("\n")
    }
}
