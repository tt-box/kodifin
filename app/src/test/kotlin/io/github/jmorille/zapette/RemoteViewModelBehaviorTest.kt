package io.github.jmorille.zapette

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.cash.turbine.test
import io.github.jmorille.zapette.model.Capability
import io.github.jmorille.zapette.model.Device
import io.github.jmorille.zapette.model.DeviceKind
import io.github.jmorille.zapette.model.PersistsCredentials
import io.github.jmorille.zapette.model.PlaybackState
import io.github.jmorille.zapette.model.RemoteController
import io.github.jmorille.zapette.model.RemoteException
import io.github.jmorille.zapette.model.RemoteKey
import io.github.jmorille.zapette.ui.remote.RemoteDependencies
import io.github.jmorille.zapette.ui.remote.RemoteViewModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

@OptIn(ExperimentalCoroutinesApi::class)
class RemoteViewModelBehaviorTest {
    private val dispatcher = StandardTestDispatcher()
    private val kodi = Device("k", "Kodi", DeviceKind.KODI, "10.0.0.2")

    private class Fake(override val device: Device, override val capabilities: Set<Capability>) :
        RemoteController, PersistsCredentials {
        override val supportedKeys = setOf(RemoteKey.SELECT)
        var connectGate: CompletableDeferred<Unit>? = null
        var sendFailure: Exception? = null
        var persistable: Map<String, String>? = null
        var playback: () -> Flow<PlaybackState> = { emptyFlow() }
        var hangOnDisconnect = false
        val sent = mutableListOf<RemoteKey>()
        val played = mutableListOf<String>()
        var volume: Int? = null
        var disconnects = 0
        var disconnectAbandoned = false
        var subscriptions = 0
        var cancelledSubscriptions = 0

        override suspend fun connect() {
            connectGate?.await()
        }

        override suspend fun send(key: RemoteKey) {
            sendFailure?.let { throw it }
            sent += key
        }

        override suspend fun playUrl(url: String, title: String?) {
            played += url
        }

        override suspend fun setVolume(percent: Int) {
            volume = percent
        }

        override fun playbackState(): Flow<PlaybackState> = flow {
            subscriptions++
            try {
                emitAll(playback())
            } catch (e: kotlinx.coroutines.CancellationException) {
                cancelledSubscriptions++
                throw e
            }
        }

        override suspend fun disconnect() {
            disconnects++
            if (hangOnDisconnect) {
                try {
                    awaitCancellation()
                } finally {
                    disconnectAbandoned = true
                }
            }
        }

        override fun persistableExtras(): Map<String, String> = persistable ?: device.extras
    }

    private class Deps(private val scope: CoroutineScope, var device: Device?) : RemoteDependencies {
        val controllers = mutableListOf<Fake>()
        val saves = mutableListOf<Device>()
        var capabilities = setOf(Capability.NAVIGATION, Capability.PLAY_URL, Capability.VOLUME)
        var configure: (Fake) -> Unit = {}
        var createFailure: Exception? = null
        var saveFailure: Exception? = null

        /** Création suspendue de façon non annulable (rappel d'un SDK, par exemple). */
        var holdCreation = false
        var pendingCreation: Continuation<Unit>? = null

        override suspend fun loadDevice(id: String) = device?.takeIf { it.id == id }

        override suspend fun createController(device: Device): RemoteController {
            createFailure?.let { throw it }
            if (holdCreation) suspendCoroutine { pendingCreation = it }
            return Fake(device, capabilities).also { configure(it); controllers += it }
        }

        override suspend fun saveDevice(device: Device) {
            saveFailure?.let { throw it }
            saves += device
            this.device = device
        }

        override val backgroundScope: CoroutineScope get() = scope
    }

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.newVm(deps: Deps, store: ViewModelStore, url: String? = null) =
        ViewModelProvider.create(
            store,
            viewModelFactory { initializer { RemoteViewModel(deps, "k", url, SavedStateHandle(), dispatcher) } },
        )[RemoteViewModel::class]

