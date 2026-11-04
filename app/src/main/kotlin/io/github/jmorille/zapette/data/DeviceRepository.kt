package io.github.jmorille.zapette.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import io.github.jmorille.zapette.model.Device
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.util.UUID

private val Context.store by preferencesDataStore(name = "zapette")

/** Appareils enregistrés et réglages locaux (stockage privé de l'application, exclu des sauvegardes). */
class DeviceRepository(private val context: Context) {
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(Device.serializer())

    val saved: Flow<List<Device>> = context.store.data.map { decode(it) }

    private fun decode(prefs: Preferences): List<Device> =
        prefs[DEVICES]?.let { runCatching { json.decodeFromString(serializer, it) }.getOrNull() } ?: emptyList()

    suspend fun get(id: String): Device? = saved.first().firstOrNull { it.id == id }

    suspend fun save(device: Device) {
        context.store.edit { prefs ->
            val list = decode(prefs).filterNot { it.id == device.id } + device
            prefs[DEVICES] = json.encodeToString(serializer, list)
        }
    }

    suspend fun delete(id: String) {
        context.store.edit { prefs -> prefs[DEVICES] = json.encodeToString(serializer, decode(prefs).filterNot { it.id == id }) }
    }

    /** Identifiant aléatoire local, utilisé comme DeviceId Jellyfin/Emby et client Plex. Jamais transmis ailleurs. */
    suspend fun installationId(): String {
        context.store.data.first()[INSTALLATION_ID]?.let { return it }
        val id = UUID.randomUUID().toString()
        context.store.edit { if (it[INSTALLATION_ID] == null) it[INSTALLATION_ID] = id }
        return context.store.data.first()[INSTALLATION_ID] ?: id
    }

    val plexToken: Flow<String?> = context.store.data.map { it[PLEX_TOKEN] }

    suspend fun setPlexToken(token: String?) {
        context.store.edit { if (token == null) it.remove(PLEX_TOKEN) else it[PLEX_TOKEN] = token }
    }

    private companion object {
        val DEVICES = stringPreferencesKey("devices")
        val INSTALLATION_ID = stringPreferencesKey("installation_id")
        val PLEX_TOKEN = stringPreferencesKey("plex_token")
    }
}
