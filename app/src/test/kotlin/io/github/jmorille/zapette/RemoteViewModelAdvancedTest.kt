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
import io.github.jmorille.zapette.model.MediaDetails
import io.github.jmorille.zapette.model.MediaEntry
import io.github.jmorille.zapette.model.MediaSection
import io.github.jmorille.zapette.model.MediaTrack
import io.github.jmorille.zapette.model.PlaybackState
import io.github.jmorille.zapette.model.PlaybackTarget
import io.github.jmorille.zapette.model.PowerAction
import io.github.jmorille.zapette.model.RemoteController
import io.github.jmorille.zapette.model.RemoteKey
import io.github.jmorille.zapette.model.RepeatMode
import io.github.jmorille.zapette.model.SupportsChapters
import io.github.jmorille.zapette.model.SupportsLibrary
import io.github.jmorille.zapette.model.SupportsLibraryMaintenance
import io.github.jmorille.zapette.model.SupportsNotify
import io.github.jmorille.zapette.model.SupportsPlayMode
import io.github.jmorille.zapette.model.SupportsPower
import io.github.jmorille.zapette.model.SupportsQueue
import io.github.jmorille.zapette.model.SupportsSeekStep
import io.github.jmorille.zapette.model.SupportsSpeed
import io.github.jmorille.zapette.model.SupportsSyncOffset
import io.github.jmorille.zapette.model.SupportsTargets
import io.github.jmorille.zapette.model.SupportsTracks
import io.github.jmorille.zapette.model.SupportsUiCommands
import io.github.jmorille.zapette.model.UiCommand
import io.github.jmorille.zapette.model.UnsupportedActionException
import io.github.jmorille.zapette.ui.remote.RemoteDependencies
import io.github.jmorille.zapette.ui.remote.RemoteFeatures
import io.github.jmorille.zapette.ui.remote.RemoteLayout
import io.github.jmorille.zapette.ui.remote.RemoteLayouts
import io.github.jmorille.zapette.ui.remote.RemotePanel
import io.github.jmorille.zapette.ui.remote.RemoteTab
import io.github.jmorille.zapette.ui.remote.RemoteViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
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

@OptIn(ExperimentalCoroutinesApi::class)
class RemoteViewModelAdvancedTest {
    private val dispatcher = StandardTestDispatcher()
    private val kodi = Device("k", "Kodi", DeviceKind.KODI, "10.0.0.2")

    /** Contrôleur minimal : aucune interface avancée. */
    private open class Basic(override val device: Device) : RemoteController {
        override val capabilities = setOf(Capability.NAVIGATION, Capability.PLAYBACK, Capability.VOLUME, Capability.NOW_PLAYING)
        override var supportedKeys = setOf(RemoteKey.SELECT, RemoteKey.PLAY_PAUSE)
        val sent = mutableListOf<RemoteKey>()
        var volume: Int? = null
        val playback = MutableStateFlow(PlaybackState(title = "Film A", volumePercent = 40))
        override suspend fun send(key: RemoteKey) {
            sent += key
        }
        override suspend fun setVolume(percent: Int) {
            volume = percent
        }
        override fun playbackState(): Flow<PlaybackState> = playback
    }

