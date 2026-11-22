package io.github.ttbox.kodifin.ui.remote

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.ttbox.kodifin.R
import io.github.ttbox.kodifin.connection.Problem
import io.github.ttbox.kodifin.model.Capability
import io.github.ttbox.kodifin.model.Device
import io.github.ttbox.kodifin.model.DeviceKind
import io.github.ttbox.kodifin.model.MediaEntry
import io.github.ttbox.kodifin.model.MediaItemInfo
import io.github.ttbox.kodifin.model.PersistsCredentials
import io.github.ttbox.kodifin.model.PlaybackState
import io.github.ttbox.kodifin.model.RemoteController
import io.github.ttbox.kodifin.model.RemoteError
import io.github.ttbox.kodifin.model.RemoteException
import io.github.ttbox.kodifin.model.RemoteKey
import io.github.ttbox.kodifin.model.SeriesContext
import io.github.ttbox.kodifin.model.SupportsItemInfo
import io.github.ttbox.kodifin.text.UiText
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.net.ConnectException

/** Cycle de connexion : erreur conservée, libération, réveil, annulation, perte de connexion. */
@OptIn(ExperimentalCoroutinesApi::class)
class RemoteViewModelConnectionTest {
    private val dispatcher = StandardTestDispatcher()
    private val kodi = Device("k", "Salon", DeviceKind.KODI, "10.0.0.2")
    private val sleepy = kodi.copy(extras = mapOf("wol_mac" to "58:47:CA:7E:0D:48", "wol_auto" to "true"))

    private open class Ctrl(override val device: Device, val deps: Deps) : RemoteController, PersistsCredentials {
        override val capabilities get() = deps.capabilities
        override val supportedKeys = setOf(RemoteKey.SELECT)
        var connects = 0
        var disconnects = 0
        override suspend fun connect() {
            connects++
            deps.connectFailure?.let { throw it() }
        }
        override suspend fun send(key: RemoteKey) = Unit
        override fun playbackState(): Flow<PlaybackState> = deps.playback()
        override suspend fun disconnect() {
            disconnects++
        }
        override fun persistableExtras(): Map<String, String> = deps.persistable ?: device.extras
    }

    private class InfoCtrl(device: Device, deps: Deps) : Ctrl(device, deps), SupportsItemInfo {
        override suspend fun itemInfo(entryId: String): MediaItemInfo? = null
        override suspend fun nowPlayingInfo(): MediaItemInfo? = deps.info()
    }

    private class Deps(private val scope: CoroutineScope, var device: Device?) : RemoteDependencies {
        val controllers = mutableListOf<Ctrl>()
        val saves = mutableListOf<Device>()
        var capabilities = setOf(Capability.NAVIGATION)
        var connectFailure: (() -> Exception)? = null
        var createFailure: Exception? = null
        var loadFailure: Exception? = null
        var wakeFailure: Exception? = null
        var playback: () -> Flow<PlaybackState> = { emptyFlow() }
        var persistable: Map<String, String>? = null
        var withInfo = false
        var info: suspend () -> MediaItemInfo? = { null }
        var onCreate: () -> Unit = {}

        override suspend fun loadDevice(id: String): Device? {
            loadFailure?.let { throw it }
            return device?.takeIf { it.id == id }
        }

        override suspend fun createController(device: Device): RemoteController {
            createFailure?.let { throw it }
            onCreate()
            val c = if (withInfo) InfoCtrl(device, this) else Ctrl(device, this)
            return c.also { controllers += it }
        }

        override suspend fun saveDevice(device: Device) {
            saves += device
            this.device = device
        }

        override suspend fun wake(device: Device) {
            wakeFailure?.let { throw it }
        }

        override val backgroundScope: CoroutineScope get() = scope
    }

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private class Env(val vm: RemoteViewModel, val deps: Deps, val store: ViewModelStore, val messages: List<UiText>)

