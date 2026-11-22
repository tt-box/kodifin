package io.github.ttbox.kodifin

import io.github.ttbox.kodifin.data.DeviceSecrets
import io.github.ttbox.kodifin.model.Device
import io.github.ttbox.kodifin.model.DeviceKind
import io.github.ttbox.kodifin.connection.ConfigValidation
import io.github.ttbox.kodifin.text.UiText
import io.github.ttbox.kodifin.connection.DeviceOptions
import io.github.ttbox.kodifin.connection.buildDevice
import io.github.ttbox.kodifin.connection.quickConnectDevice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class BuildDeviceTest {
    private fun form(
        prefill: Device? = null,
        kind: DeviceKind = DeviceKind.KODI,
        username: String = "",
        password: String = "",
        host: String = "10.0.0.2",
    ) = buildDevice(prefill, kind, "", host, kind.defaultPort.toString(), "", username, password)

    @Test
    fun `the device to persist never carries the password`() {
        for (kind in listOf(DeviceKind.KODI, DeviceKind.JELLYFIN, DeviceKind.EMBY)) {
            val draft = form(kind = kind, username = " kodi ", password = "secret")
            assertEquals(mapOf("username" to "kodi"), draft.device.extras)
            assertEquals("secret", draft.password)
            assertFalse(draft.toString().contains("secret"))
        }
    }

    @Test
    fun `no username means no credential change`() {
        val draft = form(username = " ", password = "secret")
        assertNull(draft.password)
        assertEquals(emptyMap<String, String>(), draft.device.extras)
        // Types sans identifiants : le mot de passe saisi est ignoré.
        assertNull(form(kind = DeviceKind.ROKU, username = "u", password = "p").password)
    }

    @Test
    fun `editing without new credentials keeps the encrypted password`() {
        val saved = Device(
            "KODI:10.0.0.2:8080", "Kodi", DeviceKind.KODI, "10.0.0.2", 8080,
            mapOf("username" to "kodi", DeviceSecrets.ENCRYPTED_PASSWORD to "enc:x"),
        )
        val draft = form(prefill = saved)
        assertEquals(saved.extras, draft.device.extras)
        assertEquals(saved.id, draft.device.id)
        assertNull(draft.password)
    }

    @Test
    fun `new credentials drop the previous secret and token`() {
        val saved = Device(
            "j", "Jelly", DeviceKind.JELLYFIN, "10.0.0.2", 8096,
            mapOf("username" to "old", "token" to "t", "userId" to "42", "sessionId" to "s", "password" to "leak"),
        )
        val draft = form(prefill = saved, kind = DeviceKind.JELLYFIN, username = "new", password = "pw")
        assertEquals(mapOf("username" to "new", "sessionId" to "s"), draft.device.extras)
        assertEquals("pw", draft.password)
    }

    @Test
    fun `changing the host or the scheme does not carry the stored secrets to the new target`() {
        val saved = Device(
            "KODI:10.0.0.2:8080", "Kodi", DeviceKind.KODI, "10.0.0.2", 8080,
            mapOf("username" to "kodi", DeviceSecrets.ENCRYPTED_PASSWORD to "enc:x", "wol_mac" to "AA:BB:CC:DD:EE:FF"),
        )
        // Même utilisateur, mot de passe non ressaisi, autre hôte : le chiffré ne suit pas.
        val moved = form(prefill = saved, username = "kodi", host = "10.0.0.9")
        assertEquals(mapOf("username" to "kodi", "wol_mac" to "AA:BB:CC:DD:EE:FF"), moved.device.extras)
        assertNull(moved.password)
        // Autre port : idem.
        val otherPort = buildDevice(saved, DeviceKind.KODI, "", "10.0.0.2", "8081", "", "kodi", "")
        assertFalse(DeviceSecrets.ENCRYPTED_PASSWORD in otherPort.device.extras)
        // Ressaisi : envoyé au dépôt, qui le chiffre pour la nouvelle cible.
        assertEquals("pw", form(prefill = saved, username = "kodi", password = "pw", host = "10.0.0.9").password)
        // Même cible : conservé.
        assertEquals("enc:x", form(prefill = saved, username = "kodi", host = "10.0.0.2").device.extras[DeviceSecrets.ENCRYPTED_PASSWORD])
        // HTTPS → HTTP sur le même hôte : le mot de passe partirait en clair, il n'est pas conservé.
        val https = saved.copy(extras = saved.extras + ("scheme" to "https"))
        val downgraded = buildDevice(https, DeviceKind.KODI, "", "10.0.0.2", "8080", "", "kodi", "", DeviceOptions.read(saved))
        assertFalse(DeviceSecrets.ENCRYPTED_PASSWORD in downgraded.device.extras)
        // Jetons (Jellyfin) : même règle.
        val jelly = Device("j", "J", DeviceKind.JELLYFIN, "10.0.0.2", 8096, mapOf("username" to "u", "token_enc" to "enc:t", "userId" to "1"))
        val movedJelly = form(prefill = jelly, kind = DeviceKind.JELLYFIN, username = "u", host = "10.0.0.9")
        assertEquals(mapOf("username" to "u", "userId" to "1"), movedJelly.device.extras)
    }

    @Test
    fun `airplay password is kept without a username`() {
        val draft = form(kind = DeviceKind.AIRPLAY, password = "pw")
        assertEquals("pw", draft.password)
        assertEquals(emptyMap<String, String>(), draft.device.extras)
        val saved = Device("a", "AirPlay", DeviceKind.AIRPLAY, "10.0.0.2", 7000, mapOf(DeviceSecrets.ENCRYPTED_PASSWORD to "enc:x"))
        // Vide : inchangé.
        val kept = form(prefill = saved, kind = DeviceKind.AIRPLAY)
        assertEquals(saved.extras, kept.device.extras)
        assertNull(kept.password)
        // Nouveau mot de passe : l'ancien chiffré est retiré, le nouveau sera chiffré par le dépôt.
        val replaced = form(prefill = saved, kind = DeviceKind.AIRPLAY, password = "new")
        assertEquals(emptyMap<String, String>(), replaced.device.extras)
        assertEquals("new", replaced.password)
    }

    @Test
    fun `a stray password in the prefill is never copied`() {
        val prefill = Device("k", "Kodi", DeviceKind.KODI, "10.0.0.2", extras = mapOf("password" to "leak"))
        assertEquals(emptyMap<String, String>(), form(prefill = prefill).device.extras)
    }

    @Test
    fun `changing the type does not reuse the other protocol extras`() {
        val plex = Device("plex:abc", "Plex", DeviceKind.PLEX, "10.0.0.2", 32500, mapOf("token" to "t", "clientIdentifier" to "abc"))
        val draft = form(prefill = plex, kind = DeviceKind.KODI)
        assertEquals(emptyMap<String, String>(), draft.device.extras)
        assertEquals(Device.manualId(DeviceKind.KODI, "10.0.0.2", 8080), draft.device.id)
        assertEquals(DeviceKind.KODI, draft.device.kind)
    }
}

