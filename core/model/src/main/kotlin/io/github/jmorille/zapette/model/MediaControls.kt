package io.github.jmorille.zapette.model

/*
 * Contrôles avancés, facultatifs : l'interface affiche un contrôle seulement si le contrôleur
 * implémente l'interface correspondante (`controller as? SupportsTracks`), sur le même modèle
 * que [PersistsCredentials] et [DescribesServer]. Aucun envoi ailleurs qu'à l'appareil piloté.
 */

/** Chapitre du média en cours : début (ms depuis le début du média) et nom éventuel. */
data class Chapter(val startMs: Long, val name: String? = null)

/** Piste audio ou de sous-titres du média en cours. [id] : identifiant propre au protocole. */
data class MediaTrack(val id: Int, val label: String, val language: String? = null)

enum class RepeatMode { OFF, ONE, ALL }

/** État étendu du média en cours (pistes, modes de lecture, vitesse). */
data class MediaDetails(
    val audioTracks: List<MediaTrack> = emptyList(),
    val currentAudio: Int? = null,
    val subtitles: List<MediaTrack> = emptyList(),
    /** `null` : sous-titres désactivés. */
    val currentSubtitle: Int? = null,
    val repeat: RepeatMode? = null,
    val shuffle: Boolean? = null,
    /** Vitesse de lecture (1 = normale, 0 = pause, négative = retour rapide). */
    val speed: Int = 1,
    val chapterCount: Int = 0,
    /** Chapitres positionnés (triés), si le protocole les publie ; sinon seul [chapterCount] est connu. */
    val chapters: List<Chapter> = emptyList(),
    /** Vitesse fractionnaire (Chromecast : 0,5 à 2) ; prioritaire sur [speed] pour l'affichage. */
    val playbackRate: Double? = null,
)

/** État étendu du média en cours (modes, vitesse), sans forcément de choix de pistes. */
interface SupportsMediaDetails {
    suspend fun mediaDetails(): MediaDetails
}

/** Pistes audio / sous-titres. */
interface SupportsTracks : SupportsMediaDetails {
    suspend fun setAudioTrack(id: Int)

    /** `null` désactive les sous-titres. */
    suspend fun setSubtitle(id: Int?)
    suspend fun nextSubtitle()
    suspend fun nextAudioTrack()
}

/** Saut relatif dans le média (distinct de l'avance rapide, qui change la vitesse). */
interface SupportsSeekStep {
    suspend fun seekBy(deltaMs: Long)
}

/** Vitesse réelle (avance / retour rapides progressifs). */
interface SupportsSpeed {
    suspend fun faster()
    suspend fun slower()
}

/** Décalage de synchronisation des sous-titres et de l'audio. */
interface SupportsSyncOffset {
    suspend fun subtitleDelay(later: Boolean)
    suspend fun audioDelay(later: Boolean)
}

/** Répétition et lecture aléatoire. */
interface SupportsPlayMode {
    suspend fun setRepeat(mode: RepeatMode)
    suspend fun setShuffle(enabled: Boolean)
}

/** Chapitres. */
interface SupportsChapters {
    suspend fun nextChapter()
    suspend fun previousChapter()
}

/** Commandes d'interface de l'appareil (affichage à l'écran). */
enum class UiCommand(val label: String) {
    OSD("Menu de lecture (OSD)"),
    CONTEXT_MENU("Menu contextuel"),
    CODEC_INFO("Informations codec"),
    FULLSCREEN("Plein écran"),
    HOME_SCREEN("Accueil"),
    SETTINGS("Paramètres"),
    SEARCH("Recherche"),
    PAGE_UP("Page précédente"),
    PAGE_DOWN("Page suivante"),
    CHANNEL_UP("Chaîne suivante"),
    CHANNEL_DOWN("Chaîne précédente"),
    GUIDE("Guide TV"),
}

interface SupportsUiCommands {
    val uiCommands: Set<UiCommand>
    suspend fun uiCommand(command: UiCommand)
}

enum class PowerAction(val label: String) {
    SUSPEND("Mettre en veille"),
    HIBERNATE("Mettre en veille prolongée"),
    SHUTDOWN("Éteindre"),
    REBOOT("Redémarrer"),
    QUIT_APP("Quitter l'application"),
}

/** Alimentation de la machine pilotée (à confirmer par l'utilisateur avant l'appel). */
interface SupportsPower {
    suspend fun availablePowerActions(): Set<PowerAction>
    suspend fun power(action: PowerAction)
}

/** Message affiché sur l'écran piloté. */
interface SupportsNotify {
    suspend fun showMessage(title: String, text: String)
}

/** Maintenance de la bibliothèque. */
interface SupportsLibraryMaintenance {
    suspend fun scanLibrary()

    /** Peut lever [UnsupportedActionException]. */
    suspend fun cleanLibrary()
}

/** Nature d'un élément de bibliothèque (filtres de recherche, icône, action par défaut). */
enum class MediaKind(val label: String) {
    MOVIE("Films"),
    SERIES("Séries"),
    SEASON("Saisons"),
    EPISODE("Épisodes"),
    ARTIST("Artistes"),
    ALBUM("Albums"),
    SONG("Titres"),
    CHANNEL("Chaînes TV"),
    FOLDER("Dossiers"),
    OTHER("Autres"),
}

