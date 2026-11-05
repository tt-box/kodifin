package io.github.jmorille.zapette.ui.bluetooth

import io.github.jmorille.zapette.bluetooth.HidAvailability
import io.github.jmorille.zapette.bluetooth.HidHost
import io.github.jmorille.zapette.bluetooth.HidKeyboardPort
import io.github.jmorille.zapette.bluetooth.HidLinkState
import io.github.jmorille.zapette.bluetooth.HidReport
import io.github.jmorille.zapette.bluetooth.HidReports
import io.github.jmorille.zapette.bluetooth.KeyboardLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
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

/** Écran d'essai du clavier Bluetooth, avec une fausse pile HID. */
@OptIn(ExperimentalCoroutinesApi::class)
class BluetoothKeyboardViewModelTest {
    private class FakePort : HidKeyboardPort {
        override val link = MutableStateFlow(HidLinkState())
        var availability = HidAvailability.READY
        val sent = mutableListOf<HidReport>()
        var started = 0
        var stopped = 0
        var connected: String? = null
        override fun availability() = availability
        override fun start() { started++; link.value = link.value.copy(registered = true) }
        override fun stop() { stopped++ }
        override fun bondedHosts() = listOf(HidHost("TV Salon", "AA"))
        override fun connect(address: String) { connected = address; link.value = link.value.copy(connected = HidHost("TV Salon", address)) }
        override fun disconnect() { link.value = link.value.copy(connected = null) }
        override fun send(report: HidReport): Boolean = link.value.connected != null && sent.add(report)
    }

    private val port = FakePort()

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `refresh registers only when bluetooth is ready`() = runTest {
        val vm = BluetoothKeyboardViewModel(port, holdMs = 0)
        port.availability = HidAvailability.NO_PERMISSION
        vm.refresh()
        assertEquals(HidAvailability.NO_PERMISSION, vm.state.value.availability)
        assertTrue(vm.state.value.hosts.isEmpty())
        assertEquals(0, port.started)

        port.availability = HidAvailability.READY
        vm.refresh()
        assertEquals(1, port.started)
        assertEquals(listOf(HidHost("TV Salon", "AA")), vm.state.value.hosts)
        assertTrue(vm.state.value.link.registered)
        assertFalse(vm.state.value.canSend)
    }

    @Test
    fun `keys need a connected tv, then send press and release`() = runTest {
        val vm = BluetoothKeyboardViewModel(port, holdMs = 0)
        vm.refresh()
        vm.press("OK", HidReports.ENTER)
        advanceUntilIdle()
        assertEquals(BluetoothKeyboardViewModel.NOT_SENT, vm.state.value.feedback)
        assertTrue(port.sent.isEmpty())

        vm.connect(vm.state.value.hosts.first())
        assertEquals("AA", port.connected)
        assertTrue(vm.state.value.canSend)
        vm.press("OK", HidReports.ENTER)
        advanceUntilIdle()
        assertEquals(listOf(HidReports.press(HidReports.ENTER), HidReports.release(HidReports.ENTER)), port.sent)
        assertEquals("Envoyé : OK", vm.state.value.feedback)

        vm.disconnect()
        assertFalse(vm.state.value.canSend)
    }

    @Test
    fun `text follows the layout and reports skipped characters`() = runTest {
        val vm = BluetoothKeyboardViewModel(port, holdMs = 0)
        vm.refresh()
        vm.connect(HidHost("TV Salon", "AA"))
        vm.type("")
        assertTrue(port.sent.isEmpty())

        vm.setLayout(KeyboardLayout.QWERTY_US)
        vm.type("aé")
        advanceUntilIdle()
        assertEquals(2, port.sent.size)
        assertEquals("Texte envoyé ; ignorés : é", vm.state.value.feedback)

        port.sent.clear()
        vm.setLayout(KeyboardLayout.AZERTY_FR)
        vm.type("é")
        advanceUntilIdle()
        assertEquals(HidReports.press(HidReports.type("é", KeyboardLayout.AZERTY_FR).keys.single()), port.sent.first())
        assertEquals("Texte envoyé (1 touche)", vm.state.value.feedback)

        vm.disconnect()
        vm.type("abc")
        advanceUntilIdle()
        assertEquals(BluetoothKeyboardViewModel.NOT_SENT, vm.state.value.feedback)
    }
}
