package io.github.jmorille.zapette.mediaserver

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/*
 * Objets échangés avec l'API de sessions et de bibliothèque, communs à Jellyfin et Emby
 * (schémas publics `SessionInfoDto`, `PlayerStateInfo`, `BaseItemDto`… de l'OpenAPI Jellyfin).
 * Seuls les champs utiles sont décrits ; les autres sont ignorés.
 */

@Serializable
data class SessionDto(
    @SerialName("Id") val id: String,
    @SerialName("DeviceId") val deviceId: String? = null,
    @SerialName("DeviceName") val deviceName: String = "",
    @SerialName("Client") val client: String = "",
    @SerialName("UserName") val userName: String? = null,
    @SerialName("SupportsRemoteControl") val supportsRemoteControl: Boolean = false,
    @SerialName("NowPlayingItem") val nowPlaying: NowPlayingDto? = null,
    @SerialName("PlayState") val playState: PlayStateDto? = null,
    /** Commandes générales acceptées (Emby et anciens Jellyfin la publient ici). */
    @SerialName("SupportedCommands") val supportedCommands: List<String>? = null,
    @SerialName("PlayableMediaTypes") val playableMediaTypes: List<String>? = null,
    /** Jellyfin : capacités déclarées par le client. */
    @SerialName("Capabilities") val capabilities: ClientCapabilitiesDto? = null,
    /** File de lecture (Jellyfin). */
    @SerialName("NowPlayingQueue") val nowPlayingQueue: List<QueueItemDto>? = null,
    /** Élément de la file en cours de lecture (Jellyfin). */
    @SerialName("PlaylistItemId") val playlistItemId: String? = null,
) {
    /** Commandes générales acceptées ; liste vide : inconnue (tout est permis). */
    val commands: List<String>
        get() = capabilities?.supportedCommands?.takeIf { it.isNotEmpty() } ?: supportedCommands.orEmpty()

    /** Types de médias lisibles ; liste vide : inconnue. */
    val mediaTypes: List<String>
        get() = capabilities?.playableMediaTypes?.takeIf { it.isNotEmpty() } ?: playableMediaTypes.orEmpty()
}

@Serializable
data class ClientCapabilitiesDto(
    @SerialName("PlayableMediaTypes") val playableMediaTypes: List<String>? = null,
    @SerialName("SupportedCommands") val supportedCommands: List<String>? = null,
)

@Serializable
data class QueueItemDto(
    @SerialName("Id") val id: String = "",
    @SerialName("PlaylistItemId") val playlistItemId: String? = null,
)

@Serializable
data class NowPlayingDto(
    @SerialName("Id") val id: String? = null,
    @SerialName("Name") val name: String = "",
    @SerialName("RunTimeTicks") val runTimeTicks: Long = 0,
    @SerialName("MediaStreams") val mediaStreams: List<MediaStreamDto>? = null,
    @SerialName("Chapters") val chapters: List<ChapterDto>? = null,
    /** `Movie`, `Episode`, `Audio`… */
    @SerialName("Type") val type: String? = null,
    @SerialName("SeriesName") val seriesName: String? = null,
    @SerialName("SeriesId") val seriesId: String? = null,
    @SerialName("SeasonId") val seasonId: String? = null,
    /** Épisode : numéro de saison. */
    @SerialName("ParentIndexNumber") val parentIndexNumber: Int? = null,
    /** Épisode : numéro dans la saison. */
    @SerialName("IndexNumber") val indexNumber: Int? = null,
    @SerialName("Album") val album: String? = null,
    @SerialName("AlbumId") val albumId: String? = null,
    @SerialName("AlbumArtist") val albumArtist: String? = null,
    @SerialName("Artists") val artists: List<String>? = null,
    @SerialName("ImageTags") val imageTags: Map<String, String>? = null,
    @SerialName("BackdropImageTags") val backdropImageTags: List<String>? = null,
    @SerialName("ParentBackdropItemId") val parentBackdropItemId: String? = null,
    /** Conteneur du fichier (`mkv`, `mov,mp4,m4a`…). */
    @SerialName("Container") val container: String? = null,
) {
    val isEpisode: Boolean get() = type.equals("Episode", ignoreCase = true)
    val isAudio: Boolean get() = type.equals("Audio", ignoreCase = true)

    /** Affiche : celle de la série pour un épisode, la pochette d'album pour un titre, sinon la sienne. */
    val artworkKey: String?
        get() = when {
            isEpisode && !seriesId.isNullOrBlank() -> "$seriesId/Primary"
            isAudio && imageTags?.containsKey("Primary") != true && !albumId.isNullOrBlank() -> "$albumId/Primary"
            !id.isNullOrBlank() -> "$id/Primary"
            else -> null
        }

    /** Fond : fanart de l'élément, sinon de son parent (série, album), sinon de la série. */
    val backdropKey: String?
        get() = when {
            !backdropImageTags.isNullOrEmpty() && !id.isNullOrBlank() -> "$id/Backdrop/${backdropImageTags.first()}"
            !parentBackdropItemId.isNullOrBlank() -> "$parentBackdropItemId/Backdrop"
            isEpisode && !seriesId.isNullOrBlank() -> "$seriesId/Backdrop"
            else -> null
        }
}

