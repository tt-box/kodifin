package io.github.ttbox.kodifin.connection

import io.github.ttbox.kodifin.R
import io.github.ttbox.kodifin.data.DeviceSecrets
import io.github.ttbox.kodifin.mediaserver.MediaServerController
import io.github.ttbox.kodifin.model.Device
import io.github.ttbox.kodifin.model.DeviceKind
import io.github.ttbox.kodifin.text.UiText
import java.net.URI

/*
 * Logique du formulaire de configuration d'un appareil, sans Compose : validation des champs
 * et construction de l'appareil à enregistrer. L'écran (ui/config) ne fait que l'appeler.
 */

/** Types dont le formulaire demande un utilisateur et un mot de passe. */
internal fun usesCredentials(kind: DeviceKind) = kind in setOf(DeviceKind.KODI, DeviceKind.JELLYFIN, DeviceKind.EMBY)

/** Validation pure, testée unitairement. */
object ConfigValidation {
    private val HOST = Regex("""^[A-Za-z0-9.\-:\[\]]+$""")

    fun error(kind: DeviceKind, host: String, port: String, location: String): UiText? = when {
        kind == DeviceKind.UPNP_RENDERER && locationEndpoint(location) == null -> UiText.Res(R.string.config_error_upnp_location_required)
        kind != DeviceKind.UPNP_RENDERER && !HOST.matches(host.trim()) -> UiText.Res(R.string.config_error_invalid_address)
        kind != DeviceKind.UPNP_RENDERER && (port.toIntOrNull() ?: 0) !in 1..65535 -> UiText.Res(R.string.config_error_invalid_port)
        else -> null
    }

    /**
     * Hôte et port d'une URL de description UPnP (`http(s)://hôte[:port]/…`), ou `null` si
     * l'URL est invalide. Gère IPv6 (`[fe80::1]`) et le port implicite (80 / 443), ce que
     * le découpage manuel sur « : » faisait mal.
     */
    fun locationEndpoint(location: String): Pair<String, Int>? {
        val uri = runCatching { URI(location.trim()) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") return null
        val host = uri.host?.takeIf { it.isNotEmpty() } ?: return null
        val port = if (uri.port in 1..65535) uri.port else if (scheme == "https") 443 else 80
        return host to port
    }
}

/**
 * Résultat du formulaire : l'appareil à enregistrer, sans aucun mot de passe, et le mot de
 * passe saisi à part (`null` si les identifiants ne changent pas). Le dépôt décide de son sort :
 * chiffré pour Kodi, gardé en mémoire jusqu'à l'obtention du jeton pour Jellyfin/Emby.
 */
internal data class DeviceDraft(val device: Device, val password: String?) {
    override fun toString(): String {
        val masked = device.copy(extras = device.extras.mapValues { (k, v) -> if (k in DeviceSecrets.SECRET_KEYS) "***" else v })
        return "DeviceDraft(device=$masked, password=${if (password == null) "null" else "***"})"
    }
}

/** Extras liés à l'ancien compte, invalides dès que de nouveaux identifiants sont saisis. */
private val STALE_CREDENTIALS = setOf(
    DeviceSecrets.PASSWORD,
    DeviceSecrets.ENCRYPTED_PASSWORD,
    MediaServerController.EXTRA_TOKEN,
    DeviceSecrets.ENCRYPTED_TOKEN,
    MediaServerController.EXTRA_USER_ID,
)

/** Construction de l'appareil à partir du formulaire (logique pure, testée unitairement). */
internal fun buildDevice(
    prefill: Device?,
    kind: DeviceKind,
    name: String,
    host: String,
    port: String,
    location: String,
    username: String,
    password: String,
    /** Options avancées ; `null` : celles de l'appareil pré-rempli sont conservées telles quelles. */
    options: AdvancedOptions? = null,
    /**
     * Plex : jeton propre à l'appareil saisi. `null` : inchangé ; vide : effacé ; sinon remplacé
     * (en clair dans le brouillon, chiffré par le dépôt à l'enregistrement).
     */
    plexToken: String? = null,
): DeviceDraft {
    // Changement de type : les extras (jeton Plex/Jellyfin, identifiant de session…) et
    // l'identifiant de l'appareil pré-rempli appartiennent à un autre protocole.
    val base = prefill?.takeIf { it.kind == kind }
    // Édition : même utilisateur et mot de passe laissé vide = identifiants inchangés
    // (jeton et mot de passe chiffré conservés).
    val sameAccount = base != null && username.trim() == base.extras["username"] && password.isEmpty()
    val newCredentials = usesCredentials(kind) && username.isNotBlank() && !sameAccount
    val extras = buildMap {
        base?.extras?.let { putAll(it - DeviceSecrets.PASSWORD) }
        if (kind == DeviceKind.UPNP_RENDERER) put("location", location.trim())
        if (newCredentials) {
            STALE_CREDENTIALS.forEach { remove(it) }
            put("username", username.trim())
        }
    }.let { if (options != null) DeviceOptions.apply(it, kind, options) else it }
        .let { if (kind == DeviceKind.PLEX && plexToken != null) withPlexDeviceToken(it, plexToken) else it }
    val endpoint = if (kind == DeviceKind.UPNP_RENDERER) ConfigValidation.locationEndpoint(location) else null
    val effectiveHost = endpoint?.first ?: host.trim()
    val effectivePort = endpoint?.second ?: port.toInt()
    val device = Device(
        id = base?.id ?: Device.manualId(kind, effectiveHost, effectivePort),
        name = name.trim().ifBlank { "${kind.label} $effectiveHost" },
        kind = kind,
        host = effectiveHost,
        port = effectivePort,
        extras = extras,
    )
    return DeviceDraft(device, password.takeIf { newCredentials })
}

/** Remplace (ou efface si [token] est vide) le jeton Plex propre à l'appareil. */
private fun withPlexDeviceToken(extras: Map<String, String>, token: String): Map<String, String> {
    val cleared = extras - DeviceSecrets.TOKEN - DeviceSecrets.ENCRYPTED_TOKEN
    return if (token.isBlank()) cleared else cleared + (DeviceSecrets.TOKEN to token.trim())
}

/**
 * Quick Connect réussi : appareil à enregistrer avec le jeton obtenu, sans aucun mot de passe.
 * La session imposée (`sessionId`) n'est retirée que si la cible ou le compte change : même
 * type, hôte, port et utilisateur que l'appareil enregistré, elle reste valable.
 */
internal fun quickConnectDevice(prefill: Device?, draft: Device, token: String, userId: String, userName: String): Device {
    val sameTarget = prefill != null && prefill.kind == draft.kind && prefill.host == draft.host && prefill.port == draft.port
    val sameAccount = prefill?.extras?.get(MediaServerController.EXTRA_USER_ID)?.let { it == userId }
        ?: (prefill?.extras?.get(MediaServerController.EXTRA_USERNAME) == userName)
    val stale = setOf(DeviceSecrets.PASSWORD, DeviceSecrets.ENCRYPTED_PASSWORD, DeviceSecrets.ENCRYPTED_TOKEN) +
        if (sameTarget && sameAccount) emptySet() else setOf(MediaServerController.EXTRA_SESSION_ID)
    return draft.copy(
        extras = draft.extras - stale + mapOf(
            MediaServerController.EXTRA_TOKEN to token,
            MediaServerController.EXTRA_USER_ID to userId,
            MediaServerController.EXTRA_USERNAME to userName,
        ),
    )
}
