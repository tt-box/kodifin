package io.github.jmorille.zapette.kodi

import io.github.jmorille.zapette.model.MediaEntry
import io.github.jmorille.zapette.model.MediaSection
import io.github.jmorille.zapette.model.RemoteException
import io.github.jmorille.zapette.model.UnsupportedActionException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Identifiant d'élément Kodi, encodé « type:valeur » dans [MediaEntry.id] :
 * `movieid:12`, `tvshowid:3`, `season:3:1` (série 3, saison 1), `episodeid:40`, `artistid:5`,
 * `albumid:7`, `songid:90`, `musicvideoid:2`, `channelgroupid:1`, `channelid:8`,
 * `addonid:plugin.video.x`, `file:smb://…`, `window:videos|chemin`.
 */
internal data class KodiEntryId(val type: String, val value: String) {
    override fun toString() = "$type:$value"

    /** Valeur numérique (identifiant de bibliothèque), sinon erreur explicite. */
    fun intValue(): Int = value.toIntOrNull() ?: throw RemoteException("Identifiant Kodi invalide : $this")

    companion object {
        /** Découpe au premier « : » seulement : les chemins (`smb://…`) en contiennent d'autres. */
        fun parse(id: String): KodiEntryId {
            val sep = id.indexOf(':')
            if (sep <= 0) throw UnsupportedActionException("Élément Kodi inconnu : $id")
            return KodiEntryId(id.substring(0, sep), id.substring(sep + 1))
        }
    }
}

/** Parcours de la bibliothèque Kodi (vidéo, musique, PVR, favoris, add-ons) et lancement. */
internal class KodiLibrary(private val client: KodiClient) {

    suspend fun sections(): List<MediaSection> = buildList {
        add(MediaSection(SECTION_MOVIES, "Films"))
        add(MediaSection(SECTION_TV_SHOWS, "Séries"))
        add(MediaSection(SECTION_ARTISTS, "Musique — artistes"))
        add(MediaSection(SECTION_ALBUMS, "Musique — albums"))
        if (hasPvr()) add(MediaSection(SECTION_TV, "Chaînes TV"))
        add(MediaSection(SECTION_FAVOURITES, "Favoris"))
        add(MediaSection(SECTION_ADDONS, "Add-ons vidéo"))
    }

    /** Le PVR est jugé disponible si au moins un groupe de chaînes TV est renvoyé. */
    private suspend fun hasPvr(): Boolean = try {
        val o = client.call(
            KodiMethod.PVR_GET_CHANNEL_GROUPS,
            buildJsonObject { put("channeltype", "tv"); limits(0, 1) },
        ) as? JsonObject
        (o?.get("channelgroups") as? JsonArray)?.isNotEmpty() == true
    } catch (_: RemoteException) {
        false // PVR désactivé : Kodi répond par une erreur JSON-RPC.
    } catch (_: UnsupportedActionException) {
        false
    }

    suspend fun browse(parentId: String, offset: Int, limit: Int): List<MediaEntry> {
        val start = offset.coerceAtLeast(0)
        val end = start + limit.coerceAtLeast(0)
        return when (parentId) {
            SECTION_MOVIES -> list(KodiMethod.VIDEO_GET_MOVIES, "movies", "movieid", start, end, listOf("year"), sortByLabel = true) {
                it["year"].primitive()?.intOrNull?.takeIf { y -> y > 0 }?.toString()
            }
            SECTION_TV_SHOWS -> list(
                KodiMethod.VIDEO_GET_TV_SHOWS, "tvshows", "tvshowid", start, end, listOf("year"),
                sortByLabel = true, browsable = true,
            ) { it["year"].primitive()?.intOrNull?.takeIf { y -> y > 0 }?.toString() }
            SECTION_ARTISTS -> list(
                KodiMethod.AUDIO_GET_ARTISTS, "artists", "artistid", start, end, emptyList(),
                sortByLabel = true, browsable = true,
            )
            SECTION_ALBUMS -> albums(start, end, artistId = null)
            SECTION_TV -> list(
                KodiMethod.PVR_GET_CHANNEL_GROUPS, "channelgroups", "channelgroupid", start, end, null,
                browsable = true, extra = { put("channeltype", "tv") },
            )
            SECTION_FAVOURITES -> favourites().drop(start).take(end - start)
            SECTION_ADDONS -> addons(start, end)
            else -> browseEntry(KodiEntryId.parse(parentId), start, end)
        }
    }

