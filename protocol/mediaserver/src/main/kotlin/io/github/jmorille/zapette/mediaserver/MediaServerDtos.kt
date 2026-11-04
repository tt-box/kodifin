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
)

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
)

@Serializable
data class ItemsResultDto(
    @SerialName("Items") val items: List<ItemDto> = emptyList(),
    @SerialName("TotalRecordCount") val totalRecordCount: Int = 0,
)
