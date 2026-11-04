package io.github.jmorille.zapette

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.cash.turbine.test
import io.github.jmorille.zapette.connection.NetworkInfo
import io.github.jmorille.zapette.model.Capability
import io.github.jmorille.zapette.model.Device
import io.github.jmorille.zapette.model.DeviceKind
import io.github.jmorille.zapette.model.RemoteController
import io.github.jmorille.zapette.model.RemoteKey
import io.github.jmorille.zapette.ui.remote.RemoteDependencies
import io.github.jmorille.zapette.ui.remote.RemoteViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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

/** Garde réseau (Wi-Fi uniquement, SSID autorisés) appliquée avant toute connexion. */
@OptIn(ExperimentalCoroutinesApi::class)
class RemoteViewModelNetworkTest {
    private val dispatcher = StandardTestDispatcher()

    private class Net(var wifi: Boolean, var ssid: String?) : NetworkInfo {
        override fun isWifiConnected() = wifi
        override fun currentSsid() = ssid
        override fun canReadSsid() = true
    }

    private class Ctrl(override val device: Device) : RemoteController {
        override val capabilities = setOf(Capability.NAVIGATION)
        override val supportedKeys = setOf(RemoteKey.SELECT)
        override suspend fun send(key: RemoteKey) = Unit
    }

    private class Deps(private val scope: CoroutineScope, val device: Device, override val network: NetworkInfo?) : RemoteDependencies {
        var created = 0
        var wakes = 0
        override suspend fun loadDevice(id: String) = device
        override suspend fun createController(device: Device): RemoteController = Ctrl(device).also { created++ }
        override suspend fun saveDevice(device: Device) = Unit
        override suspend fun wake(device: Device) {
            wakes++
        }
        override val backgroundScope: CoroutineScope get() = scope
    }

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.newVm(deps: Deps, store: ViewModelStore) =
        ViewModelProvider.create(
            store,
            viewModelFactory { initializer { RemoteViewModel(deps, deps.device.id, null, SavedStateHandle(), dispatcher) } },
        )[RemoteViewModel::class]

    private val restricted = Device(
        "k", "Salon", DeviceKind.KODI, "10.0.0.2",
        extras = mapOf("wifi_ssids" to "Maison", "wol_mac" to "58:47:CA:7E:0D:48", "wol_auto" to "true"),
    )

    @Test
    fun `wrong network blocks without connecting nor waking`() = runTest(dispatcher) {
        val deps = Deps(this, restricted, Net(wifi = true, ssid = "Voisin"))
        val store = ViewModelStore()
        val vm = newVm(deps, store)
        vm.messages.test {
            assertEquals("Réseau « Voisin » non autorisé pour cet appareil", awaitItem())
        }
        assertEquals(0, deps.created)
        assertEquals(0, deps.wakes)
        assertFalse(vm.state.value.connecting)
        assertEquals("Réseau « Voisin » non autorisé pour cet appareil", vm.state.value.networkBlocked)
        store.clear()
    }

    @Test
    fun `no wifi blocks, then retry on the allowed network connects`() = runTest(dispatcher) {
        val net = Net(wifi = false, ssid = null)
        val deps = Deps(this, restricted, net)
        val store = ViewModelStore()
        val vm = newVm(deps, store)
        vm.messages.test {
            assertEquals("Wi-Fi requis pour cet appareil", awaitItem())
            net.wifi = true
            net.ssid = "Maison"
            vm.retry()
            advanceUntilIdle()
            assertTrue(vm.state.value.connected)
            assertNull(vm.state.value.networkBlocked)
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(1, deps.created)
        store.clear()
    }

    @Test
    fun `devices without restriction ignore the network`() = runTest(dispatcher) {
        val deps = Deps(this, Device("k", "Salon", DeviceKind.KODI, "10.0.0.2"), Net(wifi = false, ssid = null))
        val store = ViewModelStore()
        val vm = newVm(deps, store)
        advanceUntilIdle()
        assertTrue(vm.state.value.connected)
        store.clear()
    }
}