    private fun TestScope.start(deps: Deps): Env {
        val store = ViewModelStore()
        val vm = ViewModelProvider.create(
            store,
            viewModelFactory { initializer { RemoteViewModel(deps, "k", null, SavedStateHandle(), dispatcher) } },
        )[RemoteViewModel::class]
        val messages = mutableListOf<UiText>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.messages.collect { messages += it } }
        return Env(vm, deps, store, messages)
    }

    private fun TestScope.close(env: Env) {
        env.store.clear()
        advanceUntilIdle()
    }

    @Test
    fun `connection failure is kept, controller released, then cleared by a successful retry`() = runTest(dispatcher) {
        val deps = Deps(this, kodi).apply { connectFailure = { ConnectException("ECONNREFUSED") } }
        val env = start(deps)
        advanceUntilIdle()
        val state = env.vm.state.value
        assertFalse(state.connecting)
        assertFalse(state.connected)
        assertEquals(Problem.REFUSED, state.connectionError?.problem)
        assertEquals(UiText.Res(R.string.diag_refused_title), state.connectionError?.title)
        // Pas de message éphémère en double : la carte suffit.
        assertTrue(env.messages.isEmpty())
        // Contrôleur libéré : il ne retente pas seul en tâche de fond.
        assertEquals(1, deps.controllers.single().disconnects)
        env.vm.press(RemoteKey.SELECT)
        runCurrent()
        assertEquals(listOf<UiText>(UiText.Res(R.string.remote_not_connected)), env.messages)

        deps.connectFailure = null
        env.vm.retry()
        advanceUntilIdle()
        assertTrue(env.vm.state.value.connected)
        assertNull(env.vm.state.value.connectionError)
        close(env)
    }

    @Test
    fun `untyped creation failure uses the localized label and still shows the device`() = runTest(dispatcher) {
        val deps = Deps(this, sleepy).apply { createFailure = IllegalStateException("Keystore: binder died") }
        val env = start(deps)
        advanceUntilIdle()
        val state = env.vm.state.value
        assertEquals(UiText.Res(R.string.remote_connection_failed), state.connectionError?.title)
        // Titre et menu Wake-on-LAN disponibles malgré l'échec de création.
        assertEquals("Salon", state.device?.name)
        assertTrue(deps.controllers.isEmpty())
        close(env)
    }

    @Test
    fun `device loading failure and missing device are shown without crashing`() = runTest(dispatcher) {
        val deps = Deps(this, kodi).apply { loadFailure = IOException("disque") }
        val env = start(deps)
        advanceUntilIdle()
        assertFalse(env.vm.state.value.connecting)
        assertEquals(UiText.Res(R.string.remote_connection_failed), env.vm.state.value.connectionError?.title)

        deps.loadFailure = null
        deps.device = null
        env.vm.retry()
        advanceUntilIdle()
        assertEquals(UiText.Res(R.string.remote_device_not_found), env.vm.state.value.connectionError?.title)
        assertFalse(env.vm.state.value.connecting)
        close(env)
    }

    @Test
    fun `wake retries stop at once on a definitive error`() = runTest(dispatcher) {
        val deps = Deps(this, sleepy).apply { connectFailure = { RemoteException(RemoteError.CredentialsRejected) } }
        val env = start(deps)
        advanceUntilIdle()
        assertEquals(1, deps.controllers.single().connects)
        assertEquals(Problem.CREDENTIALS, env.vm.state.value.connectionError?.problem)
        assertEquals(0, env.vm.state.value.connectAttempts)
        close(env)
    }

    @Test
    fun `wake retries expose the attempt and can be cancelled`() = runTest(dispatcher) {
        val deps = Deps(this, sleepy).apply { connectFailure = { ConnectException("ECONNREFUSED") } }
        val env = start(deps)
        runCurrent()
        assertEquals(1, env.vm.state.value.connectAttempt)
        assertEquals(12, env.vm.state.value.connectAttempts)
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(2, env.vm.state.value.connectAttempt)
        val c = deps.controllers.single()
        assertEquals(2, c.connects)

        env.vm.cancelConnect()
        advanceUntilIdle()
        val state = env.vm.state.value
        assertFalse(state.connecting)
        assertFalse(state.connected)
        assertEquals(0, state.connectAttempts)
        assertEquals(2, c.connects)
        assertEquals(1, c.disconnects)
        // Rien à annuler une fois arrêté.
        env.vm.cancelConnect()
        assertEquals(1, c.disconnects)
        close(env)
    }

    @Test
    fun `three consecutive playback failures drop the connection`() = runTest(dispatcher) {
        val deps = Deps(this, kodi).apply {
            capabilities = setOf(Capability.NAVIGATION, Capability.NOW_PLAYING)
            playback = { kotlinx.coroutines.flow.flow { throw IOException() } }
        }
        val env = start(deps)
        runCurrent()
        assertTrue(env.vm.state.value.connected)
        advanceTimeBy(3_000)
        runCurrent()
        assertTrue(env.vm.state.value.connected)
        advanceTimeBy(3_000)
        runCurrent()
        val state = env.vm.state.value
        assertFalse(state.connected)
        assertEquals(UiText.Res(R.string.remote_connection_lost), state.connectionError?.title)
        assertEquals(1, deps.controllers.single().disconnects)
        // La boucle s'est arrêtée : plus rien de planifié.
        advanceUntilIdle()
        close(env)
    }

    @Test
    fun `state errors while polling never drop the connection`() = runTest(dispatcher) {
        val deps = Deps(this, kodi).apply {
            capabilities = setOf(Capability.NAVIGATION, Capability.NOW_PLAYING)
            // Onglet Jellyfin Web fermé : le serveur répond, mais sans client contrôlable.
            playback = { kotlinx.coroutines.flow.flow { throw RemoteException(RemoteError.NoControllableClient) } }
        }
        val env = start(deps)
        runCurrent()
        advanceTimeBy(30_000)
        runCurrent()
        assertTrue(env.vm.state.value.connected)
        assertNull(env.vm.state.value.connectionError)
        assertEquals(0, deps.controllers.single().disconnects)
        // Une erreur d'état entre deux pertes de liaison remet le compteur à zéro.
        var calls = 0
        deps.playback = {
            kotlinx.coroutines.flow.flow {
                calls++
                throw if (calls % 3 == 0) RemoteException(RemoteError.NothingPlaying) else ConnectException()
            }
        }
        advanceTimeBy(30_000)
        runCurrent()
        assertTrue(env.vm.state.value.connected)
        env.store.clear()
        advanceUntilIdle()
    }

    @Test
    fun `only transport and definitive errors count as a lost connection`() {
        assertTrue(countsAsLostConnection(IOException()))
        assertTrue(countsAsLostConnection(RemoteException(RemoteError.Unreachable("h"))))
        assertTrue(countsAsLostConnection(RemoteException(RemoteError.Interrupted())))
        assertTrue(countsAsLostConnection(RemoteException(RemoteError.Timeout)))
        assertTrue(countsAsLostConnection(IllegalStateException("enveloppe", ConnectException())))
        assertTrue(countsAsLostConnection(RemoteException(RemoteError.SessionExpired)))
        assertFalse(countsAsLostConnection(RemoteException(RemoteError.NoControllableClient)))
        assertFalse(countsAsLostConnection(RemoteException(RemoteError.NothingPlaying)))
        assertFalse(countsAsLostConnection(RemoteException(RemoteError.Http(500), IOException())))
        assertFalse(countsAsLostConnection(
            io.github.ttbox.kodifin.model.UnsupportedActionException(io.github.ttbox.kodifin.model.UnsupportedAction.Volume, cause = IOException()),
        ))
        assertFalse(countsAsLostConnection(IllegalStateException("état")))
    }

    @Test
    fun `token is merged into the device as saved now, keeping a rename made meanwhile`() = runTest(dispatcher) {
        val deps = Deps(this, kodi.copy(extras = mapOf("user" to "u"))).apply {
            persistable = mapOf("user" to "u", "token" to "t")
            // Renommé depuis le formulaire pendant la connexion.
            onCreate = { device = device?.copy(name = "Chambre") }
        }
        val env = start(deps)
        advanceUntilIdle()
        val saved = deps.saves.single()
        assertEquals("Chambre", saved.name)
        assertEquals(mapOf("user" to "u", "token" to "t"), saved.extras)
        close(env)
    }

    @Test
    fun `wake failure without message has a real label`() = runTest(dispatcher) {
        val deps = Deps(this, sleepy.copy(extras = mapOf("wol_mac" to "58:47:CA:7E:0D:48")))
        val env = start(deps)
        advanceUntilIdle()
        deps.wakeFailure = IOException()
        env.vm.wake()
        advanceUntilIdle()
        assertEquals(
            listOf<UiText>(UiText.Res(R.string.remote_wake_failed, UiText.Res(R.string.remote_wake_error_generic))),
            env.messages,
        )
        close(env)
    }

    @Test
    fun `item info on a controller without fiches is refused`() = runTest(dispatcher) {
        val env = start(Deps(this, kodi))
        advanceUntilIdle()
        env.vm.openItemInfo(MediaEntry("e1", "Épisode"))
        runCurrent()
        assertEquals(listOf<UiText>(UiText.Res(R.string.remote_not_supported)), env.messages)
        assertNull(env.vm.state.value.itemInfo.entry)
        close(env)
    }

    @Test
    fun `now playing info distinguishes loading, failure and retry`() = runTest(dispatcher) {
        val gate = CompletableDeferred<MediaItemInfo?>()
        val deps = Deps(this, kodi).apply {
            withInfo = true
            capabilities = setOf(Capability.NAVIGATION, Capability.NOW_PLAYING)
            playback = { MutableStateFlow(PlaybackState(title = "Film")) }
            info = { gate.await() }
        }
        val env = start(deps)
        runCurrent()
        assertTrue(env.vm.state.value.nowPlayingInfoLoading)
        assertNull(env.vm.state.value.nowPlayingInfoError)

        gate.completeExceptionally(IOException("refusé"))
        runCurrent()
        assertFalse(env.vm.state.value.nowPlayingInfoLoading)
        assertEquals(UiText.Res(R.string.remote_command_refused), env.vm.state.value.nowPlayingInfoError)
        // Échec sans message éphémère : l'onglet Fiche l'affiche.
        assertTrue(env.messages.isEmpty())

        deps.info = { MediaItemInfo("f", "Film") }
        env.vm.retryNowPlayingInfo()
        runCurrent()
        assertEquals("Film", env.vm.state.value.nowPlayingInfo?.title)
        assertNull(env.vm.state.value.nowPlayingInfoError)
        assertFalse(env.vm.state.value.nowPlayingInfoLoading)
        close(env)
    }

    @Test
    fun `now playing info of the previous media is cleared while the next one loads`() = runTest(dispatcher) {
        val playback = MutableStateFlow(PlaybackState(title = "Film 1"))
        var gate = CompletableDeferred<MediaItemInfo?>(MediaItemInfo("f1", "Film 1"))
        val deps = Deps(this, kodi).apply {
            withInfo = true
            capabilities = setOf(Capability.NAVIGATION, Capability.NOW_PLAYING)
            this.playback = { playback }
            info = { gate.await() }
        }
        val env = start(deps)
        runCurrent()
        assertEquals("Film 1", env.vm.state.value.nowPlayingInfo?.title)

        gate = CompletableDeferred()
        playback.value = PlaybackState(title = "Film 2")
        runCurrent()
        assertTrue(env.vm.state.value.nowPlayingInfoLoading)
        assertNull("plus de fiche du média précédent", env.vm.state.value.nowPlayingInfo)
        gate.complete(MediaItemInfo("f2", "Film 2"))
        runCurrent()
        assertEquals("Film 2", env.vm.state.value.nowPlayingInfo?.title)
        close(env)
    }

    @Test
    fun `episodes of a season stop loading when disconnected`() = runTest(dispatcher) {
        val context = SeriesContext("show", "Série", listOf(MediaEntry("s1", "Saison 1", browsable = true)))
        val state = MutableStateFlow(RemoteUiState(connected = true, series = SeriesState(context = context, loaded = true)))
        val session = RemoteSession(state, Channel(Channel.BUFFERED), backgroundScope, dispatcher) { null }
        val series = SeriesFeature(session)
        state.update { it.copy(connected = false) }
        series.selectSeason("s1")
        assertTrue(state.value.series.episodesLoading)
        // Portée d'arrière-plan : advanceUntilIdle ne l'attend pas, runCurrent l'exécute.
        runCurrent()
        assertFalse(state.value.series.episodesLoading)
        assertNotNull(state.value.series.context)
    }
}