class SecretsInFormTest {
    private val jelly = Device(
        "j", "Jelly", DeviceKind.JELLYFIN, "10.0.0.2", 8096,
        mapOf("username" to "u", "userId" to "42", "token_enc" to "enc:x", "sessionId" to "s"),
    )

    @Test
    fun `quick connect keeps the pinned session when host, port and account are unchanged`() {
        val draft = buildDevice(jelly, DeviceKind.JELLYFIN, "", "10.0.0.2", "8096", "", "u", "").device
        val saved = quickConnectDevice(jelly, draft, "NOUVEAU", "42", "u")
        assertEquals("s", saved.extras["sessionId"])
        assertEquals("NOUVEAU", saved.extras["token"])
        assertNull(saved.extras["token_enc"])
    }

    @Test
    fun `quick connect drops the pinned session when the target or the account changes`() {
        val draft = buildDevice(jelly, DeviceKind.JELLYFIN, "", "10.0.0.2", "8096", "", "u", "").device
        assertNull(quickConnectDevice(jelly, draft, "T", "99", "autre").extras["sessionId"])
        val moved = buildDevice(jelly, DeviceKind.JELLYFIN, "", "10.0.0.9", "8096", "", "u", "").device
        assertNull(quickConnectDevice(jelly, moved, "T", "42", "u").extras["sessionId"])
        assertNull(quickConnectDevice(null, draft, "T", "42", "u").extras["sessionId"])
        assertEquals(mapOf("token" to "T", "userId" to "42", "username" to "u"), quickConnectDevice(null, draft.copy(extras = emptyMap()), "T", "42", "u").extras)
    }

