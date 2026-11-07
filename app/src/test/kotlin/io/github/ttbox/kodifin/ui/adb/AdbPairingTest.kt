package io.github.ttbox.kodifin.ui.adb

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.ttbox.kodifin.R
import io.github.ttbox.kodifin.firetv.DadbShell
import io.github.ttbox.kodifin.model.Device
import io.github.ttbox.kodifin.model.DeviceKind
import io.github.ttbox.kodifin.model.RemoteError
import io.github.ttbox.kodifin.model.RemoteException
import io.github.ttbox.kodifin.text.UiText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
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

class AdbPairingLogicTest {
    @Test
    fun `services are grouped per television with both ports`() {
        val list = AdbPairingLogic.candidates(
            listOf(
                AdbService(AdbServiceTypes.CONNECT, "adb-A-1", "10.0.0.5", 41234),
                AdbService(AdbServiceTypes.PAIRING, "adb-A-2", "10.0.0.5", 37000),
                AdbService(AdbServiceTypes.CONNECT, "adb-B-1", "10.0.0.2", 40000),
            ),
        )
        assertEquals(
            listOf(
                AdbCandidate("10.0.0.5", "adb-A-1", 37000, 41234),
                AdbCandidate("10.0.0.2", "adb-B-1", null, 40000),
            ),
            list,
        )
    }

    @Test
    fun `form validation`() {
        assertNull(AdbPairingLogic.validate("10.0.0.5", "37000", "123456", "41234", pairing = true))
        assertEquals(UiText.Res(R.string.adb_error_invalid_ip), AdbPairingLogic.validate(" ", "1", "123456", "2", pairing = true))
        assertEquals(UiText.Res(R.string.adb_error_invalid_pairing_port), AdbPairingLogic.validate("h", "70000", "123456", "2", pairing = true))
        assertEquals(UiText.Res(R.string.adb_error_code_six_digits), AdbPairingLogic.validate("h", "1", "12345", "2", pairing = true))
        assertEquals(UiText.Res(R.string.adb_error_invalid_connect_port), AdbPairingLogic.validate("h", "1", "123456", "", pairing = true))
        // Déjà appairé : ni code ni port d'appairage requis.
        assertNull(AdbPairingLogic.validate("h", "", "", "41234", pairing = false))
    }

    @Test
    fun `saved device uses the connection port and the tls flag`() {
        val device = AdbPairingLogic.device(" 10.0.0.5 ", 41234, "", "guid-1")
        assertEquals(Device.manualId(DeviceKind.FIRE_TV, "10.0.0.5", 41234), device.id)
        assertEquals(DeviceKind.FIRE_TV, device.kind)
        assertEquals(41234, device.port)
        assertEquals("Android TV (10.0.0.5)", device.name)
        assertEquals(mapOf(DadbShell.EXTRA_TLS to "true", DadbShell.EXTRA_GUID to "guid-1"), device.extras)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class AdbPairingViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val store = ViewModelStore()

    private class Deps : AdbPairingDependencies {
        override val services = MutableStateFlow<List<AdbService>>(emptyList())
        val pairs = mutableListOf<Triple<String, Int, String>>()
        val checks = mutableListOf<Pair<String, Int>>()
        val saved = mutableListOf<Device>()
        var pairFailure: Exception? = null
        var checkFailure: Exception? = null

        override fun pair(host: String, port: Int, code: String): String {
            pairs += Triple(host, port, code)
            pairFailure?.let { throw it }
            return "guid"
        }

        override suspend fun checkConnection(host: String, port: Int) {
            checks += host to port
            checkFailure?.let { throw it }
        }

        override suspend fun save(device: Device) {
            saved += device
        }
    }

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() {
        store.clear()
        Dispatchers.resetMain()
    }

    private fun vm(deps: Deps): AdbPairingViewModel =
        ViewModelProvider.create(store, viewModelFactory { initializer { AdbPairingViewModel(deps, dispatcher) } })[AdbPairingViewModel::class.java]

    @Test
    fun `discovered television prefills the ports, then pairing saves the device`() = runTest(dispatcher) {
        val deps = Deps()
        val vm = vm(deps)
        deps.services.value = listOf(
            AdbService(AdbServiceTypes.PAIRING, "p", "10.0.0.5", 37000),
            AdbService(AdbServiceTypes.CONNECT, "c", "10.0.0.5", 41234),
        )
        advanceUntilIdle()
        vm.choose(vm.state.value.candidates.single())
        vm.edit { copy(code = "123456") }
        vm.submit()
        advanceUntilIdle()
        assertEquals(listOf(Triple("10.0.0.5", 37000, "123456")), deps.pairs)
        assertEquals(listOf("10.0.0.5" to 41234), deps.checks)
        assertEquals(AdbPairingStep.Done(deps.saved.single().id), vm.state.value.step)
        assertEquals("true", deps.saved.single().extras[DadbShell.EXTRA_TLS])
    }

    @Test
    fun `failed connection after pairing allows a retry without a new code`() = runTest(dispatcher) {
        val deps = Deps().apply { checkFailure = RemoteException(RemoteError.DeviceRefused("refusé")) }
        val vm = vm(deps)
        vm.edit { copy(host = "10.0.0.5", pairingPort = "37000", code = "123456", connectPort = "5555") }
        vm.submit()
        advanceUntilIdle()
        val failed = vm.state.value
        assertTrue(failed.paired)
        assertEquals(UiText.Res(R.string.adb_error_paired_but_connection_failed, 5555, UiText.Res(R.string.error_device_refused_detail, "refusé")), failed.error)
        assertTrue(deps.saved.isEmpty())

        deps.checkFailure = null
        vm.edit { copy(connectPort = "41234") }
        vm.submit()
        advanceUntilIdle()
        assertEquals(1, deps.pairs.size)
        assertEquals(41234, deps.saved.single().port)
    }

    @Test
    fun `wrong code keeps the form and shows the error`() = runTest(dispatcher) {
        val deps = Deps().apply { pairFailure = RemoteException(RemoteError.CodeRejected) }
        val vm = vm(deps)
        vm.edit { copy(host = "10.0.0.5", pairingPort = "37000", code = "123456", connectPort = "41234") }
        vm.submit()
        advanceUntilIdle()
        assertFalse(vm.state.value.paired)
        assertEquals(UiText.Res(R.string.error_code_rejected), vm.state.value.error)
        assertTrue(deps.checks.isEmpty())
    }

    @Test
    fun `invalid form does not start anything`() = runTest(dispatcher) {
        val deps = Deps()
        val vm = vm(deps)
        vm.edit { copy(host = "10.0.0.5", pairingPort = "37000", code = "12", connectPort = "41234") }
        vm.submit()
        advanceUntilIdle()
        assertEquals(UiText.Res(R.string.adb_error_code_six_digits), vm.state.value.error)
        assertTrue(deps.pairs.isEmpty())
    }
}
