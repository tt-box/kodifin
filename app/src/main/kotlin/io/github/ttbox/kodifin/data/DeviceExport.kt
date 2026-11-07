package io.github.ttbox.kodifin.data

import io.github.ttbox.kodifin.R
import io.github.ttbox.kodifin.model.Device
import io.github.ttbox.kodifin.model.DeviceKind
import io.github.ttbox.kodifin.model.InsecureTls
import io.github.ttbox.kodifin.text.UiText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.DataFormatException
import java.util.zip.Deflater
import java.util.zip.Inflater

/**
 * Fichier ou texte d'import refusé en bloc (format, version, taille, structure). [text] est la
 * raison affichée ; le message de l'exception n'en est que la forme technique (journaux).
 */
class InvalidExportException(val text: UiText) : IllegalArgumentException(text.toString()) {
    companion object {
        /** Fichier importé au-delà de la taille lue ([io.github.ttbox.kodifin.ui.transfer.readTextLimited]). */
        fun fileTooLarge() = InvalidExportException(UiText.Res(R.string.sys_export_file_too_large))
    }
}

/** Conduite à tenir quand un appareil importé porte l'identifiant d'un appareil déjà enregistré. */
enum class ImportPolicy { SKIP_EXISTING, REPLACE_EXISTING }

/** Bilan d'un import (affiché par `importSummary`, ui/transfer). */
data class ImportReport(val added: Int = 0, val updated: Int = 0, val skipped: Int = 0, val invalid: Int = 0)

/** Contenu lu : appareils valides et nombre d'entrées rejetées. */
data class DecodedExport(val devices: List<Device>, val invalid: Int)

/** Appareils à écrire et bilan correspondant. */
data class MergePlan(val toSave: List<Device>, val report: ImportReport)

/**
 * Format d'échange des appareils (fichier, texte, QR code), versionné et SANS AUCUN SECRET :
 * `{"format":"kodifin-devices","version":1,"devices":[{"id","name","type","host","port","options":{…}}]}`.
 *
 * Seules les options de connexion d'une liste blanche sont exportées (et acceptées à l'import) :
 * mots de passe (chiffrés ou non), jetons, identifiants de session ou de client n'en sortent
 * jamais et ne peuvent pas être injectés par un fichier. Logique pure, testée en JVM.
 */
object DeviceExport {
    const val FORMAT = "kodifin-devices"
    const val VERSION = 1

    /** Taille maximale d'un texte importé (caractères), y compris après décompression d'un QR code. */
    const val MAX_CHARS = 256 * 1024
    const val MAX_DEVICES = 200
    /** Profondeur d'imbrication maximale : racine → devices → appareil → options. */
    const val MAX_DEPTH = 4
    const val MAX_OPTIONS = 32
    private const val MAX_ID = 256
    private const val MAX_NAME = 200
    private const val MAX_HOST = 253
    private const val MAX_OPTION_VALUE = 1024

    /** `tls_pin` : empreinte SHA-256 du certificat auquel on a fait confiance (publique, pas un secret). */
    private const val TLS_PIN = "tls_pin"

    private val EXACT_OPTIONS = setOf(
        "scheme", "tls_insecure", TLS_PIN, "timeout_s", "base_path", "upnp_alt_didl", "location", "username",
    )
    private val OPTION_PREFIXES = listOf("kodi_ws", "kodi_eventserver", "wol_", "wifi_")

    /** Garde-fou : même un préfixe autorisé ne laisse pas passer une clé à l'allure de secret. */
    private val SECRET_MARKERS = listOf(
        "pass", "token", "secret", "session", "userid", "user_id", "clientid", "client_id",
        "clientidentifier", "auth", "cookie", "apikey", "api_key", "_enc",
    )

    private val OPTION_KEY = Regex("^[a-z][a-z0-9_]{0,63}$")
    private val IPV4 = Regex("""^(25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)(\.(25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)){3}$""")
    private val IPV6 = Regex("""^[0-9A-Fa-f:.]{2,45}(%[A-Za-z0-9_.-]{1,32})?$""")
    private val HOST_LABEL = Regex("^[A-Za-z0-9_]([A-Za-z0-9_-]{0,61}[A-Za-z0-9_])?$")

