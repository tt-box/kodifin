package io.github.ttbox.kodifin.text

import io.github.ttbox.kodifin.R
import io.github.ttbox.kodifin.model.DeviceKind
import io.github.ttbox.kodifin.model.HttpStatusException
import io.github.ttbox.kodifin.model.RemoteError
import io.github.ttbox.kodifin.model.RemoteException
import io.github.ttbox.kodifin.model.RemoteKey
import io.github.ttbox.kodifin.model.Setup
import io.github.ttbox.kodifin.model.UnsupportedAction
import io.github.ttbox.kodifin.model.UnsupportedActionException
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException

class ErrorTextTest {
    @Test
    fun `les raisons portent leurs paramètres, et un paramètre absent choisit la forme courte`() {
        assertEquals(UiText.Res(R.string.error_unreachable_host, "kodi.lan"), RemoteError.Unreachable("kodi.lan").text)
        assertEquals(UiText.Res(R.string.error_unreachable), RemoteError.Unreachable().text)
        assertEquals(UiText.Res(R.string.error_unreachable), RemoteError.Unreachable(" ").text)
        assertEquals(UiText.Res(R.string.error_http, 500), HttpStatusException(500).reason.text)
        assertEquals(UiText.Res(R.string.error_response_too_large, 10L, "nas"), RemoteError.ResponseTooLarge("nas", 10).text)
    }

    @Test
    fun `le texte de l'appareil est inséré tel quel, avec son code s'il existe`() {
        assertEquals(
            UiText.Res(R.string.error_device_refused_code_detail, "-32602", "Invalid params"),
            RemoteError.DeviceRefused("Invalid params", "-32602").text,
        )
        assertEquals(UiText.Res(R.string.error_device_refused_detail, "Busy"), RemoteError.DeviceRefused("Busy").text)
        assertEquals(UiText.Res(R.string.error_device_refused_code, "402"), RemoteError.DeviceRefused(code = "402").text)
        assertEquals(UiText.Res(R.string.error_device_refused), RemoteError.DeviceRefused().text)
    }

    @Test
    fun `les réglages connus ont chacun leur conseil`() {
        val texts = Setup.entries.map { RemoteError.SetupRequired(it).text }
        assertEquals(Setup.entries.size, texts.toSet().size)
    }

    @Test
    fun `une action non prise en charge nomme l'appareil quand il est connu`() {
        assertEquals(
            UiText.Res(R.string.unsupported_by, UiText.Res(R.string.unsupported_key, "HOME"), "Roku"),
            UnsupportedAction.Key(RemoteKey.HOME).text(DeviceKind.ROKU),
        )
        assertEquals(UiText.Res(R.string.unsupported, UiText.Res(R.string.unsupported_volume)), UnsupportedAction.Volume.text())
        // Phrase complète : le média n'a pas l'élément, l'appareil n'y est pour rien.
        assertEquals(UiText.Res(R.string.unsupported_no_chapters), UnsupportedAction.NoChapters.text(DeviceKind.JELLYFIN))
    }

    @Test
    fun `toUiText choisit la raison typée, puis le message système, puis le repli`() {
        assertEquals(UiText.Res(R.string.error_timeout), RemoteException(RemoteError.Timeout).toUiText())
        assertEquals(
            UiText.Res(R.string.unsupported_by, UiText.Res(R.string.unsupported_seek), "AirPlay"),
            UnsupportedActionException(UnsupportedAction.Seek, DeviceKind.AIRPLAY).toUiText(),
        )
        assertEquals(UiText.Res(1), AppException(UiText.Res(1)).toUiText())
        assertEquals(UiText.Raw("disque plein"), IOException("disque plein").toUiText(UiText.Res(2)))
        assertEquals(UiText.Res(2), IOException().toUiText(UiText.Res(2)))
        assertEquals(UiText.Res(R.string.error_unknown, "IOException"), IOException().toUiText())
    }
}
