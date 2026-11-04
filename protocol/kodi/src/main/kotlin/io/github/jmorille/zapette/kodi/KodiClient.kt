package io.github.jmorille.zapette.kodi

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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

    suspend fun ping(): Boolean = call(KodiMethod.PING).jsonPrimitive.contentOrNull == "pong"

    suspend fun apiVersion(): String {
        val v = call(KodiMethod.VERSION).jsonObject["version"]!!.jsonObject
        return listOf("major", "minor", "patch").joinToString(".") { v[it]?.jsonPrimitive?.contentOrNull ?: "0" }
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
        call(KodiMethod.PLAYER_GET_ACTIVE).jsonArray.map {
            val o = it.jsonObject
            KodiPlayer(o["playerid"]!!.jsonPrimitive.intOrNull ?: 0, o["type"]?.jsonPrimitive?.contentOrNull ?: "unknown")
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
                putJsonObject("time") {
                    put("hours", positionMs / 3_600_000)
                    put("minutes", (positionMs / 60_000) % 60)
                    put("seconds", (positionMs / 1000) % 60)
                    put("milliseconds", positionMs % 1000)
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
        ).jsonObject
        return KodiPlayerProperties(
            speed = o["speed"]?.jsonPrimitive?.intOrNull ?: 0,
            timeMs = o["time"]?.let(::kodiTimeToMs) ?: 0,
            totalTimeMs = o["totaltime"]?.let(::kodiTimeToMs) ?: 0,
            percentage = o["percentage"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
        )
    }

    suspend fun playerItem(playerId: Int): KodiMediaItem {
        val item = call(
            KodiMethod.PLAYER_GET_ITEM,
            buildJsonObject {
                put("playerid", playerId)
                putJsonArray("properties") { add(JsonPrimitive("title")) }
            },
        ).jsonObject["item"]!!.jsonObject
        val title = item["title"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
        return KodiMediaItem(
            id = item["id"]?.jsonPrimitive?.intOrNull,
            label = title ?: item["label"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            type = item["type"]?.jsonPrimitive?.contentOrNull,
        )
    }

    // --- Application -----------------------------------------------------
    suspend fun appProperties(): KodiAppProperties {
        val o = call(
            KodiMethod.APP_GET_PROPERTIES,
            buildJsonObject { putJsonArray("properties") { listOf("volume", "muted", "version").forEach { add(JsonPrimitive(it)) } } },
        ).jsonObject
        val version = o["version"]?.jsonObject?.let { v ->
            "${v["major"]?.jsonPrimitive?.contentOrNull}.${v["minor"]?.jsonPrimitive?.contentOrNull}"
        }
        return KodiAppProperties(
            volume = o["volume"]?.jsonPrimitive?.intOrNull ?: 0,
            muted = o["muted"]?.jsonPrimitive?.booleanOrNull ?: false,
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
        ).jsonObject
        val items = result[key] as? JsonArray ?: return emptyList()
        return items.map {
            val o = it.jsonObject
            KodiMediaItem(o[idKey]?.jsonPrimitive?.intOrNull, o["label"]?.jsonPrimitive?.contentOrNull.orEmpty(), key)
        }
    }

    private fun playerParams(playerId: Int) = buildJsonObject { put("playerid", playerId) }

    companion object {
        internal fun kodiTimeToMs(element: JsonElement): Long {
            val o = element.jsonObject
            fun f(name: String) = o[name]?.jsonPrimitive?.longOrNull ?: 0L
            return f("hours") * 3_600_000 + f("minutes") * 60_000 + f("seconds") * 1000 + f("milliseconds")
        }
    }
}
