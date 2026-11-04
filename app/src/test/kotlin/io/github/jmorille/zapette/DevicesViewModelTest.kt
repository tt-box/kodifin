package io.github.jmorille.zapette

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.cash.turbine.test
import io.github.jmorille.zapette.model.Device
import io.github.jmorille.zapette.model.DeviceKind
import io.github.jmorille.zapette.ui.devices.DevicesDependencies
import io.github.jmorille.zapette.ui.devices.DevicesUiState
import io.github.jmorille.zapette.ui.devices.DevicesViewModel
import io.github.jmorille.zapette.ui.devices.devicesUiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException

class DevicesUiStateTest {
    private val saved = Device("k", "Kodi", DeviceKind.KODI, "10.0.0.2")
    private val tv = Device("r", "Roku", DeviceKind.ROKU, "10.0.0.3")

    @Test
    fun `discovery failing after a first result keeps it and stops scanning`() = runTest {
        val discovered = flow {
            emit(listOf(tv))
            throw IOException("multicast perdu")
        }
        val last = devicesUiState(flowOf(listOf(saved)), discovered).toList().last()
        assertEquals(DevicesUiState(saved = listOf(saved), discovered = listOf(tv), scanning = false), last)
    }

    @Test
    fun `discovery that ends normally stops scanning`() = runTest {
        val last = devicesUiState(flowOf(emptyList()), flowOf(listOf(tv))).toList().last()
        assertEquals(listOf(tv), last.discovered)
        assertFalse(last.scanning)
    }

    @Test
    fun `saving a discovered device removes it from the discovered list`() = runTest {
        val savedFlow = MutableStateFlow(emptyList<Device>())
        val discovered = MutableStateFlow(listOf(saved, tv))
        devicesUiState(savedFlow, discovered).test {
            var state = awaitItem()
            while (state.discovered.size != 2) state = awaitItem()
            assertTrue(state.scanning)
            savedFlow.value = listOf(saved)
            state = awaitItem()
            assertEquals(listOf(saved), state.saved)
            assertEquals(listOf(tv), state.discovered)
            cancelAndIgnoreRemainingEvents()
        }
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class DevicesViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val store = ViewModelStore()
    private val roku = Device("r", "Roku", DeviceKind.ROKU, "10.0.0.3")
    private val jelly = Device("j", "Jelly", DeviceKind.JELLYFIN, "10.0.0.4")

    private class Deps : DevicesDependencies {
        override val saved = MutableStateFlow(emptyList<Device>())
        val found = MutableStateFlow(emptyList<Device>())
        override fun scan(): Flow<List<Device>> = found
        override suspend fun save(device: Device) {
            saved.value = saved.value.filterNot { it.id == device.id } + device
        }
        override suspend fun delete(id: String) {
            saved.value = saved.value.filterNot { it.id == id }
        }
    }

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() {
        store.clear()
        Dispatchers.resetMain()
    }

    private fun newVm(deps: Deps) = ViewModelProvider.create(
        store,
        viewModelFactory { initializer { DevicesViewModel(deps) } },
    )[DevicesViewModel::class]

    @Test
    fun `adopting a device moves it to saved devices`() = runTest(dispatcher) {
        val deps = Deps().apply { found.value = listOf(roku, jelly) }
        val vm = newVm(deps)
        vm.state.test {
            var state = awaitItem()
            while (state.discovered.size != 2) state = awaitItem()
            assertTrue(vm.adopt(roku))
            state = awaitItem()
            assertEquals(listOf(roku), state.saved)
            assertEquals(listOf(jelly), state.discovered)

            // Jellyfin sans identifiants : configuration requise, rien n'est enregistré.
            assertFalse(vm.adopt(jelly))
            vm.delete(roku)
            state = awaitItem()
            assertTrue(state.saved.isEmpty())
            assertEquals(listOf(roku, jelly), state.discovered)
            cancelAndIgnoreRemainingEvents()
        }
        advanceUntilIdle()
        assertTrue(deps.saved.value.isEmpty())
    }
}
