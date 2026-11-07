package io.github.ttbox.kodifin.data

import io.github.ttbox.kodifin.R
import io.github.ttbox.kodifin.model.Device
import io.github.ttbox.kodifin.model.DeviceKind
import io.github.ttbox.kodifin.text.UiText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.Deflater

class DeviceExportTest {
    private val kodi = Device(
        "k", "Salon", DeviceKind.KODI, "192.168.1.10", 8080,
        mapOf(
            "username" to "kodi", "password" to "clair", "password_enc" to "enc:xyz", "scheme" to "https",
            "tls_insecure" to "true", "kodi_ws" to "true", "kodi_ws_port" to "9090", "kodi_eventserver_port" to "9777",
            "wol_mac" to "AA:BB:CC:DD:EE:FF", "wifi_ssid" to "Maison", "timeout_s" to "5",
        ),
    )
    private val plex = Device(
        "p", "Plex", DeviceKind.PLEX, "plex.local", 32500,
        mapOf("token" to "PLEXTOKEN", "clientIdentifier" to "cid", "clientId" to "cid2"),
    )
    private val jelly = Device(
        "j", "Jelly", DeviceKind.JELLYFIN, "fe80::1", 8096,
        mapOf("token" to "T", "userId" to "U", "sessionId" to "S", "base_path" to "/jellyfin"),
    )
    private val upnp = Device(
        "u", "Rendu", DeviceKind.UPNP_RENDERER, "10.0.0.5", 0,
        mapOf("location" to "http://10.0.0.5:1400/desc.xml", "upnp_alt_didl" to "true"),
    )
    private val all = listOf(kodi, plex, jelly, upnp)

    private fun doc(devices: String, version: String = "1", format: String = "\"kodifin-devices\"") =
        """{"format":$format,"version":$version,"devices":[$devices]}"""

    private fun expectRejected(text: String): InvalidExportException {
        try {
            DeviceExport.decode(text)
        } catch (e: InvalidExportException) {
            return e
        }
        fail("document accepté à tort")
        throw AssertionError()
    }

    @Test
    fun `raison du refus traduisible`() {
        assertEquals(UiText.Res(R.string.sys_export_newer_version, 2), expectRejected(doc("", version = "2")).text)
        assertEquals(UiText.Res(R.string.sys_export_wrong_format), expectRejected(doc("", format = "\"autre\"")).text)
        assertEquals(UiText.Res(R.string.sys_export_unreadable), expectRejected("pas du json").text)
    }

    @Test
    fun `aller-retour sans perte des options exportables`() {
        val decoded = DeviceExport.decode(DeviceExport.encode(all, prettyPrint = true))
        assertEquals(0, decoded.invalid)
        assertEquals(all.map { it.id }, decoded.devices.map { it.id })
        val k = decoded.devices.first()
        assertEquals("Salon", k.name)
        assertEquals(DeviceKind.KODI, k.kind)
        assertEquals("192.168.1.10", k.host)
        assertEquals(8080, k.port)
        assertEquals(kodi.extras - "password" - "password_enc", k.extras)
        assertEquals(upnp.copy(), decoded.devices.last())
        assertEquals("/jellyfin", decoded.devices[2].extras["base_path"])
    }

    @Test
    fun `aucun secret dans l'export`() {
        val text = DeviceExport.encode(all)
        listOf("clair", "enc:xyz", "PLEXTOKEN", "cid", "password", "token", "userId", "sessionId", "clientId").forEach {
            assertFalse("« $it » exporté", it in text)
        }
        val root = Json.parseToJsonElement(text).jsonObject
        assertEquals("kodifin-devices", root["format"]!!.jsonPrimitive.content)
        assertEquals("1", root["version"]!!.jsonPrimitive.content)
        assertEquals(setOf("id", "name", "type", "host", "port", "options"), root["devices"]!!.jsonArray[0].jsonObject.keys)
    }

    @Test
    fun `liste blanche des options`() {
        listOf("scheme", "tls_insecure", "tls_pin", "timeout_s", "base_path", "kodi_ws", "kodi_ws_port", "kodi_eventserver_port",
            "wol_mac", "wol_auto", "wifi_ssid", "upnp_alt_didl", "location", "username").forEach {
            assertTrue(it, DeviceExport.isExportableOption(it))
        }
        listOf("password", "password_enc", "token", "token_enc", "userId", "sessionId", "clientId", "clientIdentifier",
            "wifi_password", "wol_secret", "kodi_ws_token", "type", "inconnu").forEach {
            assertFalse(it, DeviceExport.isExportableOption(it))
        }
    }