    private val compact = Json
    private val pretty = Json { prettyPrint = true }

    /** Option exportable (liste blanche, jamais un secret). */
    fun isExportableOption(key: String): Boolean {
        val lower = key.lowercase()
        if (SECRET_MARKERS.any { it in lower }) return false
        return key in EXACT_OPTIONS || OPTION_PREFIXES.any { key.startsWith(it) }
    }

    /** Document d'export de [devices], options limitées à la liste blanche. */
    fun encode(devices: List<Device>, prettyPrint: Boolean = false): String {
        val root = buildJsonObject {
            put("format", FORMAT)
            put("version", VERSION)
            put("devices", JsonArray(devices.map { it.toExportJson() }))
        }
        return (if (prettyPrint) pretty else compact).encodeToString(JsonElement.serializer(), root)
    }

    private fun Device.toExportJson(): JsonObject = buildJsonObject {
        put("id", id)
        put("name", name)
        put("type", kind.name)
        put("host", host)
        put("port", port)
        put("options", JsonObject(extras.filterKeys(::isExportableOption).toSortedMap().mapValues { JsonPrimitive(it.value) }))
    }

    /**
     * Lecture stricte. Lève [InvalidExportException] si le document entier est refusé (taille,
     * profondeur, JSON illisible, format ou version inconnus, trop d'appareils) ; un appareil
     * isolé invalide (type inconnu, hôte ou port incorrect, champ manquant, doublon) est
     * seulement compté dans [DecodedExport.invalid].
     */
    fun decode(text: String): DecodedExport {
        if (text.length > MAX_CHARS) throw rejected(R.string.sys_export_too_large_kib, MAX_CHARS / 1024)
        if (exceedsDepth(text, MAX_DEPTH)) throw rejected(R.string.sys_export_too_deep)
        val root = try {
            compact.parseToJsonElement(text)
        } catch (_: Exception) {
            throw rejected(R.string.sys_export_unreadable)
        } as? JsonObject ?: throw rejected(R.string.sys_export_unreadable)

        if ((root["format"] as? JsonPrimitive)?.takeIf { it.isString }?.content != FORMAT) {
            throw rejected(R.string.sys_export_wrong_format)
        }
        val version = (root["version"] as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull
            ?: throw rejected(R.string.sys_export_missing_version)
        if (version != VERSION) {
            throw if (version > VERSION) {
                rejected(R.string.sys_export_newer_version, version)
            } else {
                rejected(R.string.sys_export_unknown_version, version)
            }
        }
        val list = root["devices"] as? JsonArray ?: throw rejected(R.string.sys_export_missing_devices)
        if (list.size > MAX_DEVICES) throw rejected(R.string.sys_export_too_many_devices, list.size, MAX_DEVICES)

        val devices = mutableListOf<Device>()
        val seen = HashSet<String>()
        var invalid = 0
        for (element in list) {
            val device = parseDevice(element)
            if (device == null || !seen.add(device.id)) invalid++ else devices += device
        }
        return DecodedExport(devices, invalid)
    }

    private fun parseDevice(element: JsonElement): Device? {
        val obj = element as? JsonObject ?: return null
        val kind = obj.string("type")?.let { name -> DeviceKind.entries.firstOrNull { it.name == name } } ?: return null
        val name = obj.string("name")?.trim()?.takeIf { it.isNotEmpty() && it.length <= MAX_NAME && it.isPrintable() } ?: return null
        val host = obj.string("host")?.trim()?.takeIf(::isValidHost) ?: return null
        val port = when (val p = obj["port"]) {
            null, JsonNull -> kind.defaultPort
            is JsonPrimitive -> p.takeUnless { it.isString }?.intOrNull ?: return null
            else -> return null
        }
        if (!isValidPort(kind, port)) return null
        val id = when (obj["id"]) {
            null, JsonNull -> Device.manualId(kind, host, port)
            else -> obj.string("id")?.takeIf { it.isNotBlank() && it.length <= MAX_ID && it.isPrintable() } ?: return null
        }
        val options = when (val o = obj["options"]) {
            null, JsonNull -> emptyMap()
            is JsonObject -> parseOptions(o) ?: return null
            else -> return null
        }
        return Device(id = id, name = name, kind = kind, host = host, port = port, extras = options)
    }

    /** Options valides de la liste blanche ; les clés inconnues ou secrètes sont écartées sans bruit. */
    private fun parseOptions(o: JsonObject): Map<String, String>? {
        if (o.size > MAX_OPTIONS) return null
        val out = LinkedHashMap<String, String>()
        for ((key, value) in o) {
            val primitive = value as? JsonPrimitive ?: return null
            if (primitive is JsonNull) continue
            val text = primitive.content
            if (text.length > MAX_OPTION_VALUE || !text.isPrintable()) return null
            if (!OPTION_KEY.matches(key) || !isExportableOption(key)) continue
            if (key == "location" && !(text.startsWith("http://") || text.startsWith("https://"))) return null
            if (key == TLS_PIN) {
                // Empreinte illisible : écartée (l'appareil retombe sur « faire de nouveau confiance »).
                InsecureTls.normalizeFingerprint(text)?.let { out[key] = it }
                continue
            }
            out[key] = text
        }
        return out
    }

    /** Port 1–65535 ; 0 seulement pour un rendu UPnP (adresse portée par `location`). */
    fun isValidPort(kind: DeviceKind, port: Int): Boolean =
        port in 1..65535 || (port == 0 && kind == DeviceKind.UPNP_RENDERER)

    /** Adresse IPv4, IPv6 (sans crochets) ou nom d'hôte ; ni schéma, ni chemin, ni espace. */
    fun isValidHost(host: String): Boolean {
        if (host.isEmpty() || host.length > MAX_HOST) return false
        if (IPV4.matches(host)) return true
        if (':' in host) return IPV6.matches(host) && host.count { it == ':' } in 2..7
        // Une suite de chiffres et de points qui n'est pas une IPv4 valide (ex. 999.1.1.1) est refusée.
        if (host.all { it.isDigit() || it == '.' }) return false
        return host.removeSuffix(".").split('.').all { HOST_LABEL.matches(it) }
    }

    private fun rejected(id: Int, vararg args: Any) = InvalidExportException(UiText.Res(id, args.toList()))

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun String.isPrintable(): Boolean = none { it.isISOControl() }

    /**
     * Profondeur d'imbrication (objets et tableaux) mesurée sans analyseur, avant tout parsing :
     * un document hostile très imbriqué est refusé sans récursion.
     */
    internal fun exceedsDepth(text: String, max: Int): Boolean {
        var depth = 0
        var inString = false
        var escaped = false
        for (c in text) {
            if (inString) {
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = false
                }
                continue
            }
            when (c) {
                '"' -> inString = true
                '{', '[' -> if (++depth > max) return true
                '}', ']' -> depth--
            }
        }
        return false
    }