    @Test
    fun `missing device reports it and stops connecting`() = runTest(dispatcher) {
        val store = ViewModelStore()
        val vm = newVm(Deps(this, device = null), store)
        vm.messages.test {
            assertEquals("Appareil introuvable", awaitItem())
        }
        assertFalse(vm.state.value.connecting)
        assertFalse(vm.state.value.connected)
        store.clear()
    }

    @Test
    fun `controller creation failure is reported without leaking`() = runTest(dispatcher) {
        val deps = Deps(this, kodi).apply { createFailure = IllegalStateException("Hôte injoignable") }
        val store = ViewModelStore()
        val vm = newVm(deps, store)
        vm.messages.test {
            assertEquals("Hôte injoignable", awaitItem())
            vm.press(RemoteKey.SELECT)
            assertEquals("Appareil non connecté", awaitItem())
        }
        assertFalse(vm.state.value.connecting)
        assertTrue(deps.controllers.isEmpty())
        store.clear()
        advanceUntilIdle()
    }

    @Test
    fun `commands before connection are refused and nothing is sent`() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>()
        val deps = Deps(this, kodi).apply { configure = { it.connectGate = gate } }
        val store = ViewModelStore()
        val vm = newVm(deps, store)
        runCurrent()
        assertTrue(vm.state.value.connecting)
        vm.messages.test {
            vm.press(RemoteKey.SELECT)
            vm.playUrl("http://a/b")
            assertEquals("Appareil non connecté", awaitItem())
            assertEquals("Appareil non connecté", awaitItem())
        }
        assertTrue(deps.controllers.single().sent.isEmpty())
        assertTrue(deps.controllers.single().played.isEmpty())
        gate.complete(Unit)
        store.clear()
        advanceUntilIdle()
    }

    @Test
    fun `failed command is reported`() = runTest(dispatcher) {
        val deps = Deps(this, kodi).apply { configure = { it.sendFailure = RemoteException("Refusé par Kodi") } }
        val store = ViewModelStore()
        val vm = newVm(deps, store)
        advanceUntilIdle()
        vm.messages.test {
            vm.press(RemoteKey.SELECT)
            assertEquals("Refusé par Kodi", awaitItem())
            deps.controllers.single().sendFailure = IOException()
            vm.press(RemoteKey.SELECT)
            assertEquals("Commande refusée", awaitItem())
        }
        assertTrue(vm.state.value.connected)
        store.clear()
        advanceUntilIdle()
    }

    @Test
    fun `volume is clamped and urls are trimmed`() = runTest(dispatcher) {
        val deps = Deps(this, kodi)
        val store = ViewModelStore()
        val vm = newVm(deps, store)
        advanceUntilIdle()
        vm.setVolume(150)
        advanceUntilIdle()
        assertEquals(100, deps.controllers.single().volume)
        vm.setVolume(-5)
        vm.playUrl("  https://a.b/v.mp4 \n")
        advanceUntilIdle()
        assertEquals(0, deps.controllers.single().volume)
        assertEquals(listOf("https://a.b/v.mp4"), deps.controllers.single().played)
        store.clear()
        advanceUntilIdle()
    }

    @Test
    fun `now playing updates the state and resubscribes 3 s after an error`() = runTest(dispatcher) {
        val deps = Deps(this, kodi).apply {
            capabilities = capabilities + Capability.NOW_PLAYING
            configure = { fake ->
                fake.playback = {
                    flow {
                        emit(PlaybackState(title = "Film ${fake.subscriptions}"))
                        throw IOException("connexion perdue")
                    }
                }
            }
        }
        val store = ViewModelStore()
        val vm = newVm(deps, store)
        // Pas d'advanceUntilIdle : la boucle d'observation ne s'arrête jamais.
        runCurrent()
        val fake = deps.controllers.single()
        assertEquals("Film 1", vm.state.value.playback?.title)
        assertEquals(1, fake.subscriptions)
        advanceTimeBy(2_999)
        runCurrent()
        assertEquals(1, fake.subscriptions)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(2, fake.subscriptions)
        assertEquals("Film 2", vm.state.value.playback?.title)
        store.clear()
        runCurrent()
    }

    @Test
    fun `retry cancels the previous playback loop`() = runTest(dispatcher) {
        val deps = Deps(this, kodi).apply {
            capabilities = capabilities + Capability.NOW_PLAYING
            configure = { fake -> fake.playback = { flow { emit(PlaybackState(title = "A")); awaitCancellation() } } }
        }
        val store = ViewModelStore()
        val vm = newVm(deps, store)
        runCurrent()
        vm.retry()
        runCurrent()
        val (old, current) = deps.controllers
        assertEquals(1, old.cancelledSubscriptions)
        assertEquals(1, old.disconnects)
        assertEquals(1, current.subscriptions)
        assertTrue(vm.state.value.connected)
        // Plus aucune réabonnement de l'ancien contrôleur, même bien plus tard.
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(1, old.subscriptions)
        store.clear()
        runCurrent()
    }

    @Test
    fun `clearing during connect disconnects exactly once`() = runTest(dispatcher) {
        val deps = Deps(this, kodi).apply { configure = { it.connectGate = CompletableDeferred() } }
        val store = ViewModelStore()
        newVm(deps, store)
        runCurrent()
        store.clear()
        advanceUntilIdle()
        assertEquals(1, deps.controllers.single().disconnects)
    }

    @Test
    fun `controller created after clearing is still released once`() = runTest(dispatcher) {
        val deps = Deps(this, kodi).apply { holdCreation = true }
        val store = ViewModelStore()
        newVm(deps, store)
        runCurrent()
        store.clear()
        deps.pendingCreation!!.resume(Unit)
        advanceUntilIdle()
        assertEquals(1, deps.controllers.single().disconnects)
    }

    @Test
    fun `hanging disconnect is abandoned after 5 s`() = runTest(dispatcher) {
        val deps = Deps(this, kodi).apply { configure = { it.hangOnDisconnect = true } }
        val store = ViewModelStore()
        newVm(deps, store)
        advanceUntilIdle()
        store.clear()
        runCurrent()
        val fake = deps.controllers.single()
        assertEquals(1, fake.disconnects)
        advanceTimeBy(4_999)
        runCurrent()
        assertFalse(fake.disconnectAbandoned)
        advanceTimeBy(1)
        runCurrent()
        assertTrue(fake.disconnectAbandoned)
    }

    @Test
    fun `save failure keeps the connection`() = runTest(dispatcher) {
        val deps = Deps(this, kodi).apply {
            saveFailure = IOException("disque plein")
            configure = { it.persistable = mapOf("token" to "t") }
        }
        val store = ViewModelStore()
        val vm = newVm(deps, store)
        vm.messages.test {
            advanceUntilIdle()
            expectNoEvents()
        }
        assertTrue(vm.state.value.connected)
        assertTrue(deps.saves.isEmpty())
        vm.press(RemoteKey.SELECT)
        advanceUntilIdle()
        assertEquals(listOf(RemoteKey.SELECT), deps.controllers.single().sent)
        store.clear()
        advanceUntilIdle()
    }

    @Test
    fun `second connection with the same extras saves nothing`() = runTest(dispatcher) {
        val deps = Deps(this, kodi).apply { configure = { it.persistable = mapOf("token" to "t") } }
        val store = ViewModelStore()
        val vm = newVm(deps, store)
        advanceUntilIdle()
        assertEquals(listOf(mapOf("token" to "t")), deps.saves.map { it.extras })
        vm.retry()
        advanceUntilIdle()
        assertEquals(2, deps.controllers.size)
        assertEquals(1, deps.saves.size)
        assertNull(deps.device?.extras?.get("password"))
        store.clear()
        advanceUntilIdle()
    }
}