    @Test
    fun `l'empreinte du certificat de confiance est exportée et validée à l'import`() {
        val pin = List(32) { "AB" }.joinToString(":")
        val kodi = Device("k", "K", DeviceKind.KODI, "10.0.0.2", 8080,
            mapOf("scheme" to "https", "tls_insecure" to "true", "tls_pin" to pin, "token_enc" to "enc:x"))
        val decoded = DeviceExport.decode(DeviceExport.encode(listOf(kodi))).devices.single()
        assertEquals(kodi.extras - "token_enc", decoded.extras)
        val bad = doc("""{"id":"k","name":"K","type":"KODI","host":"10.0.0.2","port":8080,"options":{"tls_pin":"pas une empreinte"}}""")
        assertEquals(emptyMap<String, String>(), DeviceExport.decode(bad).devices.single().extras)
        // Remplacement par un fichier sans empreinte : l'empreinte locale est conservée (même cible).
        val incoming = DecodedExport(listOf(kodi.copy(name = "Salon", extras = mapOf("scheme" to "https", "tls_insecure" to "true"))), 0)
        val merged = DeviceExport.merge(listOf(kodi), incoming, ImportPolicy.REPLACE_EXISTING).toSave.single()
        assertEquals(pin, merged.extras["tls_pin"])
        assertEquals("enc:x", merged.extras["token_enc"])
    }

    @Test
    fun `un fichier ne peut pas injecter de secret`() {
        val text = doc("""{"id":"k","name":"K","type":"KODI","host":"10.0.0.2","port":8080,
            "options":{"password_enc":"x","password":"y","token":"z","username":"u","clientIdentifier":"c"}}""")
        assertEquals(mapOf("username" to "u"), DeviceExport.decode(text).devices.single().extras)
    }

    @Test
    fun `documents refusés en bloc`() {
        expectRejected("")
        expectRejected("pas du json")
        expectRejected("[]")
        expectRejected("""{"format":"autre","version":1,"devices":[]}""")
        expectRejected(doc("", format = "1"))
        expectRejected(doc("", version = "2"))
        expectRejected(doc("", version = "\"1\""))
        expectRejected(doc("", version = "1.5"))
        expectRejected("""{"format":"kodifin-devices","version":1}""")
        expectRejected("""{"format":"kodifin-devices","version":1,"devices":{}}""")
    }

    @Test
    fun `json hostile - énorme, profond, trop d'appareils`() {
        expectRejected(doc("\"" + "a".repeat(DeviceExport.MAX_CHARS) + "\""))
        expectRejected("[".repeat(100_000) + "]".repeat(100_000))
        expectRejected(doc("""{"id":"a","name":"A","type":"ROKU","host":"h","options":{"x":{"y":[1]}}}"""))
        val many = (0..DeviceExport.MAX_DEVICES).joinToString(",") { """{"id":"$it","name":"n","type":"ROKU","host":"h"}""" }
        expectRejected(doc(many))
        // Les crochets dans une chaîne ne comptent pas dans la profondeur.
        assertFalse(DeviceExport.exceedsDepth("""{"a":"[[[[[[\"[[["}""", 2))
    }

    @Test
    fun `appareils invalides comptés sans bloquer les autres`() {
        val entries = listOf(
            """{"id":"ok","name":"Roku","type":"ROKU","host":"10.0.0.3"}""",
            """{"id":"t","name":"X","type":"TOASTER","host":"10.0.0.3"}""",
            """{"id":"m","type":"ROKU","host":"10.0.0.3"}""",
            """{"id":"h","name":"X","type":"ROKU"}""",
            """{"id":"h2","name":"X","type":"ROKU","host":"http://evil/x"}""",
            """{"id":"h3","name":"X","type":"ROKU","host":"999.1.1.1"}""",
            """{"id":"h4","name":"X","type":"ROKU","host":"a b"}""",
            """{"id":"p","name":"X","type":"ROKU","host":"h","port":70000}""",
            """{"id":"p0","name":"X","type":"ROKU","host":"h","port":0}""",
            """{"id":"ps","name":"X","type":"ROKU","host":"h","port":"80"}""",
            """{"id":"o","name":"X","type":"ROKU","host":"h","options":[]}""",
            """{"id":"l","name":"X","type":"UPNP_RENDERER","host":"h","options":{"location":"file:///etc/passwd"}}""",
            """{"id":"c","name":"A\u0000B","type":"ROKU","host":"h"}""",
            """{"id":"ok","name":"Doublon","type":"ROKU","host":"10.0.0.4"}""",
            "42",
            "null",
        )
        val decoded = DeviceExport.decode(doc(entries.joinToString(",")))
        assertEquals(listOf("ok"), decoded.devices.map { it.id })
        assertEquals(entries.size - 1, decoded.invalid)
    }

