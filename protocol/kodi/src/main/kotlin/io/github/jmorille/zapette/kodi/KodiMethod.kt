package io.github.jmorille.zapette.kodi

/**
 * Méthodes JSON-RPC Kodi utilisées par Zapette (API v12+, voir
 * https://kodi.wiki/view/JSON-RPC_API). Regroupées par espace de noms.
 */
object KodiMethod {
    // JSONRPC
    const val PING = "JSONRPC.Ping"
    const val VERSION = "JSONRPC.Version"
    const val INTROSPECT = "JSONRPC.Introspect"

    // Input
    const val INPUT_UP = "Input.Up"
    const val INPUT_DOWN = "Input.Down"
    const val INPUT_LEFT = "Input.Left"
    const val INPUT_RIGHT = "Input.Right"
    const val INPUT_SELECT = "Input.Select"
    const val INPUT_BACK = "Input.Back"
    const val INPUT_HOME = "Input.Home"
    const val INPUT_CONTEXT_MENU = "Input.ContextMenu"
    const val INPUT_INFO = "Input.Info"
    const val INPUT_SHOW_OSD = "Input.ShowOSD"
    const val INPUT_SHOW_CODEC = "Input.ShowCodec"
    const val INPUT_EXECUTE_ACTION = "Input.ExecuteAction"
    const val INPUT_SEND_TEXT = "Input.SendText"

    // Player
    const val PLAYER_GET_ACTIVE = "Player.GetActivePlayers"
    const val PLAYER_GET_PROPERTIES = "Player.GetProperties"
    const val PLAYER_GET_ITEM = "Player.GetItem"
    const val PLAYER_PLAY_PAUSE = "Player.PlayPause"
    const val PLAYER_STOP = "Player.Stop"
    const val PLAYER_GO_TO = "Player.GoTo"
    const val PLAYER_SEEK = "Player.Seek"
    const val PLAYER_SET_SPEED = "Player.SetSpeed"
    const val PLAYER_OPEN = "Player.Open"
    const val PLAYER_SET_SUBTITLE = "Player.SetSubtitle"
    const val PLAYER_SET_AUDIO_STREAM = "Player.SetAudioStream"
    const val PLAYER_SET_REPEAT = "Player.SetRepeat"
    const val PLAYER_SET_SHUFFLE = "Player.SetShuffle"

    // Playlist
    const val PLAYLIST_GET_PLAYLISTS = "Playlist.GetPlaylists"
    const val PLAYLIST_GET_ITEMS = "Playlist.GetItems"
    const val PLAYLIST_ADD = "Playlist.Add"
    const val PLAYLIST_INSERT = "Playlist.Insert"
    const val PLAYLIST_CLEAR = "Playlist.Clear"
    const val PLAYLIST_REMOVE = "Playlist.Remove"

    // Application
    const val APP_GET_PROPERTIES = "Application.GetProperties"
    const val APP_SET_VOLUME = "Application.SetVolume"
    const val APP_SET_MUTE = "Application.SetMute"
    const val APP_QUIT = "Application.Quit"

    // VideoLibrary
    const val VIDEO_GET_MOVIES = "VideoLibrary.GetMovies"
    const val VIDEO_GET_MOVIE_DETAILS = "VideoLibrary.GetMovieDetails"
    const val VIDEO_GET_RECENT_MOVIES = "VideoLibrary.GetRecentlyAddedMovies"
    const val VIDEO_GET_TV_SHOWS = "VideoLibrary.GetTVShows"
    const val VIDEO_GET_SEASONS = "VideoLibrary.GetSeasons"
    const val VIDEO_GET_EPISODES = "VideoLibrary.GetEpisodes"
    const val VIDEO_GET_RECENT_EPISODES = "VideoLibrary.GetRecentlyAddedEpisodes"
    const val VIDEO_GET_MUSIC_VIDEOS = "VideoLibrary.GetMusicVideos"
    const val VIDEO_SCAN = "VideoLibrary.Scan"
    const val VIDEO_CLEAN = "VideoLibrary.Clean"

    // AudioLibrary
    const val AUDIO_GET_ARTISTS = "AudioLibrary.GetArtists"
    const val AUDIO_GET_ALBUMS = "AudioLibrary.GetAlbums"
    const val AUDIO_GET_SONGS = "AudioLibrary.GetSongs"
    const val AUDIO_GET_RECENT_ALBUMS = "AudioLibrary.GetRecentlyAddedAlbums"
    const val AUDIO_SCAN = "AudioLibrary.Scan"
    const val AUDIO_CLEAN = "AudioLibrary.Clean"

    // GUI
    const val GUI_ACTIVATE_WINDOW = "GUI.ActivateWindow"
    const val GUI_SHOW_NOTIFICATION = "GUI.ShowNotification"
    const val GUI_SET_FULLSCREEN = "GUI.SetFullscreen"
    const val GUI_GET_PROPERTIES = "GUI.GetProperties"

    // System
    const val SYSTEM_SHUTDOWN = "System.Shutdown"
    const val SYSTEM_REBOOT = "System.Reboot"
    const val SYSTEM_SUSPEND = "System.Suspend"
    const val SYSTEM_HIBERNATE = "System.Hibernate"
    const val SYSTEM_GET_PROPERTIES = "System.GetProperties"

    // Files / PVR / Addons / Favourites / Settings / XBMC
    const val FILES_GET_SOURCES = "Files.GetSources"
    const val FILES_GET_DIRECTORY = "Files.GetDirectory"
    const val FILES_PREPARE_DOWNLOAD = "Files.PrepareDownload"
    const val PVR_GET_CHANNEL_GROUPS = "PVR.GetChannelGroups"
    const val PVR_GET_CHANNELS = "PVR.GetChannels"
    const val PVR_RECORD = "PVR.Record"
    const val ADDONS_GET_ADDONS = "Addons.GetAddons"
    const val ADDONS_EXECUTE = "Addons.ExecuteAddon"
    const val FAVOURITES_GET = "Favourites.GetFavourites"
    const val SETTINGS_GET_VALUE = "Settings.GetSettingValue"
    const val SETTINGS_SET_VALUE = "Settings.SetSettingValue"
    const val XBMC_GET_INFO_LABELS = "XBMC.GetInfoLabels"
}
