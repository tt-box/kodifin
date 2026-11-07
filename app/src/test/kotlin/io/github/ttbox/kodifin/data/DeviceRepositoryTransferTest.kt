package io.github.ttbox.kodifin.data

import io.github.ttbox.kodifin.model.Device
import io.github.ttbox.kodifin.model.DeviceKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Export / import à travers le dépôt réel (DataStore sur dossier temporaire). */
class DeviceRepositoryTransferTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val scope = CoroutineScope(Dispatchers.IO + Job())
    private val secrets = FakeSecretStore()

    private fun repo(name: String) = DeviceRepository(testStore(scope, File(tmp.root, "$name.preferences_pb")), secrets)

    @After
    fun tearDown() = scope.cancel()

    private val kodi = Device("k", "Kodi", DeviceKind.KODI, "10.0.0.2", extras = mapOf("username" to "kodi", "kodi_ws" to "true"))
    private val plex = Device("p", "Plex", DeviceKind.PLEX, "10.0.0.3", extras = mapOf("token" to "SECRET-TOKEN"))

    @Test
    fun `export puis import sur un autre téléphone, sans secret`() = runBlocking {
        val source = repo("a")
        source.save(kodi, password = "motdepasse")
        source.save(plex)
        val text = source.exportDevices(prettyPrint = true)
        listOf("motdepasse", "password", "SECRET-TOKEN", "token", "enc:").forEach { assertFalse(it, it in text) }

        val target = repo("b")
        assertEquals(ImportReport(added = 2), target.importDevices(text, ImportPolicy.SKIP_EXISTING))
        val imported = target.saved.first()
        assertEquals(listOf("k", "p"), imported.map { it.id })
        assertEquals(mapOf("username" to "kodi", "kodi_ws" to "true"), imported[0].extras)
        assertEquals(emptyMap<String, String>(), imported[1].extras)

        // Deuxième import : tout existe déjà.
        assertEquals(ImportReport(skipped = 2), target.importDevices(text, ImportPolicy.SKIP_EXISTING))
    }

    @Test
    fun `remplacement en gardant le mot de passe chiffré local`() = runBlocking {
        val r = repo("c")
        r.save(kodi, password = "motdepasse")
        val renamed = DeviceExport.encode(listOf(kodi.copy(name = "Salon")))
        assertEquals(ImportReport(updated = 1), r.importDevices(renamed, ImportPolicy.REPLACE_EXISTING))
        val k = r.saved.first().single()
        assertEquals("Salon", k.name)
        assertEquals("motdepasse", DeviceSecrets.reveal(k, secrets).extras["password"])
    }

    @Test
    fun `export partiel et import d'un qr code compressé`() = runBlocking {
        val r = repo("d")
        val many = (1..15).map { kodi.copy(id = "k$it", name = "Kodi $it") }
        many.forEach { r.save(it) }
        assertEquals(1, DeviceExport.decode(r.exportDevices(listOf("k3"))).devices.size)

        val qr = QrPayload.encode(r.exportDevices())
        val target = repo("e")
        assertEquals(ImportReport(added = 15), target.importDevices(qr, ImportPolicy.SKIP_EXISTING))
    }

    @Test
    fun `document refusé - rien n'est écrit`() = runBlocking {
        val r = repo("f")
        r.save(kodi)
        try {
            r.importDevices("""{"format":"kodifin-devices","version":9,"devices":[]}""", ImportPolicy.REPLACE_EXISTING)
            fail("accepté")
        } catch (_: InvalidExportException) {
        }
        assertEquals(listOf(kodi), r.saved.first())
    }
}
