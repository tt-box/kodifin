package io.github.jmorille.zapette.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import io.github.jmorille.zapette.model.Device
import io.github.jmorille.zapette.model.DeviceKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.GeneralSecurityException

/** DataStore réel sur un dossier temporaire, sans Robolectric. */
class DeviceRepositoryTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val scope = CoroutineScope(Dispatchers.IO + Job())
    private val secrets = FakeSecretStore()
    private lateinit var file: File
    private lateinit var store: DataStore<Preferences>
    private lateinit var repo: DeviceRepository

    private val kodi = Device("k", "Kodi", DeviceKind.KODI, "10.0.0.2")
    private val roku = Device("r", "Roku", DeviceKind.ROKU, "10.0.0.3")
    private val jelly = Device("j", "Jelly", DeviceKind.JELLYFIN, "10.0.0.4")

    @Before
    fun setUp() {
        file = File(tmp.root, "zapette.preferences_pb")
        open()
    }

    private fun open() {
        store = testStore(scope, file)
        repo = DeviceRepository(store, secrets)
    }

    @After
    fun tearDown() = scope.cancel()

    private suspend fun raw(): String? = store.data.first()[DeviceRepository.DEVICES]

    private suspend fun writeRaw(json: String) = store.edit { it[DeviceRepository.DEVICES] = json }

    @Test
    fun `save replaces by id and keeps the order`() = runBlocking {
        repo.save(kodi)
        repo.save(roku)
        repo.save(jelly)
        repo.save(roku.copy(name = "Salon"))
        assertEquals(listOf("k", "r", "j"), repo.saved.first().map { it.id })
        assertEquals("Salon", repo.get("r")?.name)
    }

    @Test
    fun `concurrent saves are all kept`() = runBlocking {
        (1..20).map { i -> async(Dispatchers.Default) { repo.save(roku.copy(id = "r$i")) } }.awaitAll()
        assertEquals((1..20).map { "r$it" }.toSet(), repo.saved.first().map { it.id }.toSet())
    }

    @Test
    fun `concurrent installation ids are identical`() = runBlocking {
        val ids = (1..10).map { async(Dispatchers.Default) { repo.installationId() } }.awaitAll()
        assertEquals(1, ids.toSet().size)
        assertEquals(ids.first(), repo.installationId())
    }

    @Test
    fun `corrupted file gives an empty list without crashing`() = runBlocking {
        file.writeBytes(byteArrayOf(-1, -1, -1, -1, -1, -1, 0x0a, 0x7f))
        assertEquals(emptyList<Device>(), repo.saved.first())
        repo.save(kodi) // le fichier remplacé reste utilisable
        assertEquals(listOf(kodi), repo.saved.first())
    }

    @Test
    fun `clearing the plex token removes the key`() = runBlocking {
        repo.setPlexToken("JETON-COMPTE")
        assertTrue(repo.plexLinked.first())
        assertEquals("JETON-COMPTE", repo.sealedPlexToken()?.let(secrets::decrypt))
        repo.setPlexToken(null)
        assertFalse(repo.plexLinked.first())
        assertNull(repo.sealedPlexToken())
        assertFalse(store.data.first().asMap().keys.any { it.name.startsWith("plex_token") })
    }

    @Test
    fun `the plex account token is stored encrypted only`() = runBlocking {
        repo.setPlexToken("JETON-COMPTE")
        assertFalse(file.readText(Charsets.ISO_8859_1).contains("JETON-COMPTE"))
        assertNull(store.data.first()[DeviceRepository.PLEX_TOKEN])
    }

    @Test
    fun `a plain plex account token from an older version is migrated`() = runBlocking {
        store.edit { it[DeviceRepository.PLEX_TOKEN] = "JETON-COMPTE" }
        assertTrue(repo.plexLinked.first())
        val prefs = store.data.first()
        assertNull(prefs[DeviceRepository.PLEX_TOKEN])
        assertEquals("JETON-COMPTE", prefs[DeviceRepository.PLEX_TOKEN_ENCRYPTED]?.let(secrets::decrypt))
        assertFalse(file.readText(Charsets.ISO_8859_1).contains("JETON-COMPTE"))
    }

    @Test
    fun `unreadable root object is set aside instead of being overwritten`() = runBlocking {
        writeRaw("{}")
        assertEquals(emptyList<Device>(), repo.saved.first())
        repo.save(kodi)
        assertEquals(listOf(kodi), repo.saved.first())
        assertEquals("{}", store.data.first()[DeviceRepository.DEVICES_UNREADABLE])
    }

    @Test
    fun `truncated json is set aside and the first backup is kept`() = runBlocking {
        writeRaw("""[{"id":"k","name":"Ko""")
        repo.save(roku)
        writeRaw("[")
        repo.save(jelly)
        assertEquals(listOf(jelly), repo.saved.first())
        assertEquals("""[{"id":"k","name":"Ko""", store.data.first()[DeviceRepository.DEVICES_UNREADABLE])
    }

    @Test
    fun `unreadable entries are kept in place when saving another device`() = runBlocking {
        writeRaw("""[{"id":"x","kind":"FUTURE_KIND"},{"id":"k","name":"Kodi","kind":"KODI","host":"10.0.0.2"}]""")
        repo.save(roku)
        val json = checkNotNull(raw())
        assertTrue(json.startsWith("""[{"id":"x","kind":"FUTURE_KIND"}"""))
        assertEquals(listOf("k", "r"), repo.saved.first().map { it.id })
    }

    @Test
    fun `unreadable entry with the same id as a valid one is not a duplicate`() = runBlocking {
        writeRaw("""[{"id":"k","kind":"FUTURE_KIND"},{"id":"k","name":"Kodi","kind":"KODI","host":"10.0.0.2"}]""")
        assertEquals(kodi, repo.get("k"))
        assertEquals(1, repo.saved.first().size)
        repo.save(kodi.copy(name = "Kodi salon"))
        assertEquals(listOf("Kodi salon"), repo.saved.first().map { it.name })
        assertEquals(1, Regex(""""id":"k"""").findAll(checkNotNull(raw())).count())
    }

    @Test
    fun `valid duplicates collapse into one after save`() = runBlocking {
        val entry = """{"id":"k","name":"Kodi","kind":"KODI","host":"10.0.0.2"}"""
        writeRaw("[$entry,$entry]")
        assertEquals(1, repo.saved.first().size)
        repo.save(kodi.copy(port = 8081))
        assertEquals(1, Regex(""""id":"k"""").findAll(checkNotNull(raw())).count())
        assertEquals(8081, repo.get("k")?.port)
    }

    @Test
    fun `an unreadable entry can be deleted`() = runBlocking {
        writeRaw("""[{"id":"x","kind":"FUTURE_KIND"},{"id":"k","name":"Kodi","kind":"KODI","host":"10.0.0.2"}]""")
        repo.delete("x")
        assertFalse(checkNotNull(raw()).contains("FUTURE_KIND"))
        assertEquals(listOf(kodi), repo.saved.first())
    }

    @Test
    fun `kodi password is stored encrypted only`() = runBlocking {
        repo.save(kodi.copy(extras = mapOf("username" to "kodi")), password = "secret")
        val json = checkNotNull(raw())
        assertFalse(json.contains("secret"))
        assertFalse(json.contains("\"password\""))
        val stored = checkNotNull(repo.get("k"))
        assertNull(stored.extras[DeviceSecrets.PASSWORD])
        assertEquals("secret", DeviceSecrets.reveal(stored, secrets).extras[DeviceSecrets.PASSWORD])
    }

    @Test
    fun `encryption failure writes nothing`() = runBlocking {
        secrets.failEncrypt = true
        try {
            repo.save(kodi, password = "secret")
            fail("le chiffrement aurait dû échouer")
        } catch (_: GeneralSecurityException) {
        }
        assertNull(raw())
    }

    @Test
    fun `media server password stays in memory until the token is saved`() = runBlocking {
        repo.save(jelly.copy(extras = mapOf("username" to "u")), password = "secret")
        assertFalse(checkNotNull(raw()).contains("secret"))
        assertEquals("secret", repo.get("j")?.extras?.get(DeviceSecrets.PASSWORD))

        // Connexion réussie : le ViewModel enregistre le jeton, sans mot de passe.
        repo.save(jelly.copy(extras = mapOf("username" to "u", "token" to "t")))
        assertNull(repo.get("j")?.extras?.get(DeviceSecrets.PASSWORD))
        assertEquals("t", DeviceSecrets.reveal(checkNotNull(repo.get("j")), secrets).extras["token"])
    }

    @Test
    fun `device tokens are encrypted at rest, user id stays readable`() = runBlocking {
        repo.save(jelly.copy(extras = mapOf("username" to "u", "token" to "JETON-JELLYFIN", "userId" to "U42")))
        repo.save(Device("p", "Plex", DeviceKind.PLEX, "10.0.0.5", 32500, mapOf("token" to "JETON-PLEX")))
        val json = checkNotNull(raw())
        listOf("JETON-JELLYFIN", "JETON-PLEX", "\"token\"").forEach { assertFalse(it, json.contains(it)) }
        assertFalse(file.readText(Charsets.ISO_8859_1).contains("JETON"))
        val stored = checkNotNull(repo.get("j"))
        assertEquals("U42", stored.extras["userId"])
        assertNull(stored.extras["token"])
        // Aller-retour : seul reveal (fabrique de contrôleurs) rend le jeton lisible.
        assertEquals("JETON-JELLYFIN", DeviceSecrets.reveal(stored, secrets).extras["token"])
        assertEquals("JETON-PLEX", DeviceSecrets.reveal(checkNotNull(repo.get("p")), secrets).extras["token"])
    }

    @Test
    fun `saving the same token again keeps the stored ciphertext`() = runBlocking {
        repo.save(jelly.copy(extras = mapOf("token" to "JETON")))
        val before = raw()
        // Extras renvoyés par le contrôleur après connexion : jeton en clair, inchangé.
        repo.save(jelly.copy(extras = mapOf("token" to "JETON")))
        assertEquals(before, raw())
        repo.save(jelly.copy(extras = mapOf("token" to "AUTRE")))
        assertEquals("AUTRE", DeviceSecrets.reveal(checkNotNull(repo.get("j")), secrets).extras["token"])
    }

    @Test
    fun `token encryption failure writes nothing`() = runBlocking {
        secrets.failEncrypt = true
        try {
            repo.save(jelly.copy(extras = mapOf("token" to "JETON")))
            fail("le chiffrement aurait dû échouer")
        } catch (_: GeneralSecurityException) {
        }
        assertNull(raw())
    }

    @Test
    fun `plain tokens from older versions are migrated on read`() = runBlocking {
        writeRaw(
            """[{"id":"j","name":"Jelly","kind":"JELLYFIN","host":"10.0.0.4","extras":{"username":"u","token":"JETON-JELLYFIN","userId":"U42","sessionId":"S"}},""" +
                """{"id":"p","name":"Plex","kind":"PLEX","host":"10.0.0.5","extras":{"token":"JETON-PLEX","clientIdentifier":"cid"}}]""",
        )
        val devices = repo.saved.first()
        assertTrue(devices.none { "token" in it.extras })
        assertFalse(checkNotNull(raw()).contains("JETON"))
        val j = devices.first { it.id == "j" }
        assertEquals(mapOf("username" to "u", "userId" to "U42", "sessionId" to "S"), j.extras - DeviceSecrets.ENCRYPTED_TOKEN)
        assertEquals("JETON-JELLYFIN", DeviceSecrets.reveal(j, secrets).extras["token"])
        assertEquals("JETON-PLEX", DeviceSecrets.reveal(devices.first { it.id == "p" }, secrets).extras["token"])
    }

    @Test
    fun `token migration drops the token when encryption is impossible`() = runBlocking {
        secrets.failEncrypt = true
        writeRaw("""[{"id":"j","name":"Jelly","kind":"JELLYFIN","host":"10.0.0.4","extras":{"username":"u","token":"JETON"}}]""")
        assertEquals(mapOf("username" to "u"), repo.saved.first().single().extras)
        assertFalse(checkNotNull(raw()).contains("JETON"))
    }

    @Test
    fun `a password carried by the device itself is never written in clear`() = runBlocking {
        repo.save(jelly.copy(extras = mapOf("username" to "u", "password" to "secret")))
        assertFalse(checkNotNull(raw()).contains("secret"))
        repo.delete("j")
        repo.save(jelly)
        assertNull(repo.get("j")?.extras?.get(DeviceSecrets.PASSWORD))
    }

    @Test
    fun `plain passwords from older versions are migrated on read`() = runBlocking {
        writeRaw(
            """[{"id":"k","name":"Kodi","kind":"KODI","host":"10.0.0.2","extras":{"username":"kodi","password":"secret"}},""" +
                """{"id":"j","name":"Jelly","kind":"JELLYFIN","host":"10.0.0.4","extras":{"username":"u","password":"hunter2"}}]""",
        )
        val devices = repo.saved.first()
        assertTrue(devices.none { DeviceSecrets.PASSWORD in it.extras })
        val json = checkNotNull(raw())
        assertFalse(json.contains("secret"))
        assertFalse(json.contains("hunter2"))
        // Kodi : chiffré ; Jellyfin : gardé en mémoire pour la prochaine connexion.
        assertEquals("secret", DeviceSecrets.reveal(checkNotNull(repo.get("k")), secrets).extras[DeviceSecrets.PASSWORD])
        assertEquals("hunter2", repo.get("j")?.extras?.get(DeviceSecrets.PASSWORD))
    }

    @Test
    fun `migration drops the password when encryption is impossible`() = runBlocking {
        secrets.failEncrypt = true
        writeRaw("""[{"id":"k","name":"Kodi","kind":"KODI","host":"10.0.0.2","extras":{"username":"kodi","password":"secret"}}]""")
        assertEquals(mapOf("username" to "kodi"), repo.saved.first().single().extras)
        assertFalse(checkNotNull(raw()).contains("secret"))
    }
}

class DeviceSecretsTest {
    private val secrets = FakeSecretStore()
    private val kodi = Device("k", "Kodi", DeviceKind.KODI, "10.0.0.2", extras = mapOf("username" to "kodi"))

    @Test
    fun `tokens are sealed, an empty token is removed, reveal restores them`() {
        val jelly = Device("j", "Jelly", DeviceKind.JELLYFIN, "10.0.0.4", extras = mapOf("token" to "abc", "userId" to "U"))
        val stored = DeviceSecrets.forStorage(jelly, null, secrets)
        assertEquals(mapOf("token_enc" to "enc:cba", "userId" to "U"), stored.extras)
        assertEquals(jelly.extras, DeviceSecrets.reveal(stored, secrets).extras)
        assertEquals(mapOf("userId" to "U"), DeviceSecrets.forStorage(stored.copy(extras = stored.extras + ("token" to "")), null, secrets).extras)
        // Chiffré illisible (clé perdue) : retiré, le jeton sera redemandé.
        assertEquals(mapOf("userId" to "U"), DeviceSecrets.reveal(stored.copy(extras = mapOf("token_enc" to "??", "userId" to "U")), secrets).extras)
        assertTrue(DeviceSecrets.hasToken(stored))
        assertFalse(DeviceSecrets.hasPlainSecrets(stored))
    }

    @Test
    fun `kodi password is encrypted, empty password clears it`() {
        val stored = DeviceSecrets.forStorage(kodi, "pw", secrets)
        assertNull(stored.extras[DeviceSecrets.PASSWORD])
        assertEquals("enc:wp", stored.extras[DeviceSecrets.ENCRYPTED_PASSWORD])
        assertNull(DeviceSecrets.forStorage(stored, "", secrets).extras[DeviceSecrets.ENCRYPTED_PASSWORD])
        assertEquals(stored, DeviceSecrets.forStorage(stored, null, secrets))
    }

    @Test
    fun `other kinds never keep a password`() {
        val jelly = Device("j", "J", DeviceKind.JELLYFIN, "h", extras = mapOf("password" to "pw"))
        assertEquals(emptyMap<String, String>(), DeviceSecrets.forStorage(jelly, "pw", secrets).extras)
        assertTrue(DeviceSecrets.isTransient(DeviceKind.EMBY))
        assertFalse(DeviceSecrets.isTransient(DeviceKind.KODI))
    }

    @Test
    fun `reveal decrypts, and drops an unreadable secret`() {
        val stored = DeviceSecrets.forStorage(kodi, "pw", secrets)
        assertEquals(mapOf("username" to "kodi", "password" to "pw"), DeviceSecrets.reveal(stored, secrets).extras)
        val broken = kodi.copy(extras = kodi.extras + (DeviceSecrets.ENCRYPTED_PASSWORD to "illisible"))
        assertEquals(mapOf("username" to "kodi"), DeviceSecrets.reveal(broken, secrets).extras)
        assertEquals(kodi, DeviceSecrets.reveal(kodi, secrets))
    }
}
