package io.github.jmorille.zapette.data

import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import io.github.jmorille.zapette.model.Device
import io.github.jmorille.zapette.model.DeviceKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.cancellation.CancellationException

/**
 * Appareils enregistrés et réglages locaux (stockage privé de l'application, exclu des sauvegardes).
 * Aucun secret n'y est écrit en clair :
 * - mot de passe Kodi (authentification Basic, exigé à chaque requête) : chiffré par [SecretStore] ;
 * - mot de passe Jellyfin/Emby : gardé en mémoire seulement, jusqu'à l'obtention du jeton ;
 * - jetons d'appareil (Jellyfin/Emby, Plex : extra `token`) : chiffrés sous `token_enc`
 *   ([DeviceSecrets.ENCRYPTED_TOKEN]), l'identifiant d'utilisateur restant en clair ;
 * - jeton du compte Plex lié : chiffré sous `plex_token_enc`.
 * Les secrets en clair hérités d'une version antérieure sont migrés à la première lecture.
 * Le déchiffrement n'a lieu qu'à la création du contrôleur ([DeviceSecrets.reveal]).
 */
class DeviceRepository(
    private val store: DataStore<Preferences>,
    private val secrets: SecretStore,
) {
    /** Mots de passe Jellyfin/Emby en attente de la première connexion réussie (jamais écrits). */
    private val transientPasswords = ConcurrentHashMap<String, String>()

    /** Lecture tolérante aux erreurs d'entrée/sortie (les autres erreurs restent visibles). */
    private val data: Flow<Preferences> = store.data.catch { e ->
        if (e is IOException) emit(emptyPreferences()) else throw e
    }

    val saved: Flow<List<Device>> = data.map { prefs ->
        val raw = prefs[DEVICES]
        if (prefs[PLEX_TOKEN] != null) migratePlexToken()
        if (DeviceCodec.anyValid(raw, DeviceSecrets::hasPlainSecrets)) migratePlainSecrets(raw) else DeviceCodec.decode(raw)
    }.distinctUntilChanged()

    /**
     * Appareil enregistré ; pour Jellyfin/Emby, le mot de passe saisi (en mémoire) y est rattaché
     * le temps de la première connexion. Le mot de passe Kodi reste chiffré : il n'est déchiffré
     * qu'à la création du contrôleur.
     */
    suspend fun get(id: String): Device? {
        val device = saved.first().firstOrNull { it.id == id } ?: return null
        val password = transientPasswords[id] ?: return device
        return device.copy(extras = device.extras + (DeviceSecrets.PASSWORD to password))
    }

    /**
     * Enregistre (ou remplace, à la même position) l'appareil. [password] : mot de passe saisi,
     * prioritaire sur un extra `password` éventuellement porté par [device] ; il n'est jamais écrit
     * en clair. Pour Jellyfin/Emby, enregistrer l'appareil sans mot de passe (jeton obtenu)
     * oublie le mot de passe gardé en mémoire.
     */
    suspend fun save(device: Device, password: String? = null) {
        val plain = password ?: device.extras[DeviceSecrets.PASSWORD]
        // Version enregistrée : un jeton inchangé garde son chiffré (pas de réécriture à chaque connexion).
        val previous = if (DeviceSecrets.hasPlainToken(device)) saved.first().firstOrNull { it.id == device.id } else null
        // Chiffrement hors transaction : en cas d'échec, rien n'est écrit (et surtout pas en clair).
        val stored = DeviceSecrets.forStorage(device, plain, secrets, previous)
        store.edit { prefs -> prefs.updateDevices { raw -> DeviceCodec.upsert(raw, stored) } }
        if (DeviceSecrets.isTransient(device.kind)) {
            if (plain.isNullOrEmpty()) transientPasswords.remove(device.id) else transientPasswords[device.id] = plain
        }
    }

    /** Supprime l'appareil, y compris une entrée illisible portant le même identifiant. */
    suspend fun delete(id: String) {
        store.edit { prefs -> prefs.updateDevices { raw -> DeviceCodec.remove(raw, id) } }
        transientPasswords.remove(id)
    }

    /**
     * Identifiant aléatoire local, utilisé comme DeviceId Jellyfin/Emby et client Plex. Jamais transmis ailleurs.
     * Créé dans une seule transaction DataStore : deux appels simultanés obtiennent la même valeur.
     */
    suspend fun installationId(): String {
        data.first()[INSTALLATION_ID]?.let { return it }
        val prefs = store.edit { if (it[INSTALLATION_ID] == null) it[INSTALLATION_ID] = UUID.randomUUID().toString() }
        return checkNotNull(prefs[INSTALLATION_ID])
    }

    /**
     * Export des appareils enregistrés (tous, ou ceux de [ids]) au format [DeviceExport] :
     * aucun mot de passe, chiffré ou non, ni jeton.
     */
    suspend fun exportDevices(ids: Collection<String>? = null, prettyPrint: Boolean = false): String {
        val devices = saved.first().filter { ids == null || it.id in ids }
        return DeviceExport.encode(devices, prettyPrint)
    }

    /**
     * Import d'un export [DeviceExport] (JSON brut ou contenu de QR code compressé), fusionné par
     * identifiant dans une seule transaction. Lève [InvalidExportException] si le document est
     * refusé en bloc ; rien n'est alors écrit.
     */
    suspend fun importDevices(text: String, policy: ImportPolicy): ImportReport {
        val decoded = DeviceExport.decode(QrPayload.decode(text))
        var report = ImportReport(invalid = decoded.invalid)
        store.edit { prefs ->
            prefs.updateDevices { raw ->
                val plan = DeviceExport.merge(DeviceCodec.decode(raw), decoded, policy)
                report = plan.report
                plan.toSave.fold(raw ?: "[]") { acc, device -> DeviceCodec.upsert(acc, device) }
            }
        }
        return report
    }

    /** Un compte Plex est-il lié ? (le jeton lui-même reste chiffré) */
    val plexLinked: Flow<Boolean> = data.map { prefs ->
        if (prefs[PLEX_TOKEN] != null) migratePlexToken()
        prefs[PLEX_TOKEN] != null || prefs[PLEX_TOKEN_ENCRYPTED] != null
    }.distinctUntilChanged()

    /**
     * Jeton du compte Plex lié, CHIFFRÉ ([SecretStore]) : seule la fabrique de contrôleurs le
     * déchiffre, au moment de créer le contrôleur. Un jeton en clair hérité est d'abord migré.
     */
    suspend fun sealedPlexToken(): String? {
        val prefs = data.first()
        val legacy = prefs[PLEX_TOKEN] ?: return prefs[PLEX_TOKEN_ENCRYPTED]
        return migratePlexToken() ?: runCatching { secrets.encrypt(legacy) }.getOrNull()
    }

    /** Lie (jeton chiffré avant écriture) ou délie le compte Plex. */
    suspend fun setPlexToken(token: String?) {
        // Chiffrement hors transaction : en cas d'échec, rien n'est écrit (et surtout pas en clair).
        val sealed = token?.let(secrets::encrypt)
        store.edit {
            it.remove(PLEX_TOKEN)
            if (sealed == null) it.remove(PLEX_TOKEN_ENCRYPTED) else it[PLEX_TOKEN_ENCRYPTED] = sealed
        }
    }

    /**
     * Migration du jeton de compte Plex en clair d'une version antérieure. Renvoie le chiffré
     * écrit, ou `null` si la migration a échoué (on réessaiera à la lecture suivante) ; si le
     * chiffrement est impossible, le jeton est retiré plutôt que laissé en clair (compte à relier).
     */
    private suspend fun migratePlexToken(): String? = try {
        val legacy = data.first()[PLEX_TOKEN]
        val sealed = legacy?.let { runCatching { secrets.encrypt(it) }.getOrNull() }
        val prefs = store.edit { p ->
            if (p[PLEX_TOKEN] == legacy) {
                p.remove(PLEX_TOKEN)
                if (sealed != null) p[PLEX_TOKEN_ENCRYPTED] = sealed
            }
        }
        prefs[PLEX_TOKEN_ENCRYPTED]
    } catch (e: CancellationException) {
        throw e
    } catch (_: IOException) {
        null
    }

    /**
     * Migration des secrets en clair d'une version antérieure : mots de passe chiffrés (Kodi) ou
     * retirés et gardés en mémoire (Jellyfin/Emby), jetons chiffrés. Si l'écriture échoue, la
     * liste renvoyée est migrée en mémoire ; on réessaiera à la lecture suivante.
     */
    private suspend fun migratePlainSecrets(raw: String?): List<Device> = try {
        val prefs = store.edit { p -> p.updateDevices { current -> DeviceCodec.mapValid(current, ::migrate) } }
        DeviceCodec.decode(prefs[DEVICES])
    } catch (e: CancellationException) {
        throw e
    } catch (_: IOException) {
        DeviceCodec.decode(raw).map(::migrate)
    }

    private fun migrate(device: Device): Device {
        if (!DeviceSecrets.hasPlainSecrets(device)) return device
        val plain = device.extras[DeviceSecrets.PASSWORD]
        if (DeviceSecrets.isTransient(device.kind) && !plain.isNullOrEmpty()) transientPasswords.putIfAbsent(device.id, plain)
        return try {
            DeviceSecrets.forStorage(device, plain, secrets)
        } catch (_: Exception) {
            // Chiffrement impossible : les secrets sont retirés plutôt que laissés en clair.
            device.copy(extras = device.extras - DeviceSecrets.SECRET_KEYS)
        }
    }

    /**
     * Applique [op] à la liste enregistrée. Une liste illisible (racine qui n'est pas un tableau,
     * JSON tronqué) n'est pas écrasée silencieusement : elle est mise de côté sous une clé de
     * secours (la première est conservée), puis on repart d'une liste vide.
     */
    private fun MutablePreferences.updateDevices(op: (String?) -> String) {
        val raw = this[DEVICES]
        val usable = if (DeviceCodec.isReadable(raw)) {
            raw
        } else {
            if (this[DEVICES_UNREADABLE] == null) this[DEVICES_UNREADABLE] = checkNotNull(raw)
            null
        }
        this[DEVICES] = op(usable)
    }

    companion object {
        internal val DEVICES = stringPreferencesKey("devices")
        internal val DEVICES_UNREADABLE = stringPreferencesKey("devices_unreadable")
        private val INSTALLATION_ID = stringPreferencesKey("installation_id")
        /** Jeton du compte Plex en clair (versions antérieures) : migré vers [PLEX_TOKEN_ENCRYPTED]. */
        internal val PLEX_TOKEN = stringPreferencesKey("plex_token")
        internal val PLEX_TOKEN_ENCRYPTED = stringPreferencesKey("plex_token_enc")

        /**
         * Fichier illisible (écriture interrompue, stockage abîmé) : on repart d'un état vide au
         * lieu de lever une CorruptionException à chaque lecture, ce qui bloquerait l'application
         * au démarrage.
         */
        fun corruptionHandler(): ReplaceFileCorruptionHandler<Preferences> =
            ReplaceFileCorruptionHandler { emptyPreferences() }

        /** Magasin DataStore de l'application (un seul par fichier et par processus). */
        fun createStore(
            scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
            produceFile: () -> File,
        ): DataStore<Preferences> = PreferenceDataStoreFactory.create(
            corruptionHandler = corruptionHandler(),
            scope = scope,
            produceFile = produceFile,
        )
    }
}