    private suspend fun browseEntry(id: KodiEntryId, start: Int, end: Int): List<MediaEntry> = when (id.type) {
        "tvshowid" -> {
            val show = id.intValue()
            val o = client.call(
                KodiMethod.VIDEO_GET_SEASONS,
                buildJsonObject {
                    put("tvshowid", show)
                    putJsonArray("properties") { add(JsonPrimitive("season")); add(JsonPrimitive("showtitle")) }
                    limits(start, end)
                },
            ).obj(KodiMethod.VIDEO_GET_SEASONS)
            objects(o["seasons"]).mapNotNull {
                val season = it["season"].primitive()?.intOrNull ?: return@mapNotNull null
                MediaEntry(
                    id = KodiEntryId("season", "$show:$season").toString(),
                    title = label(it) ?: "Saison $season",
                    subtitle = it["showtitle"].primitive()?.contentOrNull?.takeIf { s -> s.isNotBlank() },
                    browsable = true,
                )
            }
        }
        "season" -> {
            val (show, season) = seasonOf(id)
            val o = client.call(
                KodiMethod.VIDEO_GET_EPISODES,
                buildJsonObject {
                    put("tvshowid", show)
                    put("season", season)
                    putJsonArray("properties") { add(JsonPrimitive("season")); add(JsonPrimitive("episode")) }
                    limits(start, end)
                },
            ).obj(KodiMethod.VIDEO_GET_EPISODES)
            objects(o["episodes"]).mapNotNull { e ->
                val episodeId = e["episodeid"].primitive()?.intOrNull ?: return@mapNotNull null
                val s = e["season"].primitive()?.intOrNull
                val n = e["episode"].primitive()?.intOrNull
                MediaEntry(
                    id = KodiEntryId("episodeid", episodeId.toString()).toString(),
                    title = label(e).orEmpty(),
                    subtitle = if (s != null && n != null && n > 0) "S%02dE%02d".format(s, n) else null,
                )
            }
        }
        "artistid" -> albums(start, end, artistId = id.intValue())
        "albumid" -> list(
            KodiMethod.AUDIO_GET_SONGS, "songs", "songid", start, end, listOf("track"),
            extra = {
                putJsonObject("filter") { put("albumid", id.intValue()) }
                putJsonObject("sort") { put("method", "track") }
            },
        ) { it["track"].primitive()?.intOrNull?.takeIf { t -> t > 0 }?.let { t -> "Piste $t" } }
        "channelgroupid" -> list(
            KodiMethod.PVR_GET_CHANNELS, "channels", "channelid", start, end, null,
            extra = { put("channelgroupid", id.intValue()) },
        )
        else -> throw UnsupportedActionException("Cet élément Kodi ne se parcourt pas : $id")
    }

    private suspend fun albums(start: Int, end: Int, artistId: Int?): List<MediaEntry> = list(
        KodiMethod.AUDIO_GET_ALBUMS, "albums", "albumid", start, end, listOf("artist", "year"),
        sortByLabel = artistId == null, browsable = true,
        extra = { if (artistId != null) putJsonObject("filter") { put("artistid", artistId) } },
    ) { o ->
        (o["artist"] as? JsonArray)?.mapNotNull { it.primitive()?.contentOrNull }?.joinToString(", ")?.takeIf { it.isNotBlank() }
    }

