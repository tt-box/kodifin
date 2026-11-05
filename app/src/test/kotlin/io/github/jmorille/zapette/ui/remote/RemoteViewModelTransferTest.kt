package io.github.jmorille.zapette.ui.remote

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.jmorille.zapette.data.LocalShare
import io.github.jmorille.zapette.data.SharedFile
import io.github.jmorille.zapette.model.Capability
import io.github.jmorille.zapette.model.Device
import io.github.jmorille.zapette.model.DeviceKind
import io.github.jmorille.zapette.model.PlaybackState
import io.github.jmorille.zapette.model.RemoteController
import io.github.jmorille.zapette.model.RemoteException
import io.github.jmorille.zapette.model.RemoteKey
import io.github.jmorille.zapette.model.SupportsTransferOut
import io.github.jmorille.zapette.model.TransferableMedia
import io.github.jmorille.zapette.upnp.UpnpRendererController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.TestScope
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

/** Envoi d'un fichier du téléphone et transfert de la lecture vers un autre appareil. */
@OptIn(ExperimentalCoroutinesApi::class)
class RemoteViewModelTransferTest {
    private val dispatcher = StandardTestDispatcher()
    private val source = Device("src", "Salon", DeviceKind.KODI, "10.0.0.2")
    private val tv = Device("tv", "Chromecast", DeviceKind.CHROMECAST, "10.0.0.5")
    private val dlna = Device("dlna", "Bureau", DeviceKind.UPNP_RENDERER, "10.0.0.6", extras = mapOf(UpnpRendererController.EXTRA_MANUFACTURER to "BubbleUPnP"))
    private val roku = Device("r", "Chambre", DeviceKind.ROKU, "10.0.0.7")
    private val jelly = Device("j", "Thor", DeviceKind.JELLYFIN, "10.0.0.3")

    /** Appareil factice : source (transférable) ou cible (lit les adresses, saute). */
    private class Fake(override val device: Device, var media: TransferableMedia?, val seek: Boolean = true) :
        RemoteController, SupportsTransferOut {
        override val capabilities = buildSet {
            add(Capability.PLAYBACK); add(Capability.PLAY_URL)
            if (seek) add(Capability.SEEK)
        }
        override val supportedKeys = setOf(RemoteKey.STOP)
        val log = mutableListOf<String>()
        var failPlay = false
        val playback = MutableStateFlow(PlaybackState(title = "Film", durationMs = 0))
        override suspend fun send(key: RemoteKey) { log += "key:$key" }
        override suspend fun playUrl(url: String, title: String?) {
            if (failPlay) throw RemoteException("Format refusé")
            log += "play:$url|$title"
            playback.value = PlaybackState(title = title, durationMs = 3_600_000)
        }
        override suspend fun seekTo(positionMs: Long) { log += "seek:$positionMs" }
        override fun playbackState(): Flow<PlaybackState> = playback
        override suspend fun disconnect() { log += "disconnect" }
        override suspend fun currentMedia() = media
    }

    private class Share : LocalShare {
        val shared = mutableListOf<String>()
        override var isSharing = false
        override suspend fun share(uri: String): SharedFile {
            shared += uri
            isSharing = true
            return SharedFile("http://10.0.0.9:4000/m/abc/vacances.mp4", "vacances")
        }
        override fun stop() { isSharing = false }
    }

    private inner class Deps(private val scope: CoroutineScope, val media: TransferableMedia?) : RemoteDependencies {
        val created = mutableMapOf<String, Fake>()
        val share = Share()
        override suspend fun loadDevice(id: String) = listOf(source, tv, dlna, roku, jelly).firstOrNull { it.id == id }
        override suspend fun createController(device: Device): RemoteController =
            Fake(device, if (device.id == source.id) media else null, seek = device.kind != DeviceKind.ROKU).also { created[device.id] = it }
        override suspend fun saveDevice(device: Device) {}
        override val backgroundScope: CoroutineScope get() = scope
        override suspend fun allDevices() = listOf(source, tv, dlna, roku, jelly)
        override val localShare: LocalShare get() = share
    }

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private class Env(val vm: RemoteViewModel, val deps: RemoteViewModelTransferTest.Deps, val store: ViewModelStore, val messages: List<String>)