/** Règles de stockage des secrets d'appareil (logique pure, testée unitairement). */
internal object DeviceSecrets {
    const val PASSWORD = "password"
    const val ENCRYPTED_PASSWORD = "password_enc"

    /**
     * Jeton d'appareil : Jellyfin/Emby (`MediaServerController.EXTRA_TOKEN`) et Plex
     * (`PlexController.EXTRA_TOKEN`, jeton propre à l'appareil) partagent la clé `token`.
     */
    const val TOKEN = "token"

    /** Jeton chiffré par [SecretStore] (même format que [ENCRYPTED_PASSWORD]). */
    const val ENCRYPTED_TOKEN = "token_enc"

    /** Jetons en clair (en mémoire uniquement) → clé de leur forme chiffrée (seule enregistrée). */
    val TOKENS = mapOf(TOKEN to ENCRYPTED_TOKEN)

    /** Secrets chiffrés au repos : clé en clair → clé chiffrée. */
    private val SEALED = TOKENS + (PASSWORD to ENCRYPTED_PASSWORD)

    /** Toutes les clés de secrets, en clair ou chiffrés. */
    val SECRET_KEYS = setOf(PASSWORD, ENCRYPTED_PASSWORD) + TOKENS.keys + TOKENS.values

    /** Mot de passe gardé seulement en mémoire, le temps d'obtenir un jeton. */
    fun isTransient(kind: DeviceKind) = kind == DeviceKind.JELLYFIN || kind == DeviceKind.EMBY