    /** Contrôleur implémentant toutes les interfaces avancées, journalisant chaque appel. */
    private class Full(device: Device) :
        Basic(device), SupportsTracks, SupportsSeekStep, SupportsSpeed, SupportsSyncOffset, SupportsPlayMode,
        SupportsChapters, SupportsUiCommands, SupportsPower, SupportsNotify, SupportsLibraryMaintenance,
        SupportsLibrary, SupportsQueue, SupportsTargets {
        val calls = mutableListOf<String>()
        var detailsCalls = 0
        var details = MediaDetails(audioTracks = listOf(MediaTrack(0, "Français", "fra")), currentAudio = 0)
        var queueItems = mutableListOf(MediaEntry("1", "Un"), MediaEntry("2", "Deux"), MediaEntry("3", "Trois"))
        var queueCalls = 0
        val browseCalls = mutableListOf<Triple<String, Int, Int>>()
        var sectionsCalls = 0
        var cleanFailure: Exception? = UnsupportedActionException("Nettoyage non pris en charge par Jellyfin")

        override suspend fun mediaDetails(): MediaDetails {
            detailsCalls++
            return details
        }
        override suspend fun setAudioTrack(id: Int) { calls += "audio:$id"; details = details.copy(currentAudio = id) }
        override suspend fun setSubtitle(id: Int?) { calls += "subtitle:$id" }
        override suspend fun nextSubtitle() { calls += "nextSubtitle" }
        override suspend fun nextAudioTrack() { calls += "nextAudio" }
        override suspend fun seekBy(deltaMs: Long) { calls += "seekBy:$deltaMs" }
        override suspend fun faster() { calls += "faster" }
        override suspend fun slower() { calls += "slower" }
        override suspend fun subtitleDelay(later: Boolean) { calls += "subDelay:$later" }
        override suspend fun audioDelay(later: Boolean) { calls += "audioDelay:$later" }
        override suspend fun setRepeat(mode: RepeatMode) { calls += "repeat:$mode" }
        override suspend fun setShuffle(enabled: Boolean) { calls += "shuffle:$enabled" }
        override suspend fun nextChapter() { calls += "nextChapter" }
        override suspend fun previousChapter() { calls += "previousChapter" }
        override val uiCommands = setOf(UiCommand.OSD, UiCommand.FULLSCREEN)
        override suspend fun uiCommand(command: UiCommand) { calls += "ui:$command" }
        override suspend fun availablePowerActions() = setOf(PowerAction.SHUTDOWN, PowerAction.REBOOT)
        override suspend fun power(action: PowerAction) { calls += "power:$action" }
        override suspend fun showMessage(title: String, text: String) { calls += "message:$title/$text" }
        override suspend fun scanLibrary() { calls += "scan" }
        override suspend fun cleanLibrary() { cleanFailure?.let { throw it }; calls += "clean" }

        override suspend fun sections(): List<MediaSection> {
            sectionsCalls++
            return listOf(MediaSection("films", "Films"), MediaSection("series", "Séries"))
        }
        override suspend fun browse(parentId: String, offset: Int, limit: Int): List<MediaEntry> {
            browseCalls += Triple(parentId, offset, limit)
            return when (parentId) {
                // 150 films : deux pages de 100 puis 50.
                "films" -> (offset until minOf(offset + limit, 150)).map { MediaEntry("movie:$it", "Film $it") }
                "series" -> listOf(MediaEntry("show:1", "Série 1", browsable = true))
                else -> listOf(MediaEntry("ep:1", "Épisode 1"))
            }
        }
        override suspend fun play(entryId: String) { calls += "play:$entryId" }
        override suspend fun enqueue(entryId: String, next: Boolean) { calls += "enqueue:$entryId:$next" }

        override suspend fun queue(): List<MediaEntry> {
            queueCalls++
            return queueItems.toList()
        }
        override suspend fun queuePosition(): Int? = 1
        override suspend fun playQueueItem(index: Int) { calls += "queuePlay:$index" }
        override suspend fun removeQueueItem(index: Int) { queueItems.removeAt(index) }
        override suspend fun clearQueue() { queueItems.clear() }

        override suspend fun targets() = listOf(PlaybackTarget("s1", "Salon"), PlaybackTarget("s2", "Chambre", "Jellyfin Web"))
        override var currentTargetId: String? = "s1"
        override suspend fun selectTarget(id: String) { currentTargetId = id }
    }

    private class Deps(private val scope: CoroutineScope, val full: Boolean) : RemoteDependencies {
        val controllers = mutableListOf<Basic>()
        override suspend fun loadDevice(id: String) = Device("k", "Kodi", DeviceKind.KODI, "10.0.0.2")
        override suspend fun createController(device: Device): RemoteController =
            (if (full) Full(device) else Basic(device)).also { controllers += it }
        override suspend fun saveDevice(device: Device) {}
        override val backgroundScope: CoroutineScope get() = scope
    }

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.newVm(deps: Deps, store: ViewModelStore) =
        ViewModelProvider.create(
            store,
            viewModelFactory { initializer { RemoteViewModel(deps, kodi.id, null, SavedStateHandle(), dispatcher) } },
        )[RemoteViewModel::class]

