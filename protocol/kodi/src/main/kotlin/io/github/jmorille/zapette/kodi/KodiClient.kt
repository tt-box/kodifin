package io.github.jmorille.zapette.kodi

import io.github.jmorille.zapette.model.RemoteException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

data class KodiPlayer(val id: Int, val type: String)

data class KodiPlayerProperties(
    val speed: Int,
    val timeMs: Long,
    val totalTimeMs: Long,
    val percentage: Double,
)

data class KodiAppProperties(val volume: Int, val muted: Boolean, val version: String?)

data class KodiMediaItem(val id: Int?, val label: String, val type: String?)

/** API typée au-dessus d'un [KodiTransport]. */
class KodiClient(private val transport: KodiTransport) {

    val notifications get() = transport.notifications

    suspend fun call(method: String, params: JsonObject? = null): JsonElement = transport.call(method, params)

    suspend fun ping(): Boolean = (call(KodiMethod.PING) as? JsonPrimitive)?.contentOrNull == "pong"

    /** Ferme le transport sous-jacent (WebSocket notamment). */
    fun close() = transport.close()

    suspend fun apiVersion(): String {
        val v = call(KodiMethod.VERSION).obj(KodiMethod.VERSION)["version"].obj(KodiMethod.VERSION)
        return listOf("major", "minor", "patch").joinToString(".") { v[it].primitive()?.contentOrNull ?: "0" }
    }

    // --- Entrées ---------------------------------------------------------
    suspend fun input(method: String) {
        call(method)
    }

    suspend fun executeAction(action: String) {
        call(KodiMethod.INPUT_EXECUTE_ACTION, buildJsonObject { put("action", action) })
    }

    suspend fun sendText(text: String, done: Boolean = true) {
        call(KodiMethod.INPUT_SEND_TEXT, buildJsonObject { put("text", text); put("done", done) })
    }

    // --- Lecteur ---------------------------------------------------------
    suspend fun activePlayers(): List<KodiPlayer> =
        call(KodiMethod.PLAYER_GET_ACTIVE).arr(KodiMethod.PLAYER_GET_ACTIVE).mapNotNull {
            val o = it as? JsonObject ?: return@mapNotNull null
            // Un lecteur sans identifiant exploitable est ignoré plutôt que ciblé au hasard.
            val id = o["playerid"].primitive()?.intOrNull ?: return@mapNotNull null
            KodiPlayer(id, o["type"].primitive()?.contentOrNull ?: "unknown")
        }

    suspend fun playPause(playerId: Int) = call(KodiMethod.PLAYER_PLAY_PAUSE, playerParams(playerId))

    suspend fun stop(playerId: Int) = call(KodiMethod.PLAYER_STOP, playerParams(playerId))

    suspend fun goTo(playerId: Int, next: Boolean) = call(
        KodiMethod.PLAYER_GO_TO,
        buildJsonObject { put("playerid", playerId); put("to", if (next) "next" else "previous") },
    )

    /** Avance/recul relatif : `smallforward`, `smallbackward`, `bigforward`, `bigbackward`. */
    suspend fun seekStep(playerId: Int, step: String) = call(
        KodiMethod.PLAYER_SEEK,
        buildJsonObject { put("playerid", playerId); putJsonObject("value") { put("step", step) } },
    )

    suspend fun seekTo(playerId: Int, positionMs: Long) = call(
        KodiMethod.PLAYER_SEEK,
        buildJsonObject {
            put("playerid", playerId)
            putJsonObject("value") {
                // Une position négative produirait des champs négatifs refusés par Kodi.
                val ms = positionMs.coerceAtLeast(0)
                putJsonObject("time") {
                    put("hours", ms / 3_600_000)
                    put("minutes", (ms / 60_000) % 60)
                    put("seconds", (ms / 1000) % 60)
                    put("milliseconds", ms % 1000)
                }
            }
        },
    )

    suspend fun openUrl(url: String) = call(
        KodiMethod.PLAYER_OPEN,
        buildJsonObject { putJsonObject("item") { put("file", url) } },
    )

    suspend fun playerProperties(playerId: Int): KodiPlayerProperties {
        val o = call(
            KodiMethod.PLAYER_GET_PROPERTIES,
            buildJsonObject {
                put("playerid", playerId)
                putJsonArray("properties") { listOf("speed", "time", "totaltime", "percentage").forEach { add(JsonPrimitive(it)) } }
            },
        ).obj(KodiMethod.PLAYER_GET_PROPERTIES)
        return KodiPlayerProperties(
            speed = o["speed"].primitive()?.intOrNull ?: 0,
            timeMs = o["time"]?.let(::kodiTimeToMs) ?: 0,
            totalTimeMs = o["totaltime"]?.let(::kodiTimeToMs) ?: 0,
            percentage = o["percentage"].primitive()?.doubleOrNull ?: 0.0,
        )
    }

