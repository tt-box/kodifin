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
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Fusion des extras obtenus à la connexion (jeton) sans écraser une modification concurrente. */
class DeviceRepositoryMergeExtrasTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val scope = CoroutineScope(Dispatchers.IO + Job())
    private val secrets = FakeSecretStore()

    @After
    fun tearDown() = scope.cancel()

    private fun repo() = DeviceRepository(testStore(scope, File(tmp.root, "kodifin.preferences_pb")), secrets)

    @Test
    fun `merge keeps a rename made in between and only touches the given extras`() = runBlocking {
        val repo = repo()
        val jelly = Device("j", "Jelly", DeviceKind.JELLYFIN, "10.0.0.4", extras = mapOf("user" to "u0", "wol_mac" to "AA"))
        repo.save(jelly)
        // Modifié depuis le formulaire pendant la connexion.
        repo.save(jelly.copy(name = "Salon", extras = jelly.extras + ("wifi_ssids" to "Maison")))

        repo.mergeExtras(jelly, changed = mapOf("token" to "t1", "user" to "u1"), removed = setOf("wol_mac", "password"))

        val saved = repo.saved.first().single()
        assertEquals("Salon", saved.name)
        assertEquals("Maison", saved.extras["wifi_ssids"])
        assertEquals("u1", saved.extras["user"])
        assertNull(saved.extras["wol_mac"])
        // Jeton chiffré, jamais en clair.
        assertNull(saved.extras["token"])
        assertEquals("t1", secrets.decrypt(checkNotNull(saved.extras["token_enc"])))
    }

    @Test
    fun `merge forgets the transient password and ignores a removed device`() = runBlocking {
        val repo = repo()
        val jelly = Device("j", "Jelly", DeviceKind.JELLYFIN, "10.0.0.4")
        repo.save(jelly, password = "secret")
        assertEquals("secret", repo.get("j")?.extras?.get("password"))

        repo.mergeExtras(checkNotNull(repo.get("j")), changed = mapOf("token" to "t"), removed = setOf("password"))
        assertNull(repo.get("j")?.extras?.get("password"))

        repo.delete("j")
        repo.mergeExtras(jelly, changed = mapOf("token" to "t2"), removed = emptySet())
        assertEquals(emptyList<Device>(), repo.saved.first())
    }

    @Test
    fun `token of the old host is never merged into a device whose target changed meanwhile`() = runBlocking {
        val repo = repo()
        val jelly = Device("j", "Jelly", DeviceKind.JELLYFIN, "10.0.0.4", extras = mapOf("username" to "jo", "token_enc" to "enc:vieux"))
        repo.save(jelly)
        val connected = checkNotNull(repo.get("j"))
        // Hôte modifié depuis le formulaire pendant la connexion : l'ancien jeton a été retiré.
        repo.save(jelly.copy(host = "10.0.0.99", extras = mapOf("username" to "jo")))

        repo.mergeExtras(connected, changed = mapOf("token" to "neuf", "userId" to "u1"), removed = setOf("token_enc"))

        val saved = repo.saved.first().single()
        assertEquals("10.0.0.99", saved.host)
        assertNull("aucun jeton de l'ancien serveur", saved.extras["token_enc"])
        assertNull(saved.extras["token"])
        // Les extras non secrets suivent.
        assertEquals("u1", saved.extras["userId"])
    }

    @Test
    fun `a scheme change also blocks the secrets, and the transient password is not kept`() = runBlocking {
        val repo = repo()
        val jelly = Device("j", "Jelly", DeviceKind.JELLYFIN, "10.0.0.4", extras = mapOf("username" to "jo"))
        repo.save(jelly)
        repo.save(jelly.copy(extras = jelly.extras + ("scheme" to "https")))

        repo.mergeExtras(jelly, changed = mapOf("token" to "neuf", "password" to "pw"), removed = emptySet())

        val saved = repo.saved.first().single()
        assertNull(saved.extras["token_enc"])
        assertNull(repo.get("j")?.extras?.get("password"))
        assertEquals(true, DeviceSecrets.sameTarget(saved, saved.copy(host = "10.0.0.4".uppercase())))
    }
}