    private fun TestScope.connected(full: Boolean = true): Triple<RemoteViewModel, Deps, ViewModelStore> {
        val deps = Deps(this, full)
        val store = ViewModelStore()
        val vm = newVm(deps, store)
        advanceUntilIdle()
        return Triple(vm, deps, store)
    }

    private fun Deps.fake() = controllers.single() as Full

    @Test
    fun `features mirror implemented interfaces and power actions are loaded`() = runTest(dispatcher) {
        val (vm, deps, store) = connected()
        val f = vm.state.value.features
        assertTrue(f.tracks && f.seekStep && f.speed && f.syncOffset && f.playMode && f.chapters)
        assertTrue(f.power && f.notify && f.libraryMaintenance && f.library && f.queue && f.targets)
        assertEquals(setOf(UiCommand.OSD, UiCommand.FULLSCREEN), f.uiCommands)
        assertEquals(setOf(PowerAction.SHUTDOWN, PowerAction.REBOOT), f.powerActions)
        // Cibles chargées à la connexion (sélecteur de session).
        assertEquals("Salon", vm.state.value.targets.current?.name)
        assertEquals(2, vm.state.value.targets.items.size)
        assertEquals(0, deps.fake().detailsCalls)
        store.clear()
        advanceUntilIdle()
    }

    @Test
    fun `basic controller exposes no advanced feature and refuses advanced actions`() = runTest(dispatcher) {
        val (vm, deps, store) = connected(full = false)
        assertEquals(RemoteFeatures(), vm.state.value.features)
        vm.setVisiblePanels(setOf(RemotePanel.DETAILS, RemotePanel.QUEUE, RemotePanel.LIBRARY))
        advanceUntilIdle()
        assertNull(vm.state.value.mediaDetails)
        vm.messages.test {
            vm.nextChapter()
            assertEquals("Action non prise en charge par cet appareil", awaitItem())
        }
        assertTrue(deps.controllers.single().sent.isEmpty())
        store.clear()
        advanceUntilIdle()
    }

    @Test
    fun `details load when the panel opens and on media change only`() = runTest(dispatcher) {
        val (vm, deps, store) = connected()
        val fake = deps.fake()
        vm.setVisiblePanels(setOf(RemotePanel.DETAILS))
        advanceUntilIdle()
        assertEquals(1, fake.detailsCalls)
        assertEquals(0, vm.state.value.mediaDetails?.currentAudio)
        // Même média (position qui avance) : pas de nouvelle requête.
        fake.playback.value = fake.playback.value.copy(positionMs = 5_000)
        advanceUntilIdle()
        assertEquals(1, fake.detailsCalls)
        // Nouveau média : rechargement.
        fake.playback.value = PlaybackState(title = "Film B")
        advanceUntilIdle()
        assertEquals(2, fake.detailsCalls)
        // Volet masqué : plus aucune requête au changement de média.
        vm.setVisiblePanels(emptySet())
        fake.playback.value = PlaybackState(title = "Film C")
        advanceUntilIdle()
        assertEquals(2, fake.detailsCalls)
        store.clear()
        advanceUntilIdle()
    }

    @Test
    fun `track and mode actions reach the controller and refresh details`() = runTest(dispatcher) {
        val (vm, deps, store) = connected()
        val fake = deps.fake()
        vm.setAudioTrack(3)
        advanceUntilIdle()
        assertEquals(3, vm.state.value.mediaDetails?.currentAudio)
        vm.setSubtitle(null)
        vm.nextSubtitle()
        vm.nextAudioTrack()
        vm.seekBy(-30_000)
        vm.faster()
        vm.slower()
        vm.subtitleDelay(true)
        vm.audioDelay(false)
        vm.setRepeat(RepeatMode.ALL)
        vm.setShuffle(true)
        vm.nextChapter()
        vm.previousChapter()
        vm.uiCommand(UiCommand.OSD)
        advanceUntilIdle()
        assertEquals(
            listOf(
                "audio:3", "subtitle:null", "nextSubtitle", "nextAudio", "seekBy:-30000", "faster", "slower",
                "subDelay:true", "audioDelay:false", "repeat:ALL", "shuffle:true", "nextChapter", "previousChapter", "ui:OSD",
            ),
            fake.calls,
        )
        store.clear()
        advanceUntilIdle()
    }