    /**
     * Fusion par identifiant. Un appareil existant est ignoré ([ImportPolicy.SKIP_EXISTING])
     * ou remplacé ; s'il est identique, il est compté comme ignoré. Lors d'un remplacement, les
     * secrets locaux (mot de passe chiffré, jetons) ne sont conservés que si la cible de connexion
     * (type, hôte, port, schéma) est inchangée : un fichier ne peut pas rediriger un mot de passe
     * enregistré vers une autre machine ni le faire passer en clair.
     */
    fun merge(existing: List<Device>, imported: DecodedExport, policy: ImportPolicy): MergePlan {
        val byId = existing.associateBy { it.id }
        val toSave = mutableListOf<Device>()
        var added = 0
        var updated = 0
        var skipped = 0
        for (device in imported.devices) {
            val current = byId[device.id]
            when {
                current == null -> { toSave += device; added++ }
                policy == ImportPolicy.SKIP_EXISTING -> skipped++
                else -> {
                    val merged = device.copy(extras = device.extras + preservedSecrets(current, device))
                    if (merged == current) skipped++ else { toSave += merged; updated++ }
                }
            }
        }
        return MergePlan(toSave, ImportReport(added, updated, skipped, imported.invalid))
    }

    private fun preservedSecrets(current: Device, incoming: Device): Map<String, String> {
        val sameTarget = current.kind == incoming.kind && current.host == incoming.host && current.port == incoming.port &&
            current.extras["scheme"] == incoming.extras["scheme"]
        if (!sameTarget) return emptyMap()
        // Empreinte de confiance locale conservée si le fichier n'en apporte pas.
        val pin = current.extras[TLS_PIN]?.takeIf { TLS_PIN !in incoming.extras }?.let { mapOf(TLS_PIN to it) }.orEmpty()
        return current.extras.filterKeys { !isExportableOption(it) } + pin
    }
}

