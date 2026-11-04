package io.github.jmorille.zapette

import io.github.jmorille.zapette.data.DeviceSecrets
import io.github.jmorille.zapette.model.Device
import io.github.jmorille.zapette.model.DeviceKind
import io.github.jmorille.zapette.ui.config.ConfigValidation
import io.github.jmorille.zapette.connection.DeviceOptions
import io.github.jmorille.zapette.ui.config.buildDevice
import io.github.jmorille.zapette.ui.config.quickConnectDevice
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
            assertEquals(host, "Adresse invalide", ConfigValidation.error(DeviceKind.KODI, host, "8080", ""))
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
            assertEquals(port, "Port invalide", ConfigValidation.error(DeviceKind.KODI, "tv.local", port, ""))
        }
        assertNull(ConfigValidation.error(DeviceKind.KODI, "tv.local", "65535", ""))
    }

    @Test
    fun `upnp needs a valid description url, host and port are ignored`() {
        assertEquals("URL de description UPnP requise", ConfigValidation.error(DeviceKind.UPNP_RENDERER, "x", "1", ""))
        assertNull(ConfigValidation.error(DeviceKind.UPNP_RENDERER, "bad host!", "0", "https://tv.local/d.xml"))
    }
}