    private fun TestScope.connected(media: TransferableMedia?): Env {
        val deps = Deps(this, media)
        val store = ViewModelStore()
        val vm = ViewModelProvider.create(
            store,
            viewModelFactory { initializer { RemoteViewModel(deps, source.id, null, SavedStateHandle(), dispatcher) } },
        )[RemoteViewModel::class]
        val messages = mutableListOf<String>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.messages.collect { messages += it } }
        advanceUntilIdle()
        return Env(vm, deps, store, messages)
    }

    private fun TestScope.close(env: Env) {
        env.store.clear()
        advanceUntilIdle()
    }

    private val film = TransferableMedia("http://kodi/vfs/film.mkv", "Film", positionMs = 600_000, transcodedUrl = "http://srv/conv.mp4")

    @Test
    fun `targets exclude the source and devices that cannot play urls, bubble first`() {
        val targets = transferTargets(listOf(source, tv, dlna, roku, jelly), source.id)
        assertEquals(listOf("dlna", "r", "tv"), targets.map { it.device.id })
        assertTrue(targets.first().bubble)
        assertFalse(targets[1].bubble || targets[2].bubble)
    }

    @Test
    fun `transfer resumes at the same position and stops the source`() = runTest(dispatcher) {
        val env = connected(film)
        assertTrue(env.vm.state.value.features.transferOut)
        env.vm.openTransfer()
        advanceUntilIdle()
        val transfer = env.vm.state.value.transfer
        assertTrue(transfer.open)
        assertEquals(film, transfer.media)
        assertEquals(3, transfer.targets.size)

        env.vm.transferTo(tv.id, transcode = false, stopHere = true)
        advanceUntilIdle()
        val target = env.deps.created.getValue(tv.id)
        assertEquals(listOf("play:http://kodi/vfs/film.mkv|Film", "seek:600000"), target.log)
        assertTrue("key:STOP" in env.deps.created.getValue(source.id).log)
        assertFalse(env.vm.state.value.transfer.open)
        assertTrue("Lecture transférée vers Chromecast" in env.messages)
        close(env)
    }

    @Test
    fun `transcoded transfer starts at the position, target connection is closed`() = runTest(dispatcher) {
        val env = connected(film)
        env.vm.openTransfer()
        advanceUntilIdle()
        env.vm.transferTo(dlna.id, transcode = true, stopHere = false)
        advanceUntilIdle()
        val target = env.deps.created.getValue(dlna.id)
        assertEquals(listOf("play:http://srv/conv.mp4|Film", "disconnect"), target.log)
        assertFalse("key:STOP" in env.deps.created.getValue(source.id).log)
        close(env)
    }

    @Test
    fun `target without seek starts from the beginning`() = runTest(dispatcher) {
        val env = connected(film)
        env.vm.openTransfer()
        advanceUntilIdle()
        env.vm.transferTo(roku.id, transcode = false, stopHere = false)
        advanceUntilIdle()
        assertEquals(listOf("play:http://kodi/vfs/film.mkv|Film", "disconnect"), env.deps.created.getValue(roku.id).log)
        close(env)
    }

    @Test
    fun `failed transfer keeps the dialog and explains`() = runTest(dispatcher) {
        val env = connected(film)
        env.vm.openTransfer()
        advanceUntilIdle()
        env.vm.transferTo("absent", transcode = false, stopHere = true)
        advanceUntilIdle()
        assertTrue(env.vm.state.value.transfer.open)
        assertFalse(env.vm.state.value.transfer.busy)
        assertTrue("Appareil introuvable" in env.messages)
        env.vm.closeTransfer()
        assertFalse(env.vm.state.value.transfer.open)
        close(env)
    }

    @Test
    fun `nothing transferable closes the dialog with a message`() = runTest(dispatcher) {
        val env = connected(null)
        env.vm.openTransfer()
        advanceUntilIdle()
        assertFalse(env.vm.state.value.transfer.open)
        assertTrue(env.messages.toString(), "Aucun média transférable en cours de lecture" in env.messages)
        close(env)
    }

    @Test
    fun `local file is shared then played, and sharing can be stopped`() = runTest(dispatcher) {
        val env = connected(null)
        env.vm.sendLocalFile("content://docs/42")
        advanceUntilIdle()
        assertEquals(listOf("content://docs/42"), env.deps.share.shared)
        assertTrue(env.vm.state.value.sharing)
        assertEquals(listOf("play:http://10.0.0.9:4000/m/abc/vacances.mp4|vacances"), env.deps.created.getValue(source.id).log)
        assertTrue(env.messages.any { it.startsWith("« vacances » envoyé") })
        env.vm.stopSharing()
        assertFalse(env.vm.state.value.sharing)
        assertFalse(env.deps.share.isSharing)
        close(env)
    }
}