/**
 * Contenu d'un QR code : le JSON d'export tel quel s'il est court, sinon compressé (Deflate)
 * et encodé en base64url derrière le préfixe [PREFIX]. La décompression est bornée
 * ([DeviceExport.MAX_CHARS]) contre les « bombes » de compression.
 */
object QrPayload {
    const val PREFIX = "KODIFIN1:"
    /** En deçà, le JSON brut reste lisible par n'importe quel lecteur de QR code. */
    const val PLAIN_MAX = 600
    /** Au-delà, le QR code devient trop dense pour être lu de façon fiable sur un écran. */
    const val QR_MAX = 1800

    fun encode(json: String): String {
        if (json.length <= PLAIN_MAX) return json
        val input = json.toByteArray(Charsets.UTF_8)
        val deflater = Deflater(Deflater.BEST_COMPRESSION, true)
        val compressed = try {
            deflater.setInput(input)
            deflater.finish()
            val out = ByteArrayOutputStream()
            val buf = ByteArray(4096)
            while (!deflater.finished()) out.write(buf, 0, deflater.deflate(buf))
            out.toByteArray()
        } finally {
            deflater.end()
        }
        val packed = PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(compressed)
        return if (packed.length < json.length) packed else json
    }

    /** Le contenu tient-il dans un QR code lisible ? */
    fun fits(payload: String): Boolean = payload.length <= QR_MAX

    /** Texte JSON porté par un QR code (ou un texte collé), compressé ou non. */
    fun decode(text: String): String {
        val trimmed = text.trim()
        if (!trimmed.startsWith(PREFIX)) return trimmed
        val body = trimmed.removePrefix(PREFIX)
        if (body.length > DeviceExport.MAX_CHARS) throw InvalidExportException(UiText.Res(R.string.sys_export_too_large))
        val bytes = try {
            Base64.getUrlDecoder().decode(body)
        } catch (_: IllegalArgumentException) {
            throw InvalidExportException(UiText.Res(R.string.sys_qr_unreadable_encoding))
        }
        val inflater = Inflater(true)
        try {
            inflater.setInput(bytes)
            val out = ByteArrayOutputStream()
            val buf = ByteArray(4096)
            // Limite en octets : un caractère UTF-8 occupe au moins un octet.
            while (!inflater.finished()) {
                val n = inflater.inflate(buf)
                if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) {
                    throw InvalidExportException(UiText.Res(R.string.sys_qr_unreadable_truncated))
                }
                out.write(buf, 0, n)
                if (out.size() > DeviceExport.MAX_CHARS) throw InvalidExportException(UiText.Res(R.string.sys_export_too_large))
            }
            return out.toString(Charsets.UTF_8.name())
        } catch (_: DataFormatException) {
            throw InvalidExportException(UiText.Res(R.string.sys_qr_unreadable_compression))
        } finally {
            inflater.end()
        }
    }
}
