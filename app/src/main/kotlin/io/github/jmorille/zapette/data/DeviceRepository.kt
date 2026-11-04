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
 * Les jetons (Plex, Jellyfin/Emby) y sont stockés en clair, protégés par le bac à sable Android.
 * Aucun mot de passe n'y est écrit en clair :
 * - Kodi (authentification Basic, mot de passe exigé à chaque requête) : chiffré par [SecretStore] ;
 * - Jellyfin/Emby : gardé en mémoire seulement, jusqu'à l'obtention du jeton.
 * Un mot de passe en clair hérité d'une version antérieure est migré à la première lecture.
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
        if (DeviceCodec.anyValid(raw) { DeviceSecrets.PASSWORD in it.extras }) migratePlainPasswords(raw) else DeviceCodec.decode(raw)
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
        // Chiffrement hors transaction : en cas d'échec, rien n'est écrit (et surtout pas en clair).
        val stored = DeviceSecrets.forStorage(device, plain, secrets)
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

    val plexToken: Flow<String?> = data.map { it[PLEX_TOKEN] }

    suspend fun setPlexToken(token: String?) {
        store.edit { if (token == null) it.remove(PLEX_TOKEN) else it[PLEX_TOKEN] = token }
    }

    /**
     * Migration des mots de passe en clair d'une version antérieure : chiffrés (Kodi) ou
     * retirés et gardés en mémoire (Jellyfin/Emby). Si l'écriture échoue, la liste renvoyée
     * est migrée en mémoire ; on réessaiera à la lecture suivante.
     */
    private suspend fun migratePlainPasswords(raw: String?): List<Device> = try {
        val prefs = store.edit { p -> p.updateDevices { current -> DeviceCodec.mapValid(current, ::migrate) } }
        DeviceCodec.decode(prefs[DEVICES])
    } catch (e: CancellationException) {
        throw e
    } catch (_: IOException) {
        DeviceCodec.decode(raw).map(::migrate)
    }

    private fun migrate(device: Device): Device {
        val plain = device.extras[DeviceSecrets.PASSWORD] ?: return device
        if (DeviceSecrets.isTransient(device.kind) && plain.isNotEmpty()) transientPasswords.putIfAbsent(device.id, plain)
        return try {
            DeviceSecrets.forStorage(device, plain, secrets)
        } catch (_: Exception) {
            // Chiffrement impossible : le mot de passe est retiré plutôt que laissé en clair.
            device.copy(extras = device.extras - DeviceSecrets.PASSWORD - DeviceSecrets.ENCRYPTED_PASSWORD)
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
        private val PLEX_TOKEN = stringPreferencesKey("plex_token")

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

/** Règles de stockage des mots de passe (logique pure, testée unitairement). */
internal object DeviceSecrets {
    const val PASSWORD = "password"
    const val ENCRYPTED_PASSWORD = "password_enc"

    /** Mot de passe gardé seulement en mémoire, le temps d'obtenir un jeton. */
    fun isTransient(kind: DeviceKind) = kind == DeviceKind.JELLYFIN || kind == DeviceKind.EMBY

    /**
     * Forme persistable de [device] : jamais d'extra `password`. Pour Kodi, [plain] (non vide)
     * est chiffré ; un mot de passe vide efface le précédent. [plain] `null` : inchangé.
     */
    fun forStorage(device: Device, plain: String?, secrets: SecretStore): Device {
        val extras = device.extras - PASSWORD
        return when {
            plain == null -> device.copy(extras = extras)
            device.kind == DeviceKind.KODI && plain.isNotEmpty() ->
                device.copy(extras = extras + (ENCRYPTED_PASSWORD to secrets.encrypt(plain)))
            else -> device.copy(extras = extras - ENCRYPTED_PASSWORD)
        }
    }

    /** Remplace le mot de passe chiffré par sa valeur en clair, pour la connexion uniquement. */
    fun reveal(device: Device, secrets: SecretStore): Device {
        val encrypted = device.extras[ENCRYPTED_PASSWORD] ?: return device
        val extras = device.extras - ENCRYPTED_PASSWORD
        val plain = secrets.decrypt(encrypted) ?: return device.copy(extras = extras)
        return device.copy(extras = extras + (PASSWORD to plain))
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
