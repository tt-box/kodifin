package io.github.jmorille.zapette

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.jmorille.zapette.model.Capability
import io.github.jmorille.zapette.model.Device
import io.github.jmorille.zapette.model.DeviceKind
import io.github.jmorille.zapette.model.PersistsCredentials
import io.github.jmorille.zapette.model.RemoteController
import io.github.jmorille.zapette.model.RemoteKey
import io.github.jmorille.zapette.ui.config.ConfigValidation
import io.github.jmorille.zapette.ui.config.buildDevice
import io.github.jmorille.zapette.ui.devices.devicesUiState
import io.github.jmorille.zapette.ui.remote.RemoteDependencies
import io.github.jmorille.zapette.ui.remote.RemoteSliderState
import io.github.jmorille.zapette.ui.remote.RemoteViewModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SharedLinkTest {
    @Test
    fun `extracts the link and drops trailing punctuation`() {
        assertEquals(
            "https://youtu.be/abc?t=1",
            SharedLink.extract(SharedLink.ACTION_SEND, "Regarde ça : https://youtu.be/abc?t=1."),
        )
        assertEquals("http://x.org/a_(b)", SharedLink.extract(SharedLink.ACTION_SEND, "(voir http://x.org/a_(b))"))
        assertEquals("http://x.org/a", SharedLink.extract(SharedLink.ACTION_SEND, "(voir http://x.org/a)."))
    }

    @Test
    fun `ignores other actions, missing links and relaunch from recents`() {
        assertNull(SharedLink.extract("android.intent.action.MAIN", "https://a.b/c"))
        assertNull(SharedLink.extract(SharedLink.ACTION_SEND, "pas de lien"))
        assertNull(SharedLink.extract(SharedLink.ACTION_SEND, null))
        assertNull(SharedLink.extract(SharedLink.ACTION_SEND, "https://a.b/c", launchedFromHistory = true))
    }
}

class DeviceFormTest {
    @Test
    fun `upnp location parsing handles ipv6, implicit ports and https`() {
        assertEquals("10.0.0.3" to 1400, ConfigValidation.locationEndpoint("http://10.0.0.3:1400/desc.xml"))
        assertEquals("10.0.0.3" to 80, ConfigValidation.locationEndpoint("http://10.0.0.3/desc.xml"))
        assertEquals("tv.local" to 443, ConfigValidation.locationEndpoint("https://tv.local/d.xml"))
        assertEquals("[fe80::1]" to 80, ConfigValidation.locationEndpoint("http://[fe80::1]/d.xml"))
        assertNull(ConfigValidation.locationEndpoint("httpx://10.0.0.3/"))
        assertNull(ConfigValidation.locationEndpoint("http:///desc.xml"))
    }

    @Test
    fun `upnp device built from its description url`() {
        val d = buildDevice(null, DeviceKind.UPNP_RENDERER, " ", "", "", "http://[fe80::1]:1400/d.xml", "", "")
        assertEquals("[fe80::1]", d.host)
        assertEquals(1400, d.port)
        assertEquals("http://[fe80::1]:1400", d.baseUrl)
        assertEquals("UPnP / DLNA [fe80::1]", d.name)
    }

    @Test
    fun `invalid upnp scheme is rejected`() {
        assertTrue(ConfigValidation.error(DeviceKind.UPNP_RENDERER, "", "", "httpfoo") != null)
    }
}

class DevicesStateTest {
    private val saved = Device("k", "Kodi", DeviceKind.KODI, "10.0.0.2")

    @Test
    fun `saved devices are shown before the first discovery result`() = runTest {
        val discovered = flow<List<Device>> { kotlinx.coroutines.awaitCancellation() }
        val first = devicesUiState(flowOf(listOf(saved)), discovered).first()
        assertEquals(listOf(saved), first.saved)
    }

    @Test
    fun `discovery failure does not break the screen`() = runTest {
        val failing = flow<List<Device>> { throw IllegalStateException("NSD indisponible") }
        val states = devicesUiState(flowOf(listOf(saved)), failing).take(1).toList()
        assertEquals(listOf(saved), states.single().saved)
        assertTrue(states.single().discovered.isEmpty())
    }
}

class RemoteSliderStateTest {
    @Test
    fun `remote updates are ignored while dragging and until confirmed`() {
        val s = RemoteSliderState(tolerance = 1f)
        assertEquals(30f, s.displayed(30f))
        s.onDrag(70f)
        assertEquals(70f, s.displayed(35f)) // l'appareil répond pendant le glissement : pas de saut
        assertEquals(70f, s.onDragFinished())
        s.onRemoteValue(35f) // ancienne valeur encore renvoyée par l'appareil
        assertEquals(70f, s.displayed(35f))
        s.onRemoteValue(70f) // confirmée
        assertEquals(36f, s.displayed(36f))
    }