    /** Favoris lisibles (média) ou ouvrant une fenêtre ; les scripts et applications sont ignorés. */
    private suspend fun favourites(): List<MediaEntry> {
        val o = client.call(
            KodiMethod.FAVOURITES_GET,
            buildJsonObject {
                putJsonArray("properties") { listOf("window", "windowparameter", "path").forEach { add(JsonPrimitive(it)) } }
            },
        ).obj(KodiMethod.FAVOURITES_GET)
        return objects(o["favourites"]).mapNotNull { f ->
            val title = f["title"].primitive()?.contentOrNull ?: return@mapNotNull null
            val id = when (f["type"].primitive()?.contentOrNull) {
                "media" -> f["path"].primitive()?.contentOrNull?.takeIf { it.isNotBlank() }?.let { KodiEntryId("file", it) }
                "window" -> f["window"].primitive()?.contentOrNull?.takeIf { it.isNotBlank() }?.let { w ->
                    val param = f["windowparameter"].primitive()?.contentOrNull.orEmpty()
                    KodiEntryId("window", if (param.isEmpty()) w else "$w|$param")
                }
                else -> null
            } ?: return@mapNotNull null
            MediaEntry(id.toString(), title)
        }
    }

    private suspend fun addons(start: Int, end: Int): List<MediaEntry> {
        val o = client.call(
            KodiMethod.ADDONS_GET_ADDONS,
            buildJsonObject {
                put("type", "xbmc.python.pluginsource")
                put("content", "video")
                put("enabled", true)
                putJsonArray("properties") { add(JsonPrimitive("name")) }
                limits(start, end)
            },
        ).obj(KodiMethod.ADDONS_GET_ADDONS)
        return objects(o["addons"]).mapNotNull { a ->
            val addonId = a["addonid"].primitive()?.contentOrNull ?: return@mapNotNull null
            MediaEntry(KodiEntryId("addonid", addonId).toString(), a["name"].primitive()?.contentOrNull ?: addonId)
        }
    }

    // --- Lecture et file d'attente -------------------------------------------

    suspend fun play(entryId: String) {
        val id = KodiEntryId.parse(entryId)
        when (id.type) {
            in PLAYABLE_KEYS, "channelid" -> client.open(buildJsonObject { put(id.type, id.intValue()) })
            "file" -> client.open(buildJsonObject { put("file", id.value) })
            "addonid" -> client.call(KodiMethod.ADDONS_EXECUTE, buildJsonObject { put("addonid", id.value) })
            "window" -> {
                val window = id.value.substringBefore('|')
                val param = id.value.substringAfter('|', "")
                if (param.isEmpty()) client.activateWindow(window) else client.activateWindow(window, listOf(param))
            }
            "tvshowid", "season" -> {
                // Pas d'élément « saison » dans Player.Open : on remplit la playlist vidéo.
                val episodes = episodesOf(id) // avant de vider : rien n'est perdu si la saison est vide
                client.playlistClear(VIDEO_PLAYLIST)
                client.playlistAdd(VIDEO_PLAYLIST, episodes)
                client.open(buildJsonObject { put("playlistid", VIDEO_PLAYLIST); put("position", 0) })
            }
            else -> throw UnsupportedActionException("Cet élément Kodi ne se lit pas : $id")
        }
    }

    /** Élément(s) `Playlist.Item` et playlist cible (0 audio, 1 vidéo) ; `null` pour la playlist active. */
    suspend fun playlistItem(entryId: String): Pair<JsonElement, Int?> {
        val id = KodiEntryId.parse(entryId)
        return when (id.type) {
            "artistid", "albumid", "songid" -> buildJsonObject { put(id.type, id.intValue()) } to AUDIO_PLAYLIST
            "movieid", "episodeid", "musicvideoid" -> buildJsonObject { put(id.type, id.intValue()) } to VIDEO_PLAYLIST
            "tvshowid", "season" -> episodesOf(id) to VIDEO_PLAYLIST
            "file" -> buildJsonObject { put("file", id.value) } to null
            else -> throw UnsupportedActionException("Impossible d'ajouter cet élément Kodi à la file d'attente : $id")
        }
    }

