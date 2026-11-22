package io.github.ttbox.kodifin

import io.github.ttbox.kodifin.airplay.AirPlayController
import io.github.ttbox.kodifin.controller.ControllerFactory
import io.github.ttbox.kodifin.controller.PlatformControllers
import io.github.ttbox.kodifin.data.DeviceRepository
import io.github.ttbox.kodifin.data.DeviceSecrets
import io.github.ttbox.kodifin.data.FakeSecretStore
import io.github.ttbox.kodifin.data.testStore
import io.github.ttbox.kodifin.kodi.KodiController
import io.github.ttbox.kodifin.mediaserver.MediaServerController
import io.github.ttbox.kodifin.model.Device
import io.github.ttbox.kodifin.model.DeviceKind
import io.github.ttbox.kodifin.model.InsecureTls
import io.github.ttbox.kodifin.model.PinnedTrustManager
import io.github.ttbox.kodifin.model.RemoteController
import io.github.ttbox.kodifin.model.RemoteError
import io.github.ttbox.kodifin.model.RemoteException
import io.github.ttbox.kodifin.model.RemoteKey
import io.github.ttbox.kodifin.plex.PlexController
import io.github.ttbox.kodifin.roku.RokuController
import io.github.ttbox.kodifin.upnp.UpnpRendererController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class WithPlexTokenTest {
    private val plex = Device("p", "Plex", DeviceKind.PLEX, "10.0.0.2", extras = mapOf("clientIdentifier" to "c"))

    @Test
    fun `device token wins over the account token`() {
        val own = plex.copy(extras = plex.extras + ("token" to "own"))
        assertSame(own, ControllerFactory.withPlexToken(own, "account"))
    }

    @Test
    fun `account token is added when the device has none`() {
        assertEquals(mapOf("clientIdentifier" to "c", "token" to "account"), ControllerFactory.withPlexToken(plex, "account").extras)
    }

    @Test
    fun `no token at all leaves the device untouched`() {
        assertSame(plex, ControllerFactory.withPlexToken(plex, null))
    }
}

class ControllerFactoryRoutingTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val scope = CoroutineScope(Dispatchers.IO + Job())
    private val secrets = FakeSecretStore()

    private class PlatformFake(override val device: Device) : RemoteController {
        override val capabilities = emptySet<io.github.ttbox.kodifin.model.Capability>()
        override val supportedKeys = emptySet<RemoteKey>()
        override suspend fun send(key: RemoteKey) = Unit
    }

    private val platformCalls = mutableListOf<String>()
    private val platform = object : PlatformControllers {
        override fun fireTv(device: Device): RemoteController = PlatformFake(device).also { platformCalls += "fireTv" }
        override fun cast(device: Device): RemoteController = PlatformFake(device).also { platformCalls += "cast" }
    }

    private fun factory(): Pair<ControllerFactory, DeviceRepository> {
        val repo = DeviceRepository(testStore(scope, File(tmp.root, "f.preferences_pb")), secrets)
        return ControllerFactory(OkHttpClient(), repo, secrets, platform) to repo
    }

    @After
    fun tearDown() = scope.cancel()

    private fun device(kind: DeviceKind, extras: Map<String, String> = emptyMap()) =
        Device("id-$kind", kind.label, kind, "10.0.0.2", extras = extras)

    @Test
    fun `each kind is routed to its controller`() = runBlocking {
        val (factory, _) = factory()
        val expected = mapOf(
            DeviceKind.KODI to KodiController::class,
            DeviceKind.JELLYFIN to MediaServerController::class,
            DeviceKind.EMBY to MediaServerController::class,
            DeviceKind.PLEX to PlexController::class,
            DeviceKind.UPNP_RENDERER to UpnpRendererController::class,
            DeviceKind.ROKU to RokuController::class,
            DeviceKind.AIRPLAY to AirPlayController::class,
            DeviceKind.FIRE_TV to PlatformFake::class,
            DeviceKind.CHROMECAST to PlatformFake::class,
        )
        assertEquals(DeviceKind.entries.toSet(), expected.keys)
        for ((kind, type) in expected) {
            val controller = factory.create(device(kind))
            assertTrue("$kind -> ${controller::class}", type.isInstance(controller))
        }
        assertEquals(listOf("fireTv", "cast"), platformCalls)
    }

    @Test
    fun `plex controller receives the linked account token`() = runBlocking {
        val (factory, repo) = factory()
        repo.setPlexToken("account")
        val controller = factory.create(device(DeviceKind.PLEX))
        assertEquals("account", controller.device.extras["token"])
    }

    @Test
    fun `media server token is decrypted only when creating the controller`() = runBlocking {
        val (factory, _) = factory()
        val stored = DeviceSecrets.forStorage(device(DeviceKind.JELLYFIN, mapOf("token" to "JETON", "userId" to "U")), null, secrets)
        assertTrue(secrets.decrypted.isEmpty())
        val controller = factory.create(stored)
        assertEquals("JETON", controller.device.extras["token"])
        assertEquals(null, controller.device.extras[DeviceSecrets.ENCRYPTED_TOKEN])
        // Les extras renvoyés par le contrôleur contiennent le jeton en clair : le dépôt le rechiffre.
        val persistable = (controller as MediaServerController).persistableExtras()
        assertEquals("JETON", persistable["token"])
        assertEquals("enc:NOTEJ", DeviceSecrets.forStorage(stored.copy(extras = persistable), null, secrets).extras["token_enc"])
    }

    @Test
    fun `plex device token wins over the account token, both stored encrypted`() = runBlocking {
        val (factory, repo) = factory()
        repo.setPlexToken("account")
        val own = DeviceSecrets.forStorage(device(DeviceKind.PLEX, mapOf("token" to "own")), null, secrets)
        assertEquals("own", factory.create(own).device.extras["token"])
    }

    @Test
    fun `a pinned certificate is enforced by the device client`() {
        val (factory, _) = factory()
        val pin = List(32) { "AB" }.joinToString(":")
        val pinned = device(DeviceKind.KODI, mapOf("scheme" to "https", "tls_insecure" to "true", "tls_pin" to pin))
        val tm = factory.httpFor(pinned).x509TrustManager
        assertTrue(tm is PinnedTrustManager)
        assertEquals(pin, (tm as PinnedTrustManager).pin)
        // Sans empreinte (appareil ancien) : comportement inchangé, tout certificat accepté.
        val legacy = device(DeviceKind.KODI, mapOf("scheme" to "https", "tls_insecure" to "true"))
        assertSame(InsecureTls.trustAll, factory.httpFor(legacy).x509TrustManager)
        // Empreinte ignorée hors mode non vérifié.
        val verified = device(DeviceKind.KODI, mapOf("scheme" to "https", "tls_pin" to pin))
        assertTrue(factory.httpFor(verified).x509TrustManager !is PinnedTrustManager)
    }

    @Test
    fun `an unreadable stored password refuses to connect and is purged`() = runBlocking {
        val (factory, repo) = factory()
        val kodi = device(DeviceKind.KODI, mapOf("username" to "kodi"))
        // Clé du Keystore perdue : le chiffré enregistré ne se déchiffre plus.
        val lost = kodi.copy(extras = kodi.extras + (DeviceSecrets.ENCRYPTED_PASSWORD to "illisible"))
        repo.save(lost, null)
        assertEquals("illisible", repo.get(kodi.id)?.extras?.get(DeviceSecrets.ENCRYPTED_PASSWORD))

        val e = assertThrows(RemoteException::class.java) { runBlocking { factory.create(checkNotNull(repo.get(kodi.id))) } }
        assertEquals(RemoteError.StoredSecretUnreadable, e.reason)
        // Purgé : la fiche n'annonce plus de mot de passe enregistré, l'utilisateur le ressaisit.
        assertEquals(mapOf("username" to "kodi"), repo.get(kodi.id)?.extras)
        // Mot de passe saisi (bouton « Tester ») : prioritaire, la connexion se construit.
        assertTrue(factory.create(lost, "pw") is KodiController)
    }

    @Test
    fun `a keystore temporarily unavailable fails the connection but keeps the stored secrets`() = runBlocking {
        val (factory, repo) = factory()
        val kodi = DeviceSecrets.forStorage(device(DeviceKind.KODI, mapOf("username" to "kodi")), "pw", secrets)
        repo.save(kodi, null)
        val sealed = checkNotNull(repo.get(kodi.id)?.extras?.get(DeviceSecrets.ENCRYPTED_PASSWORD))
        secrets.unavailable = true

        val e = assertThrows(RemoteException::class.java) { runBlocking { factory.create(checkNotNull(repo.get(kodi.id))) } }
        assertEquals(RemoteError.SecretStoreUnavailable, e.reason)
        // Rien n'est purgé : le mot de passe enregistré resservira dès que le Keystore répondra.
        assertEquals(sealed, repo.get(kodi.id)?.extras?.get(DeviceSecrets.ENCRYPTED_PASSWORD))
        assertEquals(false, repo.getForEdit(kodi.id)?.secretLost)
        secrets.unavailable = false
        assertTrue(factory.create(checkNotNull(repo.get(kodi.id))) is KodiController)
    }

    @Test
    fun `a keystore temporarily unavailable keeps a media server token too`() = runBlocking {
        val (factory, _) = factory()
        val jelly = device(DeviceKind.JELLYFIN, mapOf("username" to "u", DeviceSecrets.ENCRYPTED_TOKEN to "enc:t"))
        secrets.unavailable = true
        val e = assertThrows(RemoteException::class.java) { runBlocking { factory.create(jelly) } }
        assertEquals(RemoteError.SecretStoreUnavailable, e.reason)
    }

    @Test
    fun `an unreadable media server token also refuses to connect`() = runBlocking {
        val (factory, _) = factory()
        val jelly = device(DeviceKind.JELLYFIN, mapOf("username" to "u", DeviceSecrets.ENCRYPTED_TOKEN to "illisible"))
        val e = assertThrows(RemoteException::class.java) { runBlocking { factory.create(jelly) } }
        assertEquals(RemoteError.StoredSecretUnreadable, e.reason)
    }

    @Test
    fun `kodi web base for playback transfer carries the credentials`() {
        val (factory, _) = factory()
        assertEquals("http://kodi:pw@10.0.0.2:8080/", factory.kodiWebBase("http://10.0.0.2:8080", "kodi", "pw"))
        assertEquals("http://10.0.0.2:8080/", factory.kodiWebBase("http://10.0.0.2:8080", null, "pw"))
        assertEquals(null, factory.kodiWebBase("pas une url", "kodi", "pw"))
    }

    @Test
    fun `kodi password is decrypted only when creating the controller`() = runBlocking {
        val (factory, _) = factory()
        val stored = DeviceSecrets.forStorage(device(DeviceKind.KODI, mapOf("username" to "kodi")), "pw", secrets)
        assertTrue(secrets.decrypted.isEmpty())
        factory.create(stored)
        assertEquals(listOf(stored.extras.getValue(DeviceSecrets.ENCRYPTED_PASSWORD)), secrets.decrypted)
    }
}