    @Test
    fun `power notify and maintenance report a short message`() = runTest(dispatcher) {
        val (vm, deps, store) = connected()
        vm.messages.test {
            vm.power(PowerAction.SHUTDOWN)
            assertEquals("Éteindre : demande envoyée", awaitItem())
            vm.showMessage(" Titre ", " Bonjour ")
            assertEquals("Message affiché sur l'appareil", awaitItem())
            vm.scanLibrary()
            assertEquals("Mise à jour de la bibliothèque lancée", awaitItem())
            vm.cleanLibrary()
            assertEquals("Nettoyage non pris en charge par Jellyfin", awaitItem())
            deps.fake().cleanFailure = UnsupportedActionException("")
            vm.cleanLibrary()
            assertEquals("Action non prise en charge par cet appareil", awaitItem())
        }
        assertEquals(listOf("power:SHUTDOWN", "message:Titre/Bonjour", "scan"), deps.fake().calls)
        store.clear()
        advanceUntilIdle()
    }

    @Test
    fun `library pages folders and navigates back`() = runTest(dispatcher) {
        val (vm, deps, store) = connected()
        val fake = deps.fake()
        vm.setVisiblePanels(setOf(RemotePanel.LIBRARY))
        advanceUntilIdle()
        assertEquals(listOf("Films", "Séries"), vm.state.value.library.sections.map { it.title })
        // Réouverture du volet : les rubriques ne sont pas redemandées.
        vm.setVisiblePanels(emptySet())
        vm.setVisiblePanels(setOf(RemotePanel.LIBRARY))
        advanceUntilIdle()
        assertEquals(1, fake.sectionsCalls)

        vm.openSection(vm.state.value.library.sections[0])
        advanceUntilIdle()
        assertEquals(100, vm.state.value.library.current?.entries?.size)
        assertFalse(vm.state.value.library.current!!.endReached)
        vm.loadMoreLibrary()
        advanceUntilIdle()
        assertEquals(150, vm.state.value.library.current?.entries?.size)
        assertTrue(vm.state.value.library.current!!.endReached)
        vm.loadMoreLibrary()
        advanceUntilIdle()
        assertEquals(listOf(Triple("films", 0, 100), Triple("films", 100, 100)), fake.browseCalls)

        assertTrue(vm.libraryBack())
        vm.openSection(MediaSection("series", "Séries"))
        advanceUntilIdle()
        val show = vm.state.value.library.current!!.entries.single()
        vm.openEntry(show)
        advanceUntilIdle()
        assertEquals(listOf("Séries", "Série 1"), vm.state.value.library.path.map { it.title })
        val episode = vm.state.value.library.current!!.entries.single()
        vm.openEntry(episode)
        vm.enqueue(episode, next = true)
        advanceUntilIdle()
        assertEquals(listOf("play:ep:1", "enqueue:ep:1:true"), fake.calls)
        assertTrue(vm.libraryBack())
        assertTrue(vm.libraryBack())
        assertFalse(vm.libraryBack())
        store.clear()
        advanceUntilIdle()
    }

    @Test
    fun `queue loads when shown and refreshes after changes`() = runTest(dispatcher) {
        val (vm, deps, store) = connected()
        val fake = deps.fake()
        assertEquals(0, fake.queueCalls)
        vm.setVisiblePanels(setOf(RemotePanel.QUEUE))
        advanceUntilIdle()
        assertEquals(3, vm.state.value.queue.items.size)
        assertEquals(1, vm.state.value.queue.position)
        vm.removeQueueItem(0)
        advanceUntilIdle()
        assertEquals(listOf("Deux", "Trois"), vm.state.value.queue.items.map { it.title })
        vm.playQueueItem(1)
        vm.clearQueue()
        advanceUntilIdle()
        assertTrue(vm.state.value.queue.items.isEmpty())
        assertTrue(vm.state.value.queue.loaded)
        assertEquals(listOf("queuePlay:1"), fake.calls)
        store.clear()
        advanceUntilIdle()
    }

    @Test
    fun `selecting a target updates the current session`() = runTest(dispatcher) {
        val (vm, _, store) = connected()
        vm.selectTarget("s2")
        advanceUntilIdle()
        assertEquals("Chambre", vm.state.value.targets.current?.name)
        store.clear()
        advanceUntilIdle()
    }