/** Élément de bibliothèque, de file d'attente, de chaîne TV ou de favori. */
data class MediaEntry(
    /** Identifiant propre au protocole (ex. `movieid:12`, `channelid:3`, id d'élément Jellyfin). */
    val id: String,
    val title: String,
    val subtitle: String? = null,
    /** Dossier navigable (série, saison, album, catégorie) plutôt qu'élément jouable. */
    val browsable: Boolean = false,
    val kind: MediaKind? = null,
    /** Clé opaque à passer à [SupportsImages.image] (affiche, vignette), ou `null` sans image. */
    val imageKey: String? = null,
    val year: Int? = null,
    /** Épisode : saison et numéro ; `null` sinon. */
    val season: Int? = null,
    val episode: Int? = null,
    /** Déjà vu (si le serveur le publie). */
    val watched: Boolean? = null,
    /** Résumé (épisode, film), si le serveur le fournit dans la liste. */
    val overview: String? = null,
)

/** Série du média en cours : ses saisons (navigables via [SupportsLibrary.browse]) et la position courante. */
data class SeriesContext(
    val seriesId: String,
    val title: String,
    val seasons: List<MediaEntry>,
    val currentSeasonId: String? = null,
    val currentEpisodeId: String? = null,
)

/** Navigation dans la série en cours de lecture (saisons puis épisodes). */
interface SupportsSeries {
    /** `null` si le média en cours n'est pas un épisode de série. */
    suspend fun currentSeries(): SeriesContext?
}

/** Recherche dans la bibliothèque du serveur (fonctions de recherche natives du protocole). */
interface SupportsSearch {
    /** Natures interrogeables (filtres proposés à l'utilisateur). */
    val searchKinds: Set<MediaKind>

    /** [kinds] vide : toutes les natures de [searchKinds]. */
    suspend fun search(query: String, kinds: Set<MediaKind> = emptySet(), limit: Int = 60): List<MediaEntry>
}

/**
 * Images (affiches, vignettes) récupérées par le contrôleur avec son authentification : aucun
 * jeton n'apparaît dans une URL ni ne sort vers un autre hôte. Taille bornée par l'implémentation.
 */
interface SupportsImages {
    /** Octets de l'image (JPEG/PNG/WebP) redimensionnée côté serveur si possible, ou `null`. */
    suspend fun image(imageKey: String, maxWidth: Int): ByteArray?
}

/** Fiche détaillée d'un élément : résumé, diffusion, note, genres, équipe, flux. */
data class MediaItemInfo(
    val id: String,
    val title: String,
    val overview: String? = null,
    val seriesTitle: String? = null,
    val season: Int? = null,
    val episode: Int? = null,
    /** Date de première diffusion ou de sortie, `AAAA-MM-JJ`. */
    val airDate: String? = null,
    val year: Int? = null,
    val runtimeMs: Long? = null,
    /** Note du public sur 10. */
    val rating: Double? = null,
    /** Classification (« TV-14 », « 12 »…). */
    val officialRating: String? = null,
    val genres: List<String> = emptyList(),
    val studios: List<String> = emptyList(),
    val directors: List<String> = emptyList(),
    val writers: List<String> = emptyList(),
    /** Distribution : « Acteur (Rôle) ». */
    val cast: List<String> = emptyList(),
    /** Résumés techniques : « 1080p · HEVC », « Français · AAC 5.1 ». */
    val video: String? = null,
    val audio: String? = null,
    val imageKey: String? = null,
)

/** Fiches détaillées (résumé d'épisode, distribution…). */
interface SupportsItemInfo {
    /** Fiche de l'élément [entryId] (identifiant de [MediaEntry]) ; `null` s'il est inconnu. */
    suspend fun itemInfo(entryId: String): MediaItemInfo?

    /** Fiche du média en cours ; `null` si rien n'est en lecture. */
    suspend fun nowPlayingInfo(): MediaItemInfo?
}

/** Fonctions facultatives dont la disponibilité dépend de l'appareil ou du client piloté. */
enum class ControlFeature { NOTIFY, SCAN_LIBRARY, CLEAN_LIBRARY, QUEUE_EDIT, PLAY_MODE, TRACKS, SPEED, CHAPTERS }

/**
 * Le contrôleur sait dire qu'une fonction de ses interfaces `Supports*` est impossible avec
 * l'appareil courant (client Jellyfin qui n'accepte pas la commande, par exemple) : l'interface
 * la masque au lieu d'afficher un bouton qui échouerait. Réévalué à chaque mise à jour de l'état.
 */
interface DeclaresAvailability {
    fun isAvailable(feature: ControlFeature): Boolean
}

/** Rubrique racine de la bibliothèque (films, séries, musique, chaînes TV, favoris, add-ons…). */
data class MediaSection(val id: String, val title: String)

/** Parcours de la bibliothèque et lancement d'un élément. */
interface SupportsLibrary {
    suspend fun sections(): List<MediaSection>

    /** Contenu d'une rubrique ou d'un dossier ([MediaEntry.browsable]). */
    suspend fun browse(parentId: String, offset: Int = 0, limit: Int = 100): List<MediaEntry>
    suspend fun play(entryId: String)

    /** Ajoute à la file d'attente ; [next] : juste après l'élément en cours. */
    suspend fun enqueue(entryId: String, next: Boolean = false)
}

/** File d'attente (playlist active). */
interface SupportsQueue {
    suspend fun queue(): List<MediaEntry>

    /** Position de l'élément en cours dans [queue], ou `null`. */
    suspend fun queuePosition(): Int?
    suspend fun playQueueItem(index: Int)
    suspend fun removeQueueItem(index: Int)
    suspend fun clearQueue()
}

/** Plusieurs cibles de lecture possibles (sessions Jellyfin/Emby). */
data class PlaybackTarget(val id: String, val name: String, val detail: String? = null)

interface SupportsTargets {
    suspend fun targets(): List<PlaybackTarget>
    val currentTargetId: String?
    suspend fun selectTarget(id: String)
}