    @Test
    fun `plex device token can be set, kept or cleared from the form`() {
        val plex = Device("p", "Plex", DeviceKind.PLEX, "10.0.0.2", 32500, mapOf("token_enc" to "enc:x", "clientIdentifier" to "c"))
        fun form(token: String?) = buildDevice(plex, DeviceKind.PLEX, "", "10.0.0.2", "32500", "", "", "", DeviceOptions.read(plex), token)
        assertEquals(plex.extras, form(null).device.extras)
        assertEquals(mapOf("clientIdentifier" to "c"), form("").device.extras)
        val replaced = form(" JETON-PLEX ")
        assertEquals(mapOf("clientIdentifier" to "c", "token" to "JETON-PLEX"), replaced.device.extras)
        // Le brouillon ne divulgue pas le jeton dans les journaux.
        assertFalse(replaced.toString().contains("JETON-PLEX"))
    }
}

class ConfigValidationMessageTest {
    @Test
    fun `hosts with forbidden characters are rejected with the exact message`() {
        for (host in listOf("tv/salon", "user@tv", "tv#1", "tv salon", "", " ")) {
            assertEquals(host, UiText.Res(R.string.config_error_invalid_address), ConfigValidation.error(DeviceKind.KODI, host, "8080", ""))
        }
    }

    @Test
    fun `valid hosts, including ipv6 and surrounding spaces, are accepted`() {
        for (host in listOf("192.168.1.2", "tv.local", "[fe80::1]", "fe80::1", " tv-salon ")) {
            assertNull(host, ConfigValidation.error(DeviceKind.ROKU, host, "8060", ""))
        }
    }

    @Test
    fun `ports outside 1 to 65535 are rejected with the exact message`() {
        for (port in listOf("0", "65536", "", "abc")) {
            assertEquals(port, UiText.Res(R.string.config_error_invalid_port), ConfigValidation.error(DeviceKind.KODI, "tv.local", port, ""))
        }
        assertNull(ConfigValidation.error(DeviceKind.KODI, "tv.local", "65535", ""))
    }

    @Test
    fun `a password without a username is refused instead of being silently ignored`() {
        assertEquals(
            UiText.Res(R.string.config_error_username_required_kodi),
            ConfigValidation.error(DeviceKind.KODI, "tv.local", "8080", "", username = " ", password = "pw"),
        )
        assertEquals(
            UiText.Res(R.string.config_error_username_required),
            ConfigValidation.error(DeviceKind.JELLYFIN, "tv.local", "8096", "", username = "", password = "pw"),
        )
        assertNull(ConfigValidation.error(DeviceKind.KODI, "tv.local", "8080", "", username = "kodi", password = "pw"))
        assertNull(ConfigValidation.error(DeviceKind.KODI, "tv.local", "8080", "", username = "", password = ""))
        // AirPlay : mot de passe seul, sans utilisateur.
        assertNull(ConfigValidation.error(DeviceKind.AIRPLAY, "tv.local", "7000", "", username = "", password = "pw"))
    }

    @Test
    fun `upnp needs a valid description url, host and port are ignored`() {
        assertEquals(UiText.Res(R.string.config_error_upnp_location_required), ConfigValidation.error(DeviceKind.UPNP_RENDERER, "x", "1", ""))
        assertNull(ConfigValidation.error(DeviceKind.UPNP_RENDERER, "bad host!", "0", "https://tv.local/d.xml"))
    }
}