@Serializable
data class PlayStateDto(
    @SerialName("PositionTicks") val positionTicks: Long = 0,
    @SerialName("IsPaused") val isPaused: Boolean = false,
    @SerialName("IsMuted") val isMuted: Boolean = false,
    @SerialName("VolumeLevel") val volumeLevel: Int? = null,
    @SerialName("AudioStreamIndex") val audioStreamIndex: Int? = null,
    @SerialName("SubtitleStreamIndex") val subtitleStreamIndex: Int? = null,
    @SerialName("MediaSourceId") val mediaSourceId: String? = null,
    /** `RepeatNone`, `RepeatOne`, `RepeatAll`. */
    @SerialName("RepeatMode") val repeatMode: String? = null,
    /** `Sorted` ou `Shuffle` (Emby, Jellyfin < 10.9). */
    @SerialName("ShuffleMode") val shuffleMode: String? = null,
    /** `Default` ou `Shuffle` (Jellyfin ≥ 10.9). */
    @SerialName("PlaybackOrder") val playbackOrder: String? = null,
)

@Serializable
data class MediaStreamDto(
    /** `Audio`, `Subtitle`, `Video`… */
    @SerialName("Type") val type: String = "",
    @SerialName("Index") val index: Int = -1,
    @SerialName("DisplayTitle") val displayTitle: String? = null,
    @SerialName("Title") val title: String? = null,
    @SerialName("Language") val language: String? = null,
    @SerialName("IsDefault") val isDefault: Boolean? = null,
)

@Serializable
data class ChapterDto(
    @SerialName("StartPositionTicks") val startPositionTicks: Long = 0,
    @SerialName("Name") val name: String? = null,
)

@Serializable
data class MediaSourceDto(
    @SerialName("Id") val id: String? = null,
    @SerialName("MediaStreams") val mediaStreams: List<MediaStreamDto>? = null,
)

@Serializable
data class ItemDto(
    @SerialName("Id") val id: String = "",
    @SerialName("Name") val name: String = "",
    @SerialName("Type") val type: String? = null,
    @SerialName("MediaType") val mediaType: String? = null,
    @SerialName("IsFolder") val isFolder: Boolean = false,
    @SerialName("SeriesName") val seriesName: String? = null,
    @SerialName("ProductionYear") val productionYear: Int? = null,
    @SerialName("IndexNumber") val indexNumber: Int? = null,
    @SerialName("ParentIndexNumber") val parentIndexNumber: Int? = null,
    @SerialName("MediaSources") val mediaSources: List<MediaSourceDto>? = null,
    @SerialName("MediaStreams") val mediaStreams: List<MediaStreamDto>? = null,
    @SerialName("Chapters") val chapters: List<ChapterDto>? = null,
    @SerialName("SeriesId") val seriesId: String? = null,
    @SerialName("SeasonId") val seasonId: String? = null,
    @SerialName("RunTimeTicks") val runTimeTicks: Long? = null,
    /** Étiquettes d'image par type (`Primary`, `Thumb`, `Logo`…) : la présence vaut disponibilité. */
    @SerialName("ImageTags") val imageTags: Map<String, String>? = null,
    @SerialName("BackdropImageTags") val backdropImageTags: List<String>? = null,
    /** Épisode ou saison : affiche de la série, à défaut d'image propre. */
    @SerialName("SeriesPrimaryImageTag") val seriesPrimaryImageTag: String? = null,
    @SerialName("UserData") val userData: UserDataDto? = null,
    @SerialName("Overview") val overview: String? = null,
    @SerialName("CommunityRating") val communityRating: Double? = null,
    @SerialName("OfficialRating") val officialRating: String? = null,
    /** Date ISO 8601 (`2017-04-08T00:00:00.0000000Z`). */
    @SerialName("PremiereDate") val premiereDate: String? = null,
    @SerialName("Genres") val genres: List<String>? = null,
    @SerialName("Studios") val studios: List<NameDto>? = null,
    @SerialName("People") val people: List<PersonDto>? = null,
)

@Serializable
data class NameDto(@SerialName("Name") val name: String = "")

/** Membre de l'équipe : `Type` vaut `Actor`, `Director`, `Writer`, `GuestStar`… */
@Serializable
data class PersonDto(
    @SerialName("Name") val name: String = "",
    @SerialName("Role") val role: String? = null,
    @SerialName("Type") val type: String? = null,
)

/** Données propres à l'utilisateur (`UserItemDataDto`) : seul l'état « vu » est utilisé. */
@Serializable
data class UserDataDto(@SerialName("Played") val played: Boolean? = null)

@Serializable
data class ItemsResultDto(
    @SerialName("Items") val items: List<ItemDto> = emptyList(),
    @SerialName("TotalRecordCount") val totalRecordCount: Int = 0,
)
