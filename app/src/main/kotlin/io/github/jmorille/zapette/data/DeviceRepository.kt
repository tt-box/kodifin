package io.github.jmorille.zapette.data

import android.content.Context
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import io.github.jmorille.zapette.model.Device
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import java.io.IOException
import java.util.UUID

/**
 * Fichier illisible (écriture interrompue, stockage abîmé) : on repart d'un état vide au lieu
 * de lever une CorruptionException à chaque lecture, ce qui bloquerait l'application au démarrage.
 */
private val Context.store by preferencesDataStore(
    name = "zapette",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

/**
 * Appareils enregistrés et réglages locaux (stockage privé de l'application, exclu des sauvegardes).
 * Les jetons (Plex, Jellyfin/Emby) y sont stockés en clair, protégés par le bac à sable Android ;
 * aucun mot de passe n'y est écrit.
 */
class DeviceRepository(private val context: Context) {

    /** Lecture tolérante aux erreurs d'entrée/sortie (les autres erreurs restent visibles). */
    private val data: Flow<Preferences> = context.store.data.catch { e ->
        if (e is IOException) emit(emptyPreferences()) else throw e
    }

    val saved: Flow<List<Device>> = data.map { DeviceCodec.decode(it[DEVICES]) }

    suspend fun get(id: String): Device? = saved.first().firstOrNull { it.id == id }

    suspend fun save(device: Device) {
        context.store.edit { prefs ->
            prefs[DEVICES] = DeviceCodec.update(prefs[DEVICES]) { list -> list.filterNot { it.id == device.id } + device }
        }
    }

    suspend fun delete(id: String) {
        context.store.edit { prefs ->
            prefs[DEVICES] = DeviceCodec.update(prefs[DEVICES]) { list -> list.filterNot { it.id == id } }
        }
    }

    /**
     * Identifiant aléatoire local, utilisé comme DeviceId Jellyfin/Emby et client Plex. Jamais transmis ailleurs.
     * Créé dans une seule transaction DataStore : deux appels simultanés obtiennent la même valeur.
     */
    suspend fun installationId(): String {
        data.first()[INSTALLATION_ID]?.let { return it }
        val prefs = context.store.edit { if (it[INSTALLATION_ID] == null) it[INSTALLATION_ID] = UUID.randomUUID().toString() }
        return checkNotNull(prefs[INSTALLATION_ID])
    }

    val plexToken: Flow<String?> = data.map { it[PLEX_TOKEN] }

    suspend fun setPlexToken(token: String?) {
        context.store.edit { if (token == null) it.remove(PLEX_TOKEN) else it[PLEX_TOKEN] = token }
    }

    private companion object {
        val DEVICES = stringPreferencesKey("devices")
        val INSTALLATION_ID = stringPreferencesKey("installation_id")
        val PLEX_TOKEN = stringPreferencesKey("plex_token")
    }
}

/**
 * Sérialisation de la liste d'appareils, élément par élément : une entrée illisible (type
 * d'appareil inconnu après un retour à une version antérieure, JSON tronqué…) est ignorée à
 * la lecture mais conservée telle quelle à l'écriture, au lieu d'effacer toute la liste.
 */
internal object DeviceCodec {
    private val json = Json { ignoreUnknownKeys = true }

    private fun elements(raw: String?): List<JsonElement> =
        raw?.let { runCatching { json.parseToJsonElement(it) as? JsonArray }.getOrNull() }.orEmpty()

    private fun JsonElement.toDevice(): Device? =
        runCatching { json.decodeFromJsonElement(Device.serializer(), this) }.getOrNull()

    fun decode(raw: String?): List<Device> = elements(raw).mapNotNull { it.toDevice() }

    fun update(raw: String?, transform: (List<Device>) -> List<Device>): String {
        val (valid, unreadable) = elements(raw).partition { it.toDevice() != null }
        val devices = transform(valid.mapNotNull { it.toDevice() })
        return JsonArray(unreadable + devices.map { json.encodeToJsonElement(Device.serializer(), it) }).toString()
    }
}