    /** Épisodes d'une série ou d'une saison, sous forme de tableau de `Playlist.Item`. */
    private suspend fun episodesOf(id: KodiEntryId): JsonArray {
        val o = client.call(
            KodiMethod.VIDEO_GET_EPISODES,
            buildJsonObject {
                if (id.type == "season") {
                    val (show, season) = seasonOf(id)
                    put("tvshowid", show)
                    put("season", season)
                } else {
                    put("tvshowid", id.intValue())
                }
            },
        ).obj(KodiMethod.VIDEO_GET_EPISODES)
        val ids = objects(o["episodes"]).mapNotNull { it["episodeid"].primitive()?.intOrNull }
        if (ids.isEmpty()) throw RemoteException("Aucun épisode à lire")
        return buildJsonArray { ids.forEach { add(buildJsonObject { put("episodeid", it) }) } }
    }

    private fun seasonOf(id: KodiEntryId): Pair<Int, Int> {
        val show = id.value.substringBefore(':').toIntOrNull()
        val season = id.value.substringAfter(':', "").toIntOrNull()
        if (show == null || season == null) throw RemoteException("Identifiant Kodi invalide : $id")
        return show to season
    }

    // --- Outils -------------------------------------------------------------

    /**
     * Liste paginée générique. [properties] `null` : aucun champ `properties` envoyé
     * (certaines méthodes PVR n'en ont pas besoin).
     */
    private suspend fun list(
        method: String,
        key: String,
        idKey: String,
        start: Int,
        end: Int,
        properties: List<String>?,
        sortByLabel: Boolean = false,
        browsable: Boolean = false,
        extra: JsonObjectBuilder.() -> Unit = {},
        subtitle: (JsonObject) -> String? = { null },
    ): List<MediaEntry> {
        val o = client.call(
            method,
            buildJsonObject {
                extra()
                if (properties != null) putJsonArray("properties") { properties.forEach { add(JsonPrimitive(it)) } }
                limits(start, end)
                if (sortByLabel) putJsonObject("sort") { put("method", "label"); put("ignorearticle", true) }
            },
        ).obj(method)
        return objects(o[key]).mapNotNull {
            val itemId = it[idKey].primitive()?.intOrNull ?: return@mapNotNull null
            MediaEntry(KodiEntryId(idKey, itemId.toString()).toString(), label(it).orEmpty(), subtitle(it), browsable)
        }
    }

    companion object {
        const val SECTION_MOVIES = "movies"
        const val SECTION_TV_SHOWS = "tvshows"
        const val SECTION_ARTISTS = "artists"
        const val SECTION_ALBUMS = "albums"
        const val SECTION_TV = "tv"
        const val SECTION_FAVOURITES = "favourites"
        const val SECTION_ADDONS = "addons"

        const val AUDIO_PLAYLIST = 0
        const val VIDEO_PLAYLIST = 1

        /** Types acceptés directement par `Player.Open {item:{type:id}}`. */
        private val PLAYABLE_KEYS = setOf("movieid", "episodeid", "musicvideoid", "artistid", "albumid", "songid")

        /** Clé d'identifiant pour un `type` d'élément de playlist. */
        val ITEM_TYPE_KEYS = mapOf(
            "movie" to "movieid",
            "episode" to "episodeid",
            "musicvideo" to "musicvideoid",
            "song" to "songid",
            "channel" to "channelid",
        )

        fun JsonObjectBuilder.limits(start: Int, end: Int) {
            putJsonObject("limits") { put("start", start); put("end", end) }
        }

        fun objects(element: JsonElement?): List<JsonObject> =
            (element as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()

        /** Titre, sinon libellé ; `null` si les deux sont absents ou vides. */
        fun label(o: JsonObject): String? =
            listOf("title", "label").firstNotNullOfOrNull { key -> o[key].primitive()?.contentOrNull?.takeIf { it.isNotBlank() } }
    }
}
