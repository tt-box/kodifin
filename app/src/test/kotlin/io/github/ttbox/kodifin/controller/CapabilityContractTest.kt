package io.github.ttbox.kodifin.controller

import io.github.ttbox.kodifin.data.DeviceRepository
import io.github.ttbox.kodifin.data.FakeSecretStore
import io.github.ttbox.kodifin.data.testStore
import io.github.ttbox.kodifin.model.Capability
import io.github.ttbox.kodifin.model.Device
import io.github.ttbox.kodifin.model.DeviceKind
import io.github.ttbox.kodifin.model.RemoteController
import io.github.ttbox.kodifin.model.RemoteKey
import io.github.ttbox.kodifin.testing.assertHonorsCapabilities
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Contrat des capacités appliqué à chaque contrôleur produit par [ControllerFactory] : ce
 * qu'il annonce ne lève jamais « non pris en charge ». Les appareils pointent vers
 * 127.0.0.1:1, qui refuse aussitôt ; seules les erreurs « non pris en charge » comptent.
 * Fire TV est couvert dans son module ; Chromecast exige le SDK Cast.
 */
class CapabilityContractTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val scope = CoroutineScope(Dispatchers.IO + Job())
    private val secrets = FakeSecretStore()
    private val http = OkHttpClient.Builder()
        .connectTimeout(1, TimeUnit.SECONDS)
        .readTimeout(1, TimeUnit.SECONDS)
        .build()

    private val platform = object : PlatformControllers {
        override fun fireTv(device: Device): RemoteController = error("hors périmètre")
        override fun cast(device: Device): RemoteController = error("hors périmètre")
    }

    @After
    fun tearDown() = scope.cancel()

    private fun factory() =
        ControllerFactory(http, DeviceRepository(testStore(scope, File(tmp.root, "c.preferences_pb")), secrets), secrets, platform)

    private fun device(kind: DeviceKind) = Device(
        id = "contrat-$kind",
        name = "Contrat",
        kind = kind,
        host = "127.0.0.1",
        port = 1,
        extras = when (kind) {
            DeviceKind.UPNP_RENDERER -> mapOf("location" to "http://127.0.0.1:1/description.xml")
            DeviceKind.JELLYFIN, DeviceKind.EMBY -> mapOf("token" to "t", "userId" to "u")
            DeviceKind.PLEX -> mapOf("token" to "t", "clientIdentifier" to "c")
            else -> emptyMap()
        },
    )

    private fun check(kind: DeviceKind) = runBlocking {
        assertHonorsCapabilities(factory().create(device(kind)))
    }

    @Test fun kodi() = check(DeviceKind.KODI)
    @Test fun jellyfin() = check(DeviceKind.JELLYFIN)
    @Test fun emby() = check(DeviceKind.EMBY)
    @Test fun plex() = check(DeviceKind.PLEX)
    @Test fun upnp() = check(DeviceKind.UPNP_RENDERER)
    @Test fun roku() = check(DeviceKind.ROKU)
    @Test fun airplay() = check(DeviceKind.AIRPLAY)

    /** Le contrat détecte bien une capacité annoncée mais absente. */
    @Test(expected = AssertionError::class)
    fun `contract catches an advertised but missing capability`() = runBlocking {
        assertHonorsCapabilities(object : RemoteController {
            override val device = device(DeviceKind.ROKU)
            override val capabilities = setOf(Capability.VOLUME)
            override val supportedKeys = emptySet<RemoteKey>()
            override suspend fun send(key: RemoteKey) = Unit
        })
    }
}
