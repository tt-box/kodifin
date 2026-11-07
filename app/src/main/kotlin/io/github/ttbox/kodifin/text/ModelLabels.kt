package io.github.ttbox.kodifin.text

import androidx.annotation.StringRes
import io.github.ttbox.kodifin.R
import io.github.ttbox.kodifin.model.MediaKind
import io.github.ttbox.kodifin.model.PowerAction
import io.github.ttbox.kodifin.model.UiCommand

/*
 * Libellés traduisibles des énumérations de core/model. Le module JVM garde son `label`
 * (journaux, tests) ; l'interface affiche toujours ces ressources. Le `when` exhaustif fait
 * échouer la compilation si une valeur est ajoutée sans libellé.
 *
 * DeviceKind n'a pas d'équivalent : ses libellés sont des noms de produits, identiques partout.
 */

val UiCommand.text: UiText
    get() = UiText.Res(labelRes)

@get:StringRes
val UiCommand.labelRes: Int
    get() = when (this) {
        UiCommand.OSD -> R.string.ui_command_osd
        UiCommand.CONTEXT_MENU -> R.string.ui_command_context_menu
        UiCommand.CODEC_INFO -> R.string.ui_command_codec_info
        UiCommand.FULLSCREEN -> R.string.ui_command_fullscreen
        UiCommand.HOME_SCREEN -> R.string.ui_command_home_screen
        UiCommand.SETTINGS -> R.string.ui_command_settings
        UiCommand.SEARCH -> R.string.ui_command_search
        UiCommand.PAGE_UP -> R.string.ui_command_page_up
        UiCommand.PAGE_DOWN -> R.string.ui_command_page_down
        UiCommand.CHANNEL_UP -> R.string.ui_command_channel_up
        UiCommand.CHANNEL_DOWN -> R.string.ui_command_channel_down
        UiCommand.GUIDE -> R.string.ui_command_guide
    }

val PowerAction.text: UiText
    get() = UiText.Res(labelRes)

@get:StringRes
val PowerAction.labelRes: Int
    get() = when (this) {
        PowerAction.SUSPEND -> R.string.power_action_suspend
        PowerAction.HIBERNATE -> R.string.power_action_hibernate
        PowerAction.SHUTDOWN -> R.string.power_action_shutdown
        PowerAction.REBOOT -> R.string.power_action_reboot
        PowerAction.QUIT_APP -> R.string.power_action_quit_app
    }

val MediaKind.text: UiText
    get() = UiText.Res(labelRes)

@get:StringRes
val MediaKind.labelRes: Int
    get() = when (this) {
        MediaKind.MOVIE -> R.string.media_kind_movie
        MediaKind.SERIES -> R.string.media_kind_series
        MediaKind.SEASON -> R.string.media_kind_season
        MediaKind.EPISODE -> R.string.media_kind_episode
        MediaKind.ARTIST -> R.string.media_kind_artist
        MediaKind.ALBUM -> R.string.media_kind_album
        MediaKind.SONG -> R.string.media_kind_song
        MediaKind.CHANNEL -> R.string.media_kind_channel
        MediaKind.FOLDER -> R.string.media_kind_folder
        MediaKind.OTHER -> R.string.media_kind_other
    }
