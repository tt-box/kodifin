package io.github.jmorille.zapette.kodi

import io.github.jmorille.zapette.model.MediaEntry
import io.github.jmorille.zapette.model.MediaKind
import io.github.jmorille.zapette.model.MediaSection
import io.github.jmorille.zapette.model.RemoteException
import io.github.jmorille.zapette.model.UnsupportedActionException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
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

/**
 * Parcours de la bibliothèque Kodi (vidéo, musique, PVR, favoris, add-ons), recherche et lancement.
 * Les [MediaEntry] sont enrichies : nature, année, image (`image://…`, voir [imageKey]) et « vu ».
 */
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
    suspend fun hasPvr(): Boolean = try {
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
            SECTION_MOVIES -> list(
                KodiMethod.VIDEO_GET_MOVIES, "movies", "movieid", MediaKind.MOVIE, start, end, MOVIE_PROPERTIES,
                sortByLabel = true, subtitle = ::yearLabel,
            )
            SECTION_TV_SHOWS -> list(
                KodiMethod.VIDEO_GET_TV_SHOWS, "tvshows", "tvshowid", MediaKind.SERIES, start, end, TV_SHOW_PROPERTIES,
                sortByLabel = true, browsable = true, subtitle = ::yearLabel,
            )
            SECTION_ARTISTS -> list(
                KodiMethod.AUDIO_GET_ARTISTS, "artists", "artistid", MediaKind.ARTIST, start, end, ARTIST_PROPERTIES,
                sortByLabel = true, browsable = true,
            )
            SECTION_ALBUMS -> albums(start, end, artistId = null)
            SECTION_TV -> list(
                KodiMethod.PVR_GET_CHANNEL_GROUPS, "channelgroups", "channelgroupid", MediaKind.FOLDER, start, end, null,
                browsable = true, extra = { put("channeltype", "tv") },
            )
            SECTION_FAVOURITES -> favourites().drop(start).take(end - start)
            SECTION_ADDONS -> addons(start, end)
            else -> browseEntry(KodiEntryId.parse(parentId), start, end)
        }
    }

    private suspend fun browseEntry(id: KodiEntryId, start: Int, end: Int): List<MediaEntry> = when (id.type) {
        "tvshowid" -> seasons(id.intValue(), start, end)
        "season" -> {
            val (show, season) = seasonOf(id)
            val o = client.call(
                KodiMethod.VIDEO_GET_EPISODES,
                buildJsonObject {
                    put("tvshowid", show)
                    put("season", season)
                    properties(EPISODE_PROPERTIES)
                    limits(start, end)
                },
            ).obj(KodiMethod.VIDEO_GET_EPISODES)
            objects(o["episodes"]).mapNotNull { episode(it, withShow = false) }
        }
        "artistid" -> albums(start, end, artistId = id.intValue())
        "albumid" -> list(
            KodiMethod.AUDIO_GET_SONGS, "songs", "songid", MediaKind.SONG, start, end, SONG_PROPERTIES,
            extra = {
                putJsonObject("filter") { put("albumid", id.intValue()) }
                putJsonObject("sort") { put("method", "track") }
            },
        ) { it["track"].primitive()?.intOrNull?.takeIf { t -> t > 0 }?.let { t -> "Piste $t" } }
        "channelgroupid" -> list(
            KodiMethod.PVR_GET_CHANNELS, "channels", "channelid", MediaKind.CHANNEL, start, end, CHANNEL_PROPERTIES,
            extra = { put("channelgroupid", id.intValue()) },
        )
        else -> throw UnsupportedActionException("Cet élément Kodi ne se parcourt pas : $id")
    }

    /** Saisons d'une série (`VideoLibrary.GetSeasons`), identifiants `season:<tvshowid>:<n>`. */
    suspend fun seasons(show: Int, start: Int, end: Int): List<MediaEntry> {
        val o = client.call(
            KodiMethod.VIDEO_GET_SEASONS,
            buildJsonObject {
                put("tvshowid", show)
                properties(SEASON_PROPERTIES)
                limits(start, end)
            },
        ).obj(KodiMethod.VIDEO_GET_SEASONS)
        return objects(o["seasons"]).mapNotNull {
            val season = it["season"].primitive()?.intOrNull ?: return@mapNotNull null
            MediaEntry(
                id = KodiEntryId("season", "$show:$season").toString(),
                title = label(it) ?: "Saison $season",
                subtitle = text(it["showtitle"]),
                browsable = true,
                kind = MediaKind.SEASON,
                imageKey = imageKey(it),
                season = season,
                watched = watched(it),
            )
        }
    }

    private suspend fun albums(start: Int, end: Int, artistId: Int?): List<MediaEntry> = list(
        KodiMethod.AUDIO_GET_ALBUMS, "albums", "albumid", MediaKind.ALBUM, start, end, ALBUM_PROPERTIES,
        sortByLabel = artistId == null, browsable = true,
        extra = { if (artistId != null) putJsonObject("filter") { put("artistid", artistId) } },
        subtitle = ::artists,
    )

    /** Épisode (`VideoLibrary.GetEpisodes`) : « S01E03 · 42 min », précédé de la série si [withShow]. */
    private fun episode(e: JsonObject, withShow: Boolean): MediaEntry? {
        val episodeId = e["episodeid"].primitive()?.intOrNull ?: return null
        val s = e["season"].primitive()?.intOrNull?.takeIf { it >= 0 }
        val n = e["episode"].primitive()?.intOrNull?.takeIf { it >= 0 }
        val code = if (s != null && n != null && n > 0) "S%02dE%02d".format(s, n) else null
        val duration = e["runtime"].primitive()?.intOrNull?.takeIf { it > 0 }?.let(::minutesLabel)
        val show = if (withShow) text(e["showtitle"]) else null
        return MediaEntry(
            id = KodiEntryId("episodeid", episodeId.toString()).toString(),
            title = label(e).orEmpty(),
            subtitle = listOfNotNull(show, code, duration).joinToString(" · ").ifEmpty { null },
            kind = MediaKind.EPISODE,
            imageKey = imageKey(e, preferThumb = true),
            season = s,
            episode = n,
            watched = watched(e),
        )
    }

    /** Favoris lisibles (média) ou ouvrant une fenêtre ; les scripts et applications sont ignorés. */
    private suspend fun favourites(): List<MediaEntry> {
        val o = client.call(
            KodiMethod.FAVOURITES_GET,
            buildJsonObject { properties(FAVOURITE_PROPERTIES) },
        ).obj(KodiMethod.FAVOURITES_GET)
        return objects(o["favourites"]).mapNotNull { f ->
            val title = f["title"].primitive()?.contentOrNull ?: return@mapNotNull null
            val (id, kind) = when (f["type"].primitive()?.contentOrNull) {
                "media" -> f["path"].primitive()?.contentOrNull?.takeIf { it.isNotBlank() }
                    ?.let { KodiEntryId("file", it) to MediaKind.OTHER }
                "window" -> f["window"].primitive()?.contentOrNull?.takeIf { it.isNotBlank() }?.let { w ->
                    val param = f["windowparameter"].primitive()?.contentOrNull.orEmpty()
                    KodiEntryId("window", if (param.isEmpty()) w else "$w|$param") to MediaKind.FOLDER
                }
                else -> null
            } ?: return@mapNotNull null
            MediaEntry(id.toString(), title, kind = kind, imageKey = imageKey(f))
        }
    }

    private suspend fun addons(start: Int, end: Int): List<MediaEntry> {
        val o = client.call(
            KodiMethod.ADDONS_GET_ADDONS,
            buildJsonObject {
                put("type", "xbmc.python.pluginsource")
                put("content", "video")
                put("enabled", true)
                properties(ADDON_PROPERTIES)
                limits(start, end)
            },
        ).obj(KodiMethod.ADDONS_GET_ADDONS)
        return objects(o["addons"]).mapNotNull { a ->
            val addonId = a["addonid"].primitive()?.contentOrNull ?: return@mapNotNull null
            MediaEntry(
                KodiEntryId("addonid", addonId).toString(),
                a["name"].primitive()?.contentOrNull ?: addonId,
                kind = MediaKind.OTHER,
                imageKey = imageKey(a),
            )
        }
    }

    // --- Recherche ----------------------------------------------------------

    /**
     * Recherche par les filtres natifs (`filter:{field,operator:"contains",value}`), une requête
     * par nature limitée à [limit] éléments, en parallèle ; résultats regroupés dans l'ordre de
     * [SEARCH_ORDER]. Les chaînes PVR, sans filtre JSON-RPC, sont filtrées localement par nom.
     * Une nature refusée par le serveur (erreur JSON-RPC, méthode absente) ne renvoie rien sans
     * faire échouer les autres ; une erreur réseau fait échouer la recherche.
     */
    suspend fun search(query: String, kinds: Set<MediaKind>, limit: Int): List<MediaEntry> {
        val q = query.trim()
        if (q.isEmpty() || limit <= 0 || kinds.isEmpty()) return emptyList()
        return coroutineScope {
            SEARCH_ORDER.filter { it in kinds }
                .map { kind -> async { searchKind(kind, q, limit) } }
                .awaitAll()
                .flatten()
        }
    }

    private suspend fun searchKind(kind: MediaKind, q: String, limit: Int): List<MediaEntry> = try {
        when (kind) {
            MediaKind.MOVIE -> list(
                KodiMethod.VIDEO_GET_MOVIES, "movies", "movieid", kind, 0, limit, MOVIE_PROPERTIES,
                sortByLabel = true, extra = { containsFilter("title", q) }, subtitle = ::yearLabel,
            )
            MediaKind.SERIES -> list(
                KodiMethod.VIDEO_GET_TV_SHOWS, "tvshows", "tvshowid", kind, 0, limit, TV_SHOW_PROPERTIES,
                sortByLabel = true, browsable = true, extra = { containsFilter("title", q) }, subtitle = ::yearLabel,
            )
            MediaKind.EPISODE -> {
                val o = client.call(
                    KodiMethod.VIDEO_GET_EPISODES,
                    buildJsonObject {
                        containsFilter("title", q)
                        properties(EPISODE_SEARCH_PROPERTIES)
                        limits(0, limit)
                        sortByLabel()
                    },
                ).obj(KodiMethod.VIDEO_GET_EPISODES)
                objects(o["episodes"]).mapNotNull { episode(it, withShow = true) }
            }
            MediaKind.ARTIST -> list(
                KodiMethod.AUDIO_GET_ARTISTS, "artists", "artistid", kind, 0, limit, ARTIST_PROPERTIES,
                sortByLabel = true, browsable = true, extra = { containsFilter("artist", q) },
            )
            MediaKind.ALBUM -> list(
                KodiMethod.AUDIO_GET_ALBUMS, "albums", "albumid", kind, 0, limit, ALBUM_PROPERTIES,
                sortByLabel = true, browsable = true, extra = { containsFilter("album", q) }, subtitle = ::artists,
            )
            MediaKind.SONG -> list(
                KodiMethod.AUDIO_GET_SONGS, "songs", "songid", kind, 0, limit, SONG_SEARCH_PROPERTIES,
                sortByLabel = true, extra = { containsFilter("title", q) },
            ) { o -> listOfNotNull(artists(o), text(o["album"])).joinToString(" — ").ifEmpty { null } }
            MediaKind.CHANNEL -> channels(q, limit)
            else -> emptyList()
        }
    } catch (_: KodiRpcException) {
        emptyList()
    } catch (_: UnsupportedActionException) {
        emptyList()
    }

    /** Chaînes TV (groupe « alltv ») dont le nom contient [q], sans tenir compte de la casse. */
    private suspend fun channels(q: String, limit: Int): List<MediaEntry> {
        val o = client.call(
            KodiMethod.PVR_GET_CHANNELS,
            buildJsonObject {
                put("channelgroupid", "alltv")
                properties(CHANNEL_PROPERTIES)
            },
        ).obj(KodiMethod.PVR_GET_CHANNELS)
        return objects(o["channels"]).asSequence()
            .filter { label(it)?.contains(q, ignoreCase = true) == true }
            .mapNotNull { entry(it, "channelid", MediaKind.CHANNEL, browsable = false, subtitle = null) }
            .take(limit)
            .toList()
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
        kind: MediaKind,
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
                if (properties != null) properties(properties)
                limits(start, end)
                if (sortByLabel) sortByLabel()
            },
        ).obj(method)
        return objects(o[key]).mapNotNull { entry(it, idKey, kind, browsable, subtitle(it)) }
    }

    /** Élément de bibliothèque enrichi (nature, image, année, vu) ; `null` sans identifiant. */
    private fun entry(o: JsonObject, idKey: String, kind: MediaKind, browsable: Boolean, subtitle: String?): MediaEntry? {
        val itemId = o[idKey].primitive()?.intOrNull ?: return null
        return MediaEntry(
            id = KodiEntryId(idKey, itemId.toString()).toString(),
            title = label(o).orEmpty(),
            subtitle = subtitle,
            browsable = browsable,
            kind = kind,
            imageKey = imageKey(o),
            year = year(o),
            watched = watched(o),
        )
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

        /** Nature d'un élément de playlist selon son `type` (autres : [MediaKind.OTHER]). */
        private val KIND_BY_ITEM_TYPE = mapOf(
            "movie" to MediaKind.MOVIE,
            "episode" to MediaKind.EPISODE,
            "song" to MediaKind.SONG,
            "channel" to MediaKind.CHANNEL,
        )

        /** Ordre des natures dans les résultats de recherche. */
        val SEARCH_ORDER = listOf(
            MediaKind.MOVIE, MediaKind.SERIES, MediaKind.EPISODE,
            MediaKind.ARTIST, MediaKind.ALBUM, MediaKind.SONG, MediaKind.CHANNEL,
        )

        // Propriétés demandées par nature (schéma JSON-RPC : Video.Fields.*, Audio.Fields.*,
        // PVR.Fields.Channel, Favourite.Fields.Favourite, Addon.Fields, List.Fields.All).
        val MOVIE_PROPERTIES = listOf("year", "playcount", "art", "thumbnail")
        val TV_SHOW_PROPERTIES = listOf("year", "playcount", "art", "thumbnail")
        val SEASON_PROPERTIES = listOf("season", "showtitle", "playcount", "art", "thumbnail")
        val EPISODE_PROPERTIES = listOf("season", "episode", "playcount", "runtime", "art", "thumbnail")
        val EPISODE_SEARCH_PROPERTIES = EPISODE_PROPERTIES + "showtitle"
        val ARTIST_PROPERTIES = listOf("thumbnail", "fanart")
        val ALBUM_PROPERTIES = listOf("artist", "year", "playcount", "art", "thumbnail")
        val SONG_PROPERTIES = listOf("track", "playcount", "thumbnail")
        val SONG_SEARCH_PROPERTIES = listOf("artist", "album", "playcount", "thumbnail")
        val CHANNEL_PROPERTIES = listOf("thumbnail")
        val FAVOURITE_PROPERTIES = listOf("window", "windowparameter", "path", "thumbnail")
        val ADDON_PROPERTIES = listOf("name", "thumbnail")
        val QUEUE_PROPERTIES = listOf(
            "title", "file", "showtitle", "artist", "season", "episode", "year", "playcount", "art", "thumbnail",
        )

        fun JsonObjectBuilder.limits(start: Int, end: Int) {
            putJsonObject("limits") { put("start", start); put("end", end) }
        }

        private fun JsonObjectBuilder.properties(names: List<String>) {
            putJsonArray("properties") { names.forEach { add(JsonPrimitive(it)) } }
        }

        private fun JsonObjectBuilder.sortByLabel() {
            putJsonObject("sort") { put("method", "label"); put("ignorearticle", true) }
        }

        /** Filtre natif `{field, operator:"contains", value}` ; la valeur est échappée par le sérialiseur JSON. */
        private fun JsonObjectBuilder.containsFilter(field: String, value: String) {
            putJsonObject("filter") {
                put("field", field)
                put("operator", "contains")
                put("value", value)
            }
        }

        fun objects(element: JsonElement?): List<JsonObject> =
            (element as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()

        /** Titre, sinon libellé ; `null` si les deux sont absents ou vides. */
        fun label(o: JsonObject): String? =
            listOf("title", "label").firstNotNullOfOrNull { key -> o[key].primitive()?.contentOrNull?.takeIf { it.isNotBlank() } }

        /** Texte non vide, sinon `null`. */
        fun text(element: JsonElement?): String? = element.primitive()?.contentOrNull?.takeIf { it.isNotBlank() }

        /** Année > 0, sinon `null` (Kodi renvoie 0 quand elle est inconnue). */
        fun year(o: JsonObject): Int? = o["year"].primitive()?.intOrNull?.takeIf { it > 0 }

        private fun yearLabel(o: JsonObject): String? = year(o)?.toString()

        /** `playcount` > 0 ; `null` si Kodi ne le publie pas pour cet élément. */
        fun watched(o: JsonObject): Boolean? = o["playcount"].primitive()?.intOrNull?.let { it > 0 }

        /** Artistes joints par « , », `null` si absents. */
        fun artists(o: JsonObject): String? =
            (o["artist"] as? JsonArray)?.mapNotNull { it.primitive()?.contentOrNull }?.joinToString(", ")?.takeIf { it.isNotBlank() }

        /** « 42 min », « 1 h 05 » (durée en secondes, arrondie à la minute, au moins 1 min). */
        fun minutesLabel(seconds: Int): String {
            val minutes = ((seconds + 30) / 60).coerceAtLeast(1)
            return if (minutes < 60) "$minutes min" else "%d h %02d".format(minutes / 60, minutes % 60)
        }

        /**
         * Chemin d'image Kodi (`image://…`) à passer à `SupportsImages.image` : affiche
         * (`art.poster`), sinon vignette (`thumbnail`, `art.thumb`), sinon fond (`fanart`) ;
         * [preferThumb] (épisodes : capture de l'épisode) place la vignette en tête.
         */
        fun imageKey(o: JsonObject, preferThumb: Boolean = false): String? {
            val art = o["art"] as? JsonObject
            val poster = text(art?.get("poster"))
            val thumb = text(o["thumbnail"]) ?: text(art?.get("thumb"))
            val fanart = text(art?.get("fanart")) ?: text(o["fanart"])
            return if (preferThumb) thumb ?: poster ?: fanart else poster ?: thumb ?: fanart
        }

        /** Élément de playlist (`Playlist.GetItems`, propriétés [QUEUE_PROPERTIES]) ; [index] : position. */
        fun queueEntry(o: JsonObject, index: Int): MediaEntry {
            val type = o["type"].primitive()?.contentOrNull
            val idKey = ITEM_TYPE_KEYS[type]
            val itemId = o["id"].primitive()?.intOrNull
            val file = o["file"].primitive()?.contentOrNull?.takeIf { it.isNotBlank() }
            val id = when {
                idKey != null && itemId != null -> KodiEntryId(idKey, itemId.toString())
                file != null -> KodiEntryId("file", file)
                else -> KodiEntryId("position", index.toString())
            }
            val isEpisode = type == "episode"
            return MediaEntry(
                id = id.toString(),
                title = label(o).orEmpty(),
                subtitle = text(o["showtitle"]) ?: artists(o),
                kind = KIND_BY_ITEM_TYPE[type] ?: MediaKind.OTHER,
                imageKey = imageKey(o, preferThumb = isEpisode),
                year = year(o),
                season = if (isEpisode) o["season"].primitive()?.intOrNull?.takeIf { it >= 0 } else null,
                episode = if (isEpisode) o["episode"].primitive()?.intOrNull?.takeIf { it >= 0 } else null,
                watched = watched(o),
            )
        }
    }
}