    suspend fun playerItem(playerId: Int): KodiMediaItem {
        val item = call(
            KodiMethod.PLAYER_GET_ITEM,
            buildJsonObject {
                put("playerid", playerId)
                putJsonArray("properties") { add(JsonPrimitive("title")) }
            },
        ).obj(KodiMethod.PLAYER_GET_ITEM)["item"].obj(KodiMethod.PLAYER_GET_ITEM)
        val title = item["title"].primitive()?.contentOrNull?.takeIf { it.isNotBlank() }
        return KodiMediaItem(
            id = item["id"].primitive()?.intOrNull,
            label = title ?: item["label"].primitive()?.contentOrNull.orEmpty(),
            type = item["type"].primitive()?.contentOrNull,
        )
    }

    // --- Application -----------------------------------------------------
    suspend fun appProperties(): KodiAppProperties {
        val o = call(
            KodiMethod.APP_GET_PROPERTIES,
            buildJsonObject { putJsonArray("properties") { listOf("volume", "muted", "version").forEach { add(JsonPrimitive(it)) } } },
        ).obj(KodiMethod.APP_GET_PROPERTIES)
        val version = (o["version"] as? JsonObject)?.let { v ->
            "${v["major"].primitive()?.contentOrNull ?: "0"}.${v["minor"].primitive()?.contentOrNull ?: "0"}"
        }
        return KodiAppProperties(
            volume = o["volume"].primitive()?.intOrNull ?: 0,
            muted = o["muted"].primitive()?.booleanOrNull ?: false,
            version = version,
        )
    }

    suspend fun setVolume(percent: Int) =
        call(KodiMethod.APP_SET_VOLUME, buildJsonObject { put("volume", percent.coerceIn(0, 100)) })

    /** `increment` ou `decrement`. */
    suspend fun stepVolume(direction: String) =
        call(KodiMethod.APP_SET_VOLUME, buildJsonObject { put("volume", direction) })

    suspend fun toggleMute() = call(KodiMethod.APP_SET_MUTE, buildJsonObject { put("mute", "toggle") })

    // --- Bibliothèques et système ----------------------------------------
    suspend fun recentMovies(limit: Int = 25): List<KodiMediaItem> =
        listItems(KodiMethod.VIDEO_GET_RECENT_MOVIES, "movies", "movieid", limit)

    suspend fun recentEpisodes(limit: Int = 25): List<KodiMediaItem> =
        listItems(KodiMethod.VIDEO_GET_RECENT_EPISODES, "episodes", "episodeid", limit)

    suspend fun recentAlbums(limit: Int = 25): List<KodiMediaItem> =
        listItems(KodiMethod.AUDIO_GET_RECENT_ALBUMS, "albums", "albumid", limit)

    suspend fun scanVideoLibrary() = call(KodiMethod.VIDEO_SCAN)

    suspend fun scanAudioLibrary() = call(KodiMethod.AUDIO_SCAN)

    suspend fun showNotification(title: String, message: String) = call(
        KodiMethod.GUI_SHOW_NOTIFICATION,
        buildJsonObject { put("title", title); put("message", message) },
    )

    suspend fun activateWindow(window: String) =
        call(KodiMethod.GUI_ACTIVATE_WINDOW, buildJsonObject { put("window", window) })

    suspend fun systemAction(method: String) = call(method)

    private suspend fun listItems(method: String, key: String, idKey: String, limit: Int): List<KodiMediaItem> {
        val result = call(
            method,
            buildJsonObject { putJsonObject("limits") { put("start", 0); put("end", limit) } },
        ).obj(method)
        val items = result[key] as? JsonArray ?: return emptyList()
        return items.mapNotNull {
            val o = it as? JsonObject ?: return@mapNotNull null
            KodiMediaItem(o[idKey].primitive()?.intOrNull, o["label"].primitive()?.contentOrNull.orEmpty(), key)
        }
    }

    private fun playerParams(playerId: Int) = buildJsonObject { put("playerid", playerId) }

    companion object {
        internal fun kodiTimeToMs(element: JsonElement): Long {
            val o = element as? JsonObject ?: return 0
            fun f(name: String) = o[name].primitive()?.longOrNull ?: 0L
            return f("hours") * 3_600_000 + f("minutes") * 60_000 + f("seconds") * 1000 + f("milliseconds")
        }
    }
}

/** Valeur primitive ou `null` (objet, tableau, absence). */
private fun JsonElement?.primitive(): JsonPrimitive? = this as? JsonPrimitive

/** Exige un objet JSON ; sinon la réponse de Kodi est jugée invalide. */
private fun JsonElement?.obj(method: String): JsonObject =
    this as? JsonObject ?: throw RemoteException("Réponse Kodi inattendue pour $method")

/** Exige un tableau JSON ; sinon la réponse de Kodi est jugée invalide. */
private fun JsonElement?.arr(method: String): JsonArray =
    this as? JsonArray ?: throw RemoteException("Réponse Kodi inattendue pour $method")