    @Test
    fun `valeurs par défaut - identifiant et port`() {
        val d = DeviceExport.decode(doc("""{"name":"Roku","type":"ROKU","host":"roku.local","futur":"ignoré"}""")).devices.single()
        assertEquals(DeviceKind.ROKU.defaultPort, d.port)
        assertEquals(Device.manualId(DeviceKind.ROKU, "roku.local", 8060), d.id)
    }

    @Test
    fun `validation des hôtes`() {
        listOf("10.0.0.1", "fe80::1", "fe80::1%wlan0", "::1", "tv.local", "my-tv", "srv_1.lan", "a.b.c.").forEach {
            assertTrue(it, DeviceExport.isValidHost(it))
        }
        listOf("", "-a", "a..b", "http://a", "a/b", "[::1]", "1.2.3.256", "a b", "x".repeat(254), ":", "a:b").forEach {
            assertFalse(it, DeviceExport.isValidHost(it))
        }
    }

    @Test
    fun `fusion - ajout, ignorés, remplacement`() {
        val existing = listOf(kodi, plex)
        val incoming = DeviceExport.decode(DeviceExport.encode(listOf(kodi.copy(name = "Salon 2"), plex, jelly)))

        val skip = DeviceExport.merge(existing, incoming, ImportPolicy.SKIP_EXISTING)
        assertEquals(ImportReport(added = 1, updated = 0, skipped = 2), skip.report)
        assertEquals(listOf("j"), skip.toSave.map { it.id })

        val replace = DeviceExport.merge(existing, incoming, ImportPolicy.REPLACE_EXISTING)
        // Plex identique (secrets conservés) : compté comme ignoré.
        assertEquals(ImportReport(added = 1, updated = 1, skipped = 1), replace.report)
        val k = replace.toSave.first { it.id == "k" }
        assertEquals("Salon 2", k.name)
        assertEquals("enc:xyz", k.extras["password_enc"])
    }

    @Test
    fun `remplacement vers une autre cible - secrets locaux abandonnés`() {
        val moved = DeviceExport.decode(DeviceExport.encode(listOf(kodi.copy(host = "10.6.6.6"))))
        val plan = DeviceExport.merge(listOf(kodi), moved, ImportPolicy.REPLACE_EXISTING)
        val k = plan.toSave.single()
        assertEquals("10.6.6.6", k.host)
        assertFalse("password_enc" in k.extras)
        assertFalse("password" in k.extras)

        val plain = DeviceExport.decode(DeviceExport.encode(listOf(kodi.copy(extras = kodi.extras - "scheme"))))
        assertFalse("password_enc" in DeviceExport.merge(listOf(kodi), plain, ImportPolicy.REPLACE_EXISTING).toSave.single().extras)
    }

    @Test
    fun `qr - court en clair, long compressé, aller-retour`() {
        val short = DeviceExport.encode(listOf(upnp))
        assertEquals(short, QrPayload.encode(short))
        assertEquals(short, QrPayload.decode("  $short\n"))

        val many = (1..20).map { kodi.copy(id = "k$it", name = "Kodi $it") }
        val long = DeviceExport.encode(many)
        val packed = QrPayload.encode(long)
        assertTrue(packed.startsWith(QrPayload.PREFIX))
        assertTrue(packed.length < long.length)
        assertTrue(QrPayload.fits(packed))
        assertEquals(long, QrPayload.decode(packed))
        assertEquals(20, DeviceExport.decode(QrPayload.decode(packed)).devices.size)
    }

    @Test
    fun `qr - bombe de compression et contenu corrompu refusés`() {
        val bomb = ByteArray(DeviceExport.MAX_CHARS * 2) { ' '.code.toByte() }
        val deflater = Deflater(9, true).apply { setInput(bomb); finish() }
        val out = ByteArrayOutputStream()
        val buf = ByteArray(4096)
        while (!deflater.finished()) out.write(buf, 0, deflater.deflate(buf))
        val payload = QrPayload.PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(out.toByteArray())
        listOf(payload, QrPayload.PREFIX + "!!!", QrPayload.PREFIX + "AAAA", QrPayload.PREFIX + payload.takeLast(20)).forEach {
            try {
                QrPayload.decode(it)
                fail("accepté : ${it.take(30)}")
            } catch (_: InvalidExportException) {
            }
        }
    }

    @Test
    fun `export vide`() {
        val decoded = DeviceExport.decode(DeviceExport.encode(emptyList()))
        assertEquals(DecodedExport(emptyList(), 0), decoded)
        assertTrue(Json.parseToJsonElement(DeviceExport.encode(emptyList())) is JsonObject)
    }
}
