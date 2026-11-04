package io.github.jmorille.zapette.kodi

import io.github.jmorille.zapette.model.RemoteException
import io.github.jmorille.zapette.model.UnsupportedActionException
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

/** Nom et version de l'application (ex. « Kodi », « 21.2 »), via `Application.GetProperties`. */
data class KodiAppInfo(val name: String, val version: String?)

/**
 * Élément en cours ou listé. Pour un épisode (`type` = `episode`), [showTitle], [season],
 * [episode] et [tvShowId] sont renseignés si Kodi les connaît ; `null` sinon.
 */
data class KodiMediaItem(
    val id: Int?,
    val label: String,
    val type: String?,
    val showTitle: String? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val tvShowId: Int? = null,
)

/** API typée au-dessus d'un [KodiTransport]. */
class KodiClient(private val transport: KodiTransport) {

    val notifications get() = transport.notifications

    /** Le transport pousse l'état (WebSocket) : inutile d'interroger Kodi en continu. */
    val pushesNotifications: Boolean get() = transport.pushesNotifications

    /**
     * Appel JSON-RPC brut. Une méthode absente du serveur (erreur -32601, « Method not found »)
     * devient une [UnsupportedActionException] : l'interface peut alors masquer la commande.
     */
    suspend fun call(method: String, params: JsonObject? = null): JsonElement = try {
        transport.call(method, params)
    } catch (e: KodiRpcException) {
        if (e.code == METHOD_NOT_FOUND) {
            throw UnsupportedActionException("Commande Kodi « $method » non prise en charge par ce serveur")
        }
        throw e
    }

    suspend fun ping(): Boolean = (call(KodiMethod.PING) as? JsonPrimitive)?.contentOrNull == "pong"

    /** Téléchargeur d'images du transport HTTP (même hôte, mêmes identifiants) ; `null` pour un autre transport. */
    internal val httpImages: KodiImageFetcher? get() = (transport as? KodiHttpTransport)?.images

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

    /**
     * Saut prédéfini pour JSON-RPC < v12 (Kodi ≤ 18) : `value` est directement la chaîne
     * `smallforward`, `smallbackward`, `bigforward` ou `bigbackward`.
     */
    suspend fun seekStepLegacy(playerId: Int, step: String) = call(
        KodiMethod.PLAYER_SEEK,
        buildJsonObject { put("playerid", playerId); put("value", step) },
    )

    /**
     * Position absolue. [legacy] (JSON-RPC < v12) : `value` est directement l'objet temps ;
     * sinon `value:{time:{…}}`.
     */
    suspend fun seekTo(playerId: Int, positionMs: Long, legacy: Boolean = false) = call(
        KodiMethod.PLAYER_SEEK,
        buildJsonObject {
            put("playerid", playerId)
            // Une position négative produirait des champs négatifs refusés par Kodi.
            val time = timeObject(positionMs.coerceAtLeast(0))
            if (legacy) put("value", time) else putJsonObject("value") { put("time", time) }
        },
    )