    @Test
    fun `finishing without dragging sends nothing`() {
        val s = RemoteSliderState(tolerance = 1f)
        assertNull(s.onDragFinished())
    }

    @Test
    fun `pending value expires`() {
        val s = RemoteSliderState(tolerance = 1f)
        s.onDrag(10f)
        s.onDragFinished()
        s.expirePending()
        assertEquals(50f, s.displayed(50f))
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class RemoteViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val device = Device("j", "Jelly", DeviceKind.JELLYFIN, "10.0.0.2", extras = mapOf("username" to "u", "password" to "p"))

    private class FakeController(override val device: Device, private val gate: CompletableDeferred<Unit>?) :
        RemoteController, PersistsCredentials {
        var connects = 0
        var disconnects = 0
        val played = mutableListOf<String>()
        var failConnect = false
        override val capabilities = setOf(Capability.PLAY_URL, Capability.NAVIGATION)
        override val supportedKeys = setOf(RemoteKey.SELECT)
        override suspend fun connect() {
            connects++
            gate?.await()
            if (failConnect) error("refusé")
        }
        override suspend fun send(key: RemoteKey) = Unit
        override suspend fun playUrl(url: String, title: String?) { played += url }
        override suspend fun disconnect() { disconnects++ }
        override fun persistableExtras() = mapOf("username" to "u", "token" to "t")
    }

    private inner class Deps(private val scope: CoroutineScope) : RemoteDependencies {
        val controllers = mutableListOf<FakeController>()
        val saves = mutableListOf<Device>()
        var gate: CompletableDeferred<Unit>? = null
        var failNext = false
        override suspend fun loadDevice(id: String) = device.takeIf { it.id == id }
        override suspend fun createController(device: Device) =
            FakeController(device, gate).also { it.failConnect = failNext; controllers += it }
        override suspend fun saveDevice(device: Device) { saves += device }
        override val backgroundScope get() = scope
    }

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.newVm(deps: Deps, store: ViewModelStore, handle: SavedStateHandle, url: String? = "https://a.b/v.mp4") =
        ViewModelProvider.create(
            store,
            viewModelFactory { initializer { RemoteViewModel(deps, device.id, url, handle, dispatcher) } },
        )[RemoteViewModel::class]

    @Test
    fun `retry while connecting does not open a second connection`() = runTest(dispatcher) {
        val deps = Deps(this).apply { gate = CompletableDeferred() }
        val store = ViewModelStore()
        val vm = newVm(deps, store, SavedStateHandle())
        advanceUntilIdle()
        vm.retry()
        vm.retry()
        deps.gate!!.complete(Unit)
        advanceUntilIdle()
        assertEquals(1, deps.controllers.size)
        assertTrue(vm.state.value.connected)
        store.clear()
        advanceUntilIdle()
    }

    @Test
    fun `retry after failure releases the previous controller`() = runTest(dispatcher) {
        val deps = Deps(this).apply { failNext = true }
        val store = ViewModelStore()
        val vm = newVm(deps, store, SavedStateHandle())
        advanceUntilIdle()
        assertFalse(vm.state.value.connected)
        deps.failNext = false
        vm.retry()
        advanceUntilIdle()
        assertEquals(2, deps.controllers.size)
        assertEquals(1, deps.controllers[0].disconnects)
        assertTrue(vm.state.value.connected)
        store.clear()
        advanceUntilIdle()
        assertEquals(1, deps.controllers[1].disconnects) // fermé après onCleared
    }

    @Test
    fun `shared url is played once, even after recreation`() = runTest(dispatcher) {
        val deps = Deps(this)
        val handle = SavedStateHandle()
        val store = ViewModelStore()
        newVm(deps, store, handle)
        advanceUntilIdle()
        assertEquals(listOf("https://a.b/v.mp4"), deps.controllers[0].played)
        store.clear()
        advanceUntilIdle()

        // Recréation (mort du processus) : même route, même état sauvegardé.
        val store2 = ViewModelStore()
        newVm(deps, store2, handle)
        advanceUntilIdle()
        assertTrue(deps.controllers[1].played.isEmpty())
        store2.clear()
        advanceUntilIdle()
    }

    @Test
    fun `token is persisted without password, once`() = runTest(dispatcher) {
        val deps = Deps(this)
        val store = ViewModelStore()
        newVm(deps, store, SavedStateHandle(), url = null)
        advanceUntilIdle()
        assertEquals(1, deps.saves.size)
        assertEquals(mapOf("username" to "u", "token" to "t"), deps.saves.single().extras)
        store.clear()
        advanceUntilIdle()
    }
}