    @Test
    fun `volume keys drive the device`() = runTest(dispatcher) {
        val (vm, deps, store) = connected(full = false)
        val fake = deps.controllers.single()
        // Pas de touche volume : réglage absolu par pas de 5 à partir du volume publié.
        assertTrue(vm.onVolumeKey(up = true))
        advanceUntilIdle()
        assertEquals(45, fake.volume)
        assertTrue(vm.onVolumeKey(up = false))
        advanceUntilIdle()
        assertEquals(40, fake.volume)
        assertTrue(fake.sent.isEmpty())
        store.clear()
        advanceUntilIdle()
    }

    @Test
    fun `volume keys send device keys when supported and are ignored when disconnected`() = runTest(dispatcher) {
        val deps = object : RemoteDependencies {
            val controllers = mutableListOf<Basic>()
            override suspend fun loadDevice(id: String) = kodi
            override suspend fun createController(device: Device): RemoteController =
                Basic(device).apply { supportedKeys = setOf(RemoteKey.VOLUME_UP, RemoteKey.VOLUME_DOWN) }.also { controllers += it }
            override suspend fun saveDevice(device: Device) {}
            override val backgroundScope: CoroutineScope get() = this@runTest
        }
        val store = ViewModelStore()
        val vm = ViewModelProvider.create(
            store,
            viewModelFactory { initializer { RemoteViewModel(deps, kodi.id, null, SavedStateHandle(), dispatcher) } },
        )[RemoteViewModel::class]
        assertFalse(vm.onVolumeKey(up = true))
        advanceUntilIdle()
        assertTrue(vm.onVolumeKey(up = true))
        assertTrue(vm.onVolumeKey(up = false))
        advanceUntilIdle()
        assertEquals(listOf(RemoteKey.VOLUME_UP, RemoteKey.VOLUME_DOWN), deps.controllers.single().sent)
        assertNull(deps.controllers.single().volume)
        store.clear()
        advanceUntilIdle()
    }

    // --- Disposition (logique pure) ---------------------------------------------------------

    @Test
    fun `layout follows window size classes`() {
        assertEquals(RemoteLayout.PHONE, RemoteLayouts.forSize(411f, 891f))
        // Téléphone couché : trop peu de hauteur pour les volets.
        assertEquals(RemoteLayout.PHONE, RemoteLayouts.forSize(891f, 411f))
        assertEquals(RemoteLayout.TABLET_PORTRAIT, RemoteLayouts.forSize(800f, 1280f))
        assertEquals(RemoteLayout.TABLET_PORTRAIT, RemoteLayouts.forSize(876f, 1300f))
        assertEquals(RemoteLayout.TABLET_LANDSCAPE, RemoteLayouts.forSize(1280f, 800f))
    }

    @Test
    fun `tabs only list pages the device can fill`() {
        val none = RemoteFeatures()
        assertEquals(listOf(RemoteTab.REMOTE), RemoteLayouts.phoneTabs(setOf(Capability.NAVIGATION), none))
        val full = RemoteFeatures(tracks = true, queue = true, library = true)
        assertEquals(
            listOf(RemoteTab.REMOTE, RemoteTab.NOW_PLAYING, RemoteTab.QUEUE, RemoteTab.LIBRARY),
            RemoteLayouts.phoneTabs(emptySet(), full),
        )
        assertEquals(listOf(RemoteTab.NOW_PLAYING, RemoteTab.QUEUE, RemoteTab.LIBRARY), RemoteLayouts.sideTabs(RemoteLayout.TABLET_PORTRAIT, full))
        assertEquals(listOf(RemoteTab.QUEUE, RemoteTab.LIBRARY), RemoteLayouts.sideTabs(RemoteLayout.TABLET_LANDSCAPE, full))
        assertEquals(
            setOf(RemotePanel.DETAILS, RemotePanel.QUEUE),
            RemoteLayouts.visiblePanels(RemoteLayout.TABLET_LANDSCAPE, RemoteTab.QUEUE, full),
        )
        assertEquals(emptySet<RemotePanel>(), RemoteLayouts.visiblePanels(RemoteLayout.PHONE, RemoteTab.REMOTE, full))
    }
}