    private fun timeObject(ms: Long) = buildJsonObject {
        put("hours", ms / 3_600_000)
        put("minutes", (ms / 60_000) % 60)
        put("seconds", (ms / 1000) % 60)
        put("milliseconds", ms % 1000)
    }

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
                putJsonArray("properties") { PLAYER_ITEM_PROPERTIES.forEach { add(JsonPrimitive(it)) } }
            },
        ).obj(KodiMethod.PLAYER_GET_ITEM)["item"].obj(KodiMethod.PLAYER_GET_ITEM)
        val title = item["title"].primitive()?.contentOrNull?.takeIf { it.isNotBlank() }
        // Kodi renvoie -1 (ou une chaîne vide) pour les champs sans objet : ignorés.
        return KodiMediaItem(
            id = item["id"].primitive()?.intOrNull,
            label = title ?: item["label"].primitive()?.contentOrNull.orEmpty(),
            type = item["type"].primitive()?.contentOrNull,
            showTitle = item["showtitle"].primitive()?.contentOrNull?.takeIf { it.isNotBlank() },
            season = item["season"].primitive()?.intOrNull?.takeIf { it >= 0 },
            episode = item["episode"].primitive()?.intOrNull?.takeIf { it >= 0 },
            tvShowId = item["tvshowid"].primitive()?.intOrNull?.takeIf { it >= 0 },
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

    suspend fun appInfo(): KodiAppInfo {
        val o = call(
            KodiMethod.APP_GET_PROPERTIES,
            buildJsonObject { putJsonArray("properties") { listOf("name", "version").forEach { add(JsonPrimitive(it)) } } },
        ).obj(KodiMethod.APP_GET_PROPERTIES)
        val version = (o["version"] as? JsonObject)?.let { v ->
            val base = "${v["major"].primitive()?.contentOrNull ?: "0"}.${v["minor"].primitive()?.contentOrNull ?: "0"}"
            // Étiquette affichée seulement pour les préversions (« alpha1 », « rc2 »…).
            val tag = v["tag"].primitive()?.contentOrNull?.takeIf { it.isNotBlank() && it != "stable" }
            if (tag != null) "$base $tag" else base
        }
        return KodiAppInfo(o["name"].primitive()?.contentOrNull?.takeIf { it.isNotBlank() } ?: "Kodi", version)
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

    // --- Contrôles avancés du lecteur -------------------------------------

    /** Propriétés arbitraires du lecteur (objet brut, analysé par l'appelant). */
    suspend fun playerPropertiesOf(playerId: Int, properties: List<String>): JsonObject = call(
        KodiMethod.PLAYER_GET_PROPERTIES,
        buildJsonObject {
            put("playerid", playerId)
            putJsonArray("properties") { properties.forEach { add(JsonPrimitive(it)) } }
        },
    ).obj(KodiMethod.PLAYER_GET_PROPERTIES)

    /** Saut relatif en secondes (`Player.Seek {value:{seconds:n}}`). */
    suspend fun seekSeconds(playerId: Int, seconds: Long) = call(
        KodiMethod.PLAYER_SEEK,
        buildJsonObject { put("playerid", playerId); putJsonObject("value") { put("seconds", seconds) } },
    )

    /** `increment` ou `decrement`. */
    suspend fun setSpeed(playerId: Int, speed: String) = call(
        KodiMethod.PLAYER_SET_SPEED,
        buildJsonObject { put("playerid", playerId); put("speed", speed) },
    )

    /** Index de piste, ou `next` / `previous`. */
    suspend fun setAudioStream(playerId: Int, stream: JsonPrimitive) = call(
        KodiMethod.PLAYER_SET_AUDIO_STREAM,
        buildJsonObject { put("playerid", playerId); put("stream", stream) },
    )

    /** Index de sous-titre, ou `next` / `previous` / `on` / `off` ; [enable] affiche la piste choisie. */
    suspend fun setSubtitle(playerId: Int, subtitle: JsonPrimitive, enable: Boolean? = null) = call(
        KodiMethod.PLAYER_SET_SUBTITLE,
        buildJsonObject {
            put("playerid", playerId)
            put("subtitle", subtitle)
            if (enable != null) put("enable", enable)
        },
    )

    /** `off`, `one`, `all`. */
    suspend fun setRepeat(playerId: Int, repeat: String) = call(
        KodiMethod.PLAYER_SET_REPEAT,
        buildJsonObject { put("playerid", playerId); put("repeat", repeat) },
    )

    suspend fun setShuffle(playerId: Int, shuffle: Boolean) = call(
        KodiMethod.PLAYER_SET_SHUFFLE,
        buildJsonObject { put("playerid", playerId); put("shuffle", shuffle) },
    )

    /** Saut à une position de la playlist du lecteur. */
    suspend fun goToPosition(playerId: Int, position: Int) = call(
        KodiMethod.PLAYER_GO_TO,
        buildJsonObject { put("playerid", playerId); put("to", position) },
    )

    /** `Player.Open` avec un élément arbitraire (`{movieid:n}`, `{file:…}`, `{playlistid,position}`…). */
    suspend fun open(item: JsonObject) = call(KodiMethod.PLAYER_OPEN, buildJsonObject { put("item", item) })

    /** Valeurs d'InfoLabels (`XBMC.GetInfoLabels`), ex. `Player.ChapterCount`. */
    suspend fun infoLabels(labels: List<String>): Map<String, String> {
        val o = call(
            KodiMethod.XBMC_GET_INFO_LABELS,
            buildJsonObject { putJsonArray("labels") { labels.forEach { add(JsonPrimitive(it)) } } },
        ).obj(KodiMethod.XBMC_GET_INFO_LABELS)
        return o.mapNotNull { (k, v) -> v.primitive()?.contentOrNull?.let { k to it } }.toMap()
    }

    // --- Interface et système ---------------------------------------------
    suspend fun setFullscreen(value: String = "toggle") =
        call(KodiMethod.GUI_SET_FULLSCREEN, buildJsonObject { put("fullscreen", value) })

    suspend fun activateWindow(window: String, parameters: List<String>) = call(
        KodiMethod.GUI_ACTIVATE_WINDOW,
        buildJsonObject {
            put("window", window)
            putJsonArray("parameters") { parameters.forEach { add(JsonPrimitive(it)) } }
        },
    )

    /** Propriétés booléennes de `System.GetProperties` (`canshutdown`…), absentes = `false`. */
    suspend fun systemProperties(properties: List<String>): Map<String, Boolean> {
        val o = call(
            KodiMethod.SYSTEM_GET_PROPERTIES,
            buildJsonObject { putJsonArray("properties") { properties.forEach { add(JsonPrimitive(it)) } } },
        ).obj(KodiMethod.SYSTEM_GET_PROPERTIES)
        return properties.associateWith { o[it].primitive()?.booleanOrNull ?: false }
    }

    suspend fun cleanVideoLibrary() = call(KodiMethod.VIDEO_CLEAN)

    suspend fun cleanAudioLibrary() = call(KodiMethod.AUDIO_CLEAN)

    // --- Playlists ----------------------------------------------------------
    suspend fun playlistItems(playlistId: Int, properties: List<String>): JsonArray {
        val o = call(
            KodiMethod.PLAYLIST_GET_ITEMS,
            buildJsonObject {
                put("playlistid", playlistId)
                putJsonArray("properties") { properties.forEach { add(JsonPrimitive(it)) } }
            },
        ).obj(KodiMethod.PLAYLIST_GET_ITEMS)
        return o["items"] as? JsonArray ?: JsonArray(emptyList())
    }

    /** [item] : un `Playlist.Item` ou un tableau de `Playlist.Item`. */
    suspend fun playlistAdd(playlistId: Int, item: JsonElement) = call(
        KodiMethod.PLAYLIST_ADD,
        buildJsonObject { put("playlistid", playlistId); put("item", item) },
    )

    suspend fun playlistInsert(playlistId: Int, position: Int, item: JsonElement) = call(
        KodiMethod.PLAYLIST_INSERT,
        buildJsonObject { put("playlistid", playlistId); put("position", position); put("item", item) },
    )

    suspend fun playlistRemove(playlistId: Int, position: Int) = call(
        KodiMethod.PLAYLIST_REMOVE,
        buildJsonObject { put("playlistid", playlistId); put("position", position) },
    )

    suspend fun playlistClear(playlistId: Int) =
        call(KodiMethod.PLAYLIST_CLEAR, buildJsonObject { put("playlistid", playlistId) })

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
        /** Propriétés lues par `Player.GetItem` : titre et, pour un épisode, série, saison et numéro. */
        internal val PLAYER_ITEM_PROPERTIES = listOf("title", "showtitle", "season", "episode", "tvshowid")

        /** Code JSON-RPC 2.0 « méthode introuvable ». */
        const val METHOD_NOT_FOUND = -32601

        /** Code JSON-RPC 2.0 « paramètres invalides ». */
        const val INVALID_PARAMS = -32602

        internal fun kodiTimeToMs(element: JsonElement): Long {
            val o = element as? JsonObject ?: return 0
            fun f(name: String) = o[name].primitive()?.longOrNull ?: 0L
            return f("hours") * 3_600_000 + f("minutes") * 60_000 + f("seconds") * 1000 + f("milliseconds")
        }
    }
}

/** Valeur primitive ou `null` (objet, tableau, absence). */
internal fun JsonElement?.primitive(): JsonPrimitive? = this as? JsonPrimitive

/** Exige un objet JSON ; sinon la réponse de Kodi est jugée invalide. */
internal fun JsonElement?.obj(method: String): JsonObject =
    this as? JsonObject ?: throw RemoteException("Réponse Kodi inattendue pour $method")

/** Exige un tableau JSON ; sinon la réponse de Kodi est jugée invalide. */
internal fun JsonElement?.arr(method: String): JsonArray =
    this as? JsonArray ?: throw RemoteException("Réponse Kodi inattendue pour $method")