    fun hasPlainToken(device: Device): Boolean = TOKENS.keys.any { it in device.extras }

    /** Secret en clair à ne pas laisser dans le stockage (migration d'une version antérieure). */
    fun hasPlainSecrets(device: Device): Boolean = PASSWORD in device.extras || hasPlainToken(device)

    /** Jeton disponible, en clair (en mémoire) ou chiffré (enregistré). */
    fun hasToken(device: Device): Boolean = TOKENS.any { (plain, sealed) -> plain in device.extras || sealed in device.extras }

    /**
     * Forme persistable de [device] : jamais d'extra `password` ni de jeton en clair. Pour Kodi,
     * [plain] (non vide) est chiffré ; un mot de passe vide efface le précédent. [plain] `null` :
     * inchangé. Chaque jeton en clair est chiffré (vide : effacé) ; s'il est identique à celui
     * de [previous], le chiffré existant est repris tel quel. Lève une exception si le
     * chiffrement échoue (rien ne doit alors être écrit).
     */
    fun forStorage(device: Device, plain: String?, secrets: SecretStore, previous: Device? = null): Device {
        val extras = device.extras - PASSWORD
        val withPassword = when {
            plain == null -> extras
            device.kind == DeviceKind.KODI && plain.isNotEmpty() -> extras + (ENCRYPTED_PASSWORD to secrets.encrypt(plain))
            else -> extras - ENCRYPTED_PASSWORD
        }
        return device.copy(extras = sealTokens(withPassword, secrets, previous))
    }

