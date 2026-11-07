package io.github.ttbox.kodifin.text

import io.github.ttbox.kodifin.R
import io.github.ttbox.kodifin.model.DeviceKind
import io.github.ttbox.kodifin.model.RemoteError
import io.github.ttbox.kodifin.model.RemoteException
import io.github.ttbox.kodifin.model.Setup
import io.github.ttbox.kodifin.model.UnsupportedAction
import io.github.ttbox.kodifin.model.UnsupportedActionException

/*
 * Traduction des erreurs typées des protocoles (core/model). Point unique : tout message
 * d'erreur affiché passe par [toUiText]. Les `when` sont exhaustifs : un cas ajouté dans
 * core/model ne compile pas tant qu'il n'est pas traduit ici.
 */

/** Erreur levée par l'application elle-même, qui porte directement son texte. */
class AppException(val text: UiText, cause: Throwable? = null) : Exception(text.toString(), cause)

/**
 * Texte d'une erreur quelconque : raison typée si elle existe, sinon message brut de
 * l'exception (erreur système, déjà localisée par Android), sinon [fallback].
 */
fun Throwable.toUiText(fallback: UiText? = null): UiText = when (this) {
    is RemoteException -> reason.text
    is UnsupportedActionException -> action.text(kind)
    is AppException -> text
    else -> message?.takeIf { it.isNotBlank() }?.let(UiText::Raw)
        ?: fallback
        ?: UiText.Res(R.string.error_unknown, javaClass.simpleName)
}

val RemoteError.text: UiText
    get() = when (this) {
        is RemoteError.Unreachable -> withOptional(host, R.string.error_unreachable_host, R.string.error_unreachable)
        is RemoteError.Interrupted -> withOptional(host, R.string.error_interrupted_host, R.string.error_interrupted)
        RemoteError.Timeout -> UiText.Res(R.string.error_timeout)
        is RemoteError.ResponseTooLarge -> UiText.Res(R.string.error_response_too_large, maxBytes, host)
        is RemoteError.InvalidAddress ->
            withOptional(address, R.string.error_invalid_address_value, R.string.error_invalid_address)
        is RemoteError.TlsFailed -> withOptional(host, R.string.error_tls_host, R.string.error_tls)
        RemoteError.RedirectRefused -> UiText.Res(R.string.error_redirect_refused)
        is RemoteError.Http -> UiText.Res(R.string.error_http, code)
        is RemoteError.UnexpectedResponse ->
            withOptional(service, R.string.error_unexpected_response_service, R.string.error_unexpected_response)
        is RemoteError.DeviceRefused -> deviceRefused(detail?.takeIf { it.isNotBlank() }, code?.takeIf { it.isNotBlank() })
        RemoteError.CredentialsRejected -> UiText.Res(R.string.error_credentials_rejected)
        RemoteError.SessionExpired -> UiText.Res(R.string.error_session_expired)
        RemoteError.PasswordRequired -> UiText.Res(R.string.error_password_required)
        RemoteError.PasswordRejected -> UiText.Res(R.string.error_password_rejected)
        RemoteError.CodeRejected -> UiText.Res(R.string.error_code_rejected)
        RemoteError.CodeExpired -> UiText.Res(R.string.error_code_expired)
        RemoteError.AdminRequired -> UiText.Res(R.string.error_admin_required)
        RemoteError.NothingPlaying -> UiText.Res(R.string.error_nothing_playing)
        RemoteError.NoControllableClient -> UiText.Res(R.string.error_no_controllable_client)
        RemoteError.ItemNotFound -> UiText.Res(R.string.error_item_not_found)
        RemoteError.SessionEnded -> UiText.Res(R.string.error_session_ended)
        is RemoteError.SetupRequired -> UiText.Res(setup.res)
    }

private val Setup.res: Int
    get() = when (this) {
        Setup.ROKU_NETWORK_ACCESS -> R.string.error_setup_roku_network_access
        Setup.ADB_DEBUGGING -> R.string.error_setup_adb_debugging
        Setup.ADB_PAIRING_WINDOW -> R.string.error_setup_adb_pairing_window
        Setup.AIRPLAY_1_REQUIRED -> R.string.error_setup_airplay_1
        Setup.MEDIA_SERVER_BASE_PATH -> R.string.error_setup_media_server_base_path
        Setup.QUICK_CONNECT_DISABLED -> R.string.error_setup_quick_connect_disabled
        Setup.GOOGLE_CAST -> R.string.error_setup_google_cast
    }

/** Libellé d'une action non prise en charge, avec le type d'appareil s'il est connu. */
fun UnsupportedAction.text(kind: DeviceKind? = null): UiText {
    val subject: UiText = when (this) {
        // Phrases complètes : il n'y a rien à « prendre en charge », le média n'a pas l'élément.
        UnsupportedAction.NoSubtitles -> return UiText.Res(R.string.unsupported_no_subtitles)
        UnsupportedAction.NoAudioTracks -> return UiText.Res(R.string.unsupported_no_audio_tracks)
        UnsupportedAction.NoChapters -> return UiText.Res(R.string.unsupported_no_chapters)
        UnsupportedAction.PlayUrl -> UiText.Res(R.string.unsupported_play_url)
        UnsupportedAction.Volume -> UiText.Res(R.string.unsupported_volume)
        UnsupportedAction.Seek -> UiText.Res(R.string.unsupported_seek)
        UnsupportedAction.TextInput -> UiText.Res(R.string.unsupported_text_input)
        UnsupportedAction.Search -> UiText.Res(R.string.unsupported_search)
        is UnsupportedAction.Key -> UiText.Res(R.string.unsupported_key, key.name)
        is UnsupportedAction.Command -> command.text
        is UnsupportedAction.Power -> action.text
        is UnsupportedAction.ServerMethod -> UiText.Res(R.string.unsupported_server_method, method)
        is UnsupportedAction.MediaType -> UiText.Res(R.string.unsupported_media_type, type)
        is UnsupportedAction.Characters -> UiText.Res(R.string.unsupported_characters, characters)
        UnsupportedAction.Browse -> UiText.Res(R.string.unsupported_browse)
        UnsupportedAction.Play -> UiText.Res(R.string.unsupported_play)
        UnsupportedAction.Enqueue -> UiText.Res(R.string.unsupported_enqueue)
        UnsupportedAction.EditQueue -> UiText.Res(R.string.unsupported_edit_queue)
        UnsupportedAction.CleanLibrary -> UiText.Res(R.string.unsupported_clean_library)
        UnsupportedAction.PlayMode -> UiText.Res(R.string.unsupported_play_mode)
    }
    // DeviceKind.label : nom de produit, identique dans toutes les langues.
    return if (kind != null) UiText.Res(R.string.unsupported_by, subject, kind.label) else UiText.Res(R.string.unsupported, subject)
}

private fun withOptional(value: String?, withValue: Int, without: Int): UiText =
    value?.takeIf { it.isNotBlank() }?.let { UiText.Res(withValue, it) } ?: UiText.Res(without)

private fun deviceRefused(detail: String?, code: String?): UiText = when {
    detail != null && code != null -> UiText.Res(R.string.error_device_refused_code_detail, code, detail)
    detail != null -> UiText.Res(R.string.error_device_refused_detail, detail)
    code != null -> UiText.Res(R.string.error_device_refused_code, code)
    else -> UiText.Res(R.string.error_device_refused)
}

