package io.github.ttbox.kodifin.connection

import io.github.ttbox.kodifin.data.DeviceSecrets
import io.github.ttbox.kodifin.model.Device
import io.github.ttbox.kodifin.model.DeviceKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Formulaire : cible changée (schéma compris) et indice « à ressaisir » des secrets supprimés. */
class RetypeHintTest {
    private val jelly = Device(
        "j", "Serveur", DeviceKind.JELLYFIN, "10.0.0.5", 8096,
        mapOf("username" to "jo", DeviceSecrets.ENCRYPTED_TOKEN to "enc:t", "userId" to "u1"),
    )
    private val https = AdvancedOptions(mode = ConnectionMode.HTTPS)
    private val http = AdvancedOptions()

    @Test
    fun `target change includes the scheme, like buildDevice`() {
        assertFalse(targetChanged(jelly, DeviceKind.JELLYFIN, " 10.0.0.5 ", "8096", "", http))
        assertFalse(targetChanged(jelly, DeviceKind.JELLYFIN, "10.0.0.5", "8096", "", null))
        assertTrue(targetChanged(jelly, DeviceKind.JELLYFIN, "10.0.0.6", "8096", "", http))
        assertTrue(targetChanged(jelly, DeviceKind.JELLYFIN, "10.0.0.5", "8920", "", http))
        assertTrue("HTTP → HTTPS", targetChanged(jelly, DeviceKind.JELLYFIN, "10.0.0.5", "8096", "", https))
        assertTrue("port illisible", targetChanged(jelly, DeviceKind.JELLYFIN, "10.0.0.5", "x", "", http))
        // Même règle que buildDevice : le jeton disparaît exactement quand targetChanged le dit.
        val draft = buildDevice(jelly, DeviceKind.JELLYFIN, "", "10.0.0.5", "8096", "", "jo", "", https).device
        assertFalse(DeviceSecrets.ENCRYPTED_TOKEN in draft.extras)
        // Nouvel appareil ou changement de type : rien à ressaisir.
        assertFalse(targetChanged(null, DeviceKind.JELLYFIN, "10.0.0.6", "8096", "", http))
        assertFalse(targetChanged(jelly, DeviceKind.EMBY, "10.0.0.6", "8096", "", http))
    }

    @Test
    fun `upnp target comes from the description url`() {
        val upnp = Device("u", "TV", DeviceKind.UPNP_RENDERER, "10.0.0.7", 1400, mapOf("location" to "http://10.0.0.7:1400/d.xml"))
        assertFalse(targetChanged(upnp, DeviceKind.UPNP_RENDERER, "", "", "http://10.0.0.7:1400/autre.xml", null))
        assertTrue(targetChanged(upnp, DeviceKind.UPNP_RENDERER, "", "", "http://10.0.0.8:1400/d.xml", null))
    }

    @Test
    fun `retype hint appears as soon as a stored secret would be dropped`() {
        assertNull(retypeHint(jelly, targetChanged = false))
        assertNull(retypeHint(null, targetChanged = true))
        assertEquals(RetypeHint.MEDIA_SERVER_TOKEN, retypeHint(jelly, targetChanged = true))
        val plex = Device("p", "Plex", DeviceKind.PLEX, "10.0.0.9", 32400, mapOf(DeviceSecrets.ENCRYPTED_TOKEN to "enc:t"))
        assertEquals(RetypeHint.PLEX_TOKEN, retypeHint(plex, targetChanged = true))
        val kodi = Device("k", "Kodi", DeviceKind.KODI, "10.0.0.2", 8080, mapOf("username" to "kodi", DeviceSecrets.ENCRYPTED_PASSWORD to "enc:pw"))
        assertEquals(RetypeHint.PASSWORD, retypeHint(kodi, targetChanged = true))
        assertNull(retypeHint(kodi.copy(extras = mapOf("username" to "kodi")), targetChanged = true))
    }
}