    private fun sealTokens(extras: Map<String, String>, secrets: SecretStore, previous: Device?): Map<String, String> {
        var out = extras
        for ((key, sealedKey) in TOKENS) {
            val token = extras[key] ?: continue
            out = out - key - sealedKey
            if (token.isEmpty()) continue
            val reused = previous?.extras?.get(sealedKey)?.takeIf { secrets.decrypt(it) == token }
            out = out + (sealedKey to (reused ?: secrets.encrypt(token)))
        }
        return out
    }

    /**
     * Remplace le mot de passe et les jetons chiffrés par leur valeur en clair, pour la connexion
     * uniquement (appelé par la fabrique de contrôleurs). Un chiffré illisible est retiré.
     */
    fun reveal(device: Device, secrets: SecretStore): Device {
        if (SEALED.values.none { it in device.extras }) return device
        val extras = device.extras.toMutableMap()
        for ((plainKey, sealedKey) in SEALED) {
            val encrypted = extras.remove(sealedKey) ?: continue
            // Une valeur en clair déjà présente (saisie non enregistrée) reste prioritaire.
            if (plainKey !in extras) secrets.decrypt(encrypted)?.let { extras[plainKey] = it }
        }
        return device.copy(extras = extras)
    }
}

/**
 * Sérialisation de la liste d'appareils, élément par élément : une entrée illisible (type
 * d'appareil inconnu après un retour à une version antérieure, JSON tronqué…) est ignorée à
 * la lecture mais conservée telle quelle, à sa place, à l'écriture, au lieu d'effacer toute la liste.
 */
internal object DeviceCodec {
    private val json = Json { ignoreUnknownKeys = true }

    /** Éléments du tableau ; `null` si [raw] existe mais n'est pas un tableau JSON lisible. */
    private fun elements(raw: String?): List<JsonElement>? =
        if (raw == null) emptyList() else runCatching { json.parseToJsonElement(raw) as? JsonArray }.getOrNull()

    private fun JsonElement.toDevice(): Device? =
        runCatching { json.decodeFromJsonElement(Device.serializer(), this) }.getOrNull()

    /** Identifiant lu directement dans le JSON : permet de remplacer ou supprimer une entrée illisible. */
    private fun JsonElement.rawId(): String? =
        ((this as? JsonObject)?.get("id") as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun Device.toJson(): JsonElement = json.encodeToJsonElement(Device.serializer(), this)

    fun isReadable(raw: String?): Boolean = elements(raw) != null

    /** Appareils lisibles ; en cas de doublons d'identifiant, le premier lisible l'emporte. */
    fun decode(raw: String?): List<Device> = elements(raw).orEmpty().mapNotNull { it.toDevice() }.distinctBy { it.id }

    fun anyValid(raw: String?, predicate: (Device) -> Boolean): Boolean =
        elements(raw).orEmpty().any { e -> e.toDevice()?.let(predicate) == true }

    /**
     * Remplace toutes les entrées portant l'identifiant de [device] (lisibles ou non) par une
     * seule, à la place de la première ; sinon ajoute l'appareil en fin de liste.
     */
    fun upsert(raw: String?, device: Device): String {
        var placed = false
        val out = elements(raw).orEmpty().mapNotNull { e ->
            when {
                e.rawId() != device.id -> e
                placed -> null
                else -> device.toJson().also { placed = true }
            }
        }
        return JsonArray(if (placed) out else out + device.toJson()).toString()
    }

    fun remove(raw: String?, id: String): String =
        JsonArray(elements(raw).orEmpty().filterNot { it.rawId() == id }).toString()

    /**
     * Transformation de toute la liste lisible ; les entrées illisibles sont conservées, en tête.
     * Le dépôt utilise [upsert] / [remove], qui préservent aussi les positions.
     */
    fun update(raw: String?, transform: (List<Device>) -> List<Device>): String {
        val (valid, unreadable) = elements(raw).orEmpty().partition { it.toDevice() != null }
        val devices = transform(valid.mapNotNull { it.toDevice() })
        return JsonArray(unreadable + devices.map { it.toJson() }).toString()
    }

    /** Transforme chaque entrée lisible en place ; les entrées illisibles restent intactes. */
    fun mapValid(raw: String?, transform: (Device) -> Device): String =
        JsonArray(elements(raw).orEmpty().map { e -> e.toDevice()?.let { transform(it).toJson() } ?: e }).toString()
}
