package io.github.jmorille.zapette.ui.remote

import androidx.compose.ui.graphics.ImageBitmap
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.jmorille.zapette.model.Capability
import io.github.jmorille.zapette.model.Device
import io.github.jmorille.zapette.model.DeviceKind
import io.github.jmorille.zapette.model.MediaEntry
import io.github.jmorille.zapette.model.MediaKind
import io.github.jmorille.zapette.model.MediaSection
import io.github.jmorille.zapette.model.PlaybackState
import io.github.jmorille.zapette.model.RemoteController
import io.github.jmorille.zapette.model.RemoteKey
import io.github.jmorille.zapette.model.SeriesContext
import io.github.jmorille.zapette.model.SupportsImages
import io.github.jmorille.zapette.model.SupportsLibrary
import io.github.jmorille.zapette.model.SupportsQueue
import io.github.jmorille.zapette.model.SupportsSearch
import io.github.jmorille.zapette.model.SupportsSeries
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
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

/** Série en cours, recherche et images, avec un faux contrôleur Jellyfin/Kodi. */
@OptIn(ExperimentalCoroutinesApi::class)
class RemoteViewModelMediaTest {
    private val dispatcher = StandardTestDispatcher()
    private val device = Device("j", "Thor", DeviceKind.KODI, "10.0.0.3")

    private class Fake(override val device: Device) :
        RemoteController, SupportsLibrary, SupportsQueue, SupportsSeries, SupportsSearch, SupportsImages {
        override val capabilities = setOf(Capability.NAVIGATION, Capability.PLAYBACK, Capability.NOW_PLAYING)
        override val supportedKeys = setOf(RemoteKey.SELECT)
        override suspend fun send(key: RemoteKey) {}
        val playback = MutableStateFlow(PlaybackState(title = "Pilote", seriesTitle = "Série X", season = 1, episode = 1))
        override fun playbackState(): Flow<PlaybackState> = playback

        var series: SeriesContext? = SeriesContext(
            seriesId = "show",
            title = "Série X",
            seasons = listOf(MediaEntry("s1", "Saison 1", browsable = true), MediaEntry("s2", "Saison 2", browsable = true)),
            currentSeasonId = "s2",
            currentEpisodeId = "e2-3",
        )
        var seriesCalls = 0
        override suspend fun currentSeries(): SeriesContext? {
            seriesCalls++
            return series
        }

        val browseCalls = mutableListOf<String>()
        var browseFailure: Exception? = null
        override suspend fun sections() = listOf(MediaSection("films", "Films"))
        override suspend fun browse(parentId: String, offset: Int, limit: Int): List<MediaEntry> {
            browseCalls += "$parentId@$offset"
            browseFailure?.let { throw it }
            val season = parentId.removePrefix("s").toIntOrNull() ?: 0
            return (1..4).map { MediaEntry("e$season-$it", "Épisode $it", kind = MediaKind.EPISODE, season = season, episode = it) }
        }
        val played = mutableListOf<String>()
        override suspend fun play(entryId: String) { played += entryId }
        override suspend fun enqueue(entryId: String, next: Boolean) { played += "enqueue:$entryId:$next" }

        override suspend fun queue() = listOf(MediaEntry("q1", "Un"))
        override suspend fun queuePosition(): Int? = 0
        override suspend fun playQueueItem(index: Int) {}
        override suspend fun removeQueueItem(index: Int) {}
        override suspend fun clearQueue() {}

        override val searchKinds = setOf(MediaKind.MOVIE, MediaKind.SERIES, MediaKind.EPISODE)
        val searches = mutableListOf<Pair<String, Set<MediaKind>>>()
        var searchFailure: Exception? = null
        override suspend fun search(query: String, kinds: Set<MediaKind>, limit: Int): List<MediaEntry> {
            searches += query to kinds
            searchFailure?.let { throw it }
            return listOf(
                MediaEntry("m1", "$query le film", kind = MediaKind.MOVIE, year = 2008, imageKey = "img-m1"),
                MediaEntry("show", "$query la série", kind = MediaKind.SERIES, browsable = true, imageKey = "img-show"),
            )
        }

        val imageCalls = mutableListOf<Pair<String, Int>>()
        override suspend fun image(imageKey: String, maxWidth: Int): ByteArray {
            imageCalls += imageKey to maxWidth
            return byteArrayOf(1, 2, 3)
        }
    }

    private class Deps(private val scope: CoroutineScope) : RemoteDependencies {
        var fake: Fake? = null
        override suspend fun loadDevice(id: String) = Device("j", "Thor", DeviceKind.KODI, "10.0.0.3")
        override suspend fun createController(device: Device): RemoteController = Fake(device).also { fake = it }
        override suspend fun saveDevice(device: Device) {}
        override val backgroundScope: CoroutineScope get() = scope
    }

    /** Décodeur factice : aucune image Android en JVM, on vérifie seulement le chemin des octets. */
    private class NullDecoder : ImageDecoder<ImageBitmap> {
        val decoded = mutableListOf<Int>()
        override fun decode(bytes: ByteArray, targetWidthPx: Int): ImageBitmap? {
            decoded += bytes.size
            return null
        }
        override fun sizeOf(image: ImageBitmap) = 0
    }

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private class Env(val vm: RemoteViewModel, val deps: Deps, val store: ViewModelStore, val decoder: NullDecoder) {
        val fake get() = deps.fake!!
    }

    private fun TestScope.connected(): Env {
        val deps = Deps(this)
        val store = ViewModelStore()
        val decoder = NullDecoder()
        val vm = ViewModelProvider.create(
            store,
            viewModelFactory {
                initializer {
                    RemoteViewModel(deps, device.id, null, SavedStateHandle(), dispatcher, imageDecoder = decoder)
                }
            },
        )[RemoteViewModel::class]
        advanceUntilIdle()
        return Env(vm, deps, store, decoder)
    }

    private fun TestScope.close(env: Env) {
        env.store.clear()
        advanceUntilIdle()
    }

    @Test
    fun `features expose series, search kinds and images`() = runTest(dispatcher) {
        val env = connected()
        val f = env.vm.state.value.features
        assertTrue(f.series && f.search && f.images)
        assertEquals(setOf(MediaKind.MOVIE, MediaKind.SERIES, MediaKind.EPISODE), f.searchKinds)
        // Série seule (sans file) : l'onglet « File » existe quand même.
        val onlySeries = RemoteFeatures(series = true)
        assertTrue(RemoteTab.QUEUE in RemoteLayouts.phoneTabs(emptySet(), onlySeries))
        assertTrue(RemoteTab.QUEUE in RemoteLayouts.sideTabs(RemoteLayout.TABLET_LANDSCAPE, onlySeries))
        close(env)
    }

    @Test
    fun `series loads with the queue panel, current season selected`() = runTest(dispatcher) {
        val env = connected()
        assertEquals(0, env.fake.seriesCalls)
        env.vm.setVisiblePanels(setOf(RemotePanel.QUEUE))
        advanceUntilIdle()
        val series = env.vm.state.value.series
        assertEquals("Série X", series.context?.title)
        assertEquals("s2", series.seasonId)
        assertEquals(listOf("s2@0"), env.fake.browseCalls)
        assertEquals(4, series.episodes.size)
        assertFalse(series.episodesLoading)
        // Épisode courant repéré par son identifiant.
        assertEquals(listOf("e2-3"), series.episodes.filter { isCurrentEpisode(it, series, env.vm.state.value.playback) }.map { it.id })
        close(env)
    }

    @Test
    fun `selecting a season loads its episodes, play and enqueue go to the library`() = runTest(dispatcher) {
        val env = connected()
        env.vm.setVisiblePanels(setOf(RemotePanel.QUEUE))
        advanceUntilIdle()
        env.vm.selectSeason("s1")
        assertTrue(env.vm.state.value.series.episodesLoading)
        advanceUntilIdle()
        val series = env.vm.state.value.series
        assertEquals("s1", series.seasonId)
        assertEquals("e1-1", series.episodes.first().id)
        // Même saison déjà chargée : pas de nouvelle requête.
        env.vm.selectSeason("s1")
        advanceUntilIdle()
        assertEquals(listOf("s2@0", "s1@0"), env.fake.browseCalls)
        env.vm.playEntry(series.episodes[1])
        env.vm.enqueue(series.episodes[2], next = true)
        advanceUntilIdle()
        assertEquals(listOf("e1-2", "enqueue:e1-3:true"), env.fake.played)
        close(env)
    }

    @Test
    fun `series refreshes when the media changes, not on position updates`() = runTest(dispatcher) {
        val env = connected()
        env.vm.setVisiblePanels(setOf(RemotePanel.QUEUE))
        advanceUntilIdle()
        assertEquals(1, env.fake.seriesCalls)
        env.fake.playback.value = env.fake.playback.value.copy(positionMs = 10_000)
        advanceUntilIdle()
        assertEquals(1, env.fake.seriesCalls)
        env.vm.selectSeason("s1")
        advanceUntilIdle()
        env.fake.playback.value = PlaybackState(title = "Épisode suivant", seriesTitle = "Série X", season = 2, episode = 4)
        advanceUntilIdle()
        assertEquals(2, env.fake.seriesCalls)
        // Retour sur la saison en cours au changement d'épisode.
        assertEquals("s2", env.vm.state.value.series.seasonId)
        close(env)
    }

    @Test
    fun `non episode media and errors are reported in the series state`() = runTest(dispatcher) {
        val env = connected()
        env.fake.series = null
        env.vm.setVisiblePanels(setOf(RemotePanel.QUEUE))
        advanceUntilIdle()
        val none = env.vm.state.value.series
        assertTrue(none.loaded)
        assertNull(none.context)
        env.fake.series = SeriesContext("show", "Série X", listOf(MediaEntry("s1", "Saison 1", browsable = true)))
        env.fake.browseFailure = IllegalStateException("Serveur indisponible")
        env.vm.refreshSeries()
        advanceUntilIdle()
        val failed = env.vm.state.value.series
        // Pas de saison courante annoncée : la première est choisie.
        assertEquals("s1", failed.seasonId)
        assertEquals("Serveur indisponible", failed.error)
        assertFalse(failed.episodesLoading)
        close(env)
    }

    @Test
    fun `search waits for typing to pause then queries once`() = runTest(dispatcher) {
        val env = connected()
        env.vm.openSearch()
        assertTrue(env.vm.state.value.search.open)
        listOf("b", "br", "bre", "brea").forEach {
            env.vm.setSearchQuery(it)
            advanceTimeBy(100)
        }
        assertTrue(env.fake.searches.isEmpty())
        assertTrue(env.vm.state.value.search.loading)
        advanceTimeBy(351)
        runCurrent()
        advanceUntilIdle()
        assertEquals(listOf("brea" to emptySet<MediaKind>()), env.fake.searches)
        val search = env.vm.state.value.search
        assertTrue(search.searched)
        assertFalse(search.loading)
        assertEquals(listOf("m1", "show"), search.results.map { it.id })
        close(env)
    }

    @Test
    fun `too short query clears results without querying`() = runTest(dispatcher) {
        val env = connected()
        env.vm.openSearch()
        env.vm.setSearchQuery("alien")
        advanceUntilIdle()
        assertEquals(2, env.vm.state.value.search.results.size)
        env.vm.setSearchQuery("a")
        advanceUntilIdle()
        assertEquals(1, env.fake.searches.size)
        assertTrue(env.vm.state.value.search.results.isEmpty())
        assertFalse(env.vm.state.value.search.searched)
        close(env)
    }

    @Test
    fun `kind filters and submit search immediately`() = runTest(dispatcher) {
        val env = connected()
        env.vm.openSearch()
        env.vm.setSearchQuery("dune")
        env.vm.submitSearch()
        runCurrent()
        assertEquals(listOf("dune" to emptySet<MediaKind>()), env.fake.searches)
        env.vm.toggleSearchKind(MediaKind.MOVIE)
        runCurrent()
        env.vm.toggleSearchKind(MediaKind.SERIES)
        runCurrent()
        env.vm.toggleSearchKind(MediaKind.MOVIE)
        runCurrent()
        assertEquals(setOf(MediaKind.SERIES), env.vm.state.value.search.kinds)
        assertEquals(setOf(MediaKind.SERIES), env.fake.searches.last().second)
        env.vm.clearSearchKinds()
        runCurrent()
        assertEquals(emptySet<MediaKind>(), env.fake.searches.last().second)
        close(env)
    }

    @Test
    fun `search error is shown in the view`() = runTest(dispatcher) {
        val env = connected()
        env.vm.openSearch()
        env.fake.searchFailure = IllegalStateException("Délai dépassé")
        env.vm.setSearchQuery("dune")
        advanceUntilIdle()
        assertEquals("Délai dépassé", env.vm.state.value.search.error)
        assertFalse(env.vm.state.value.search.loading)
        close(env)
    }

    @Test
    fun `browsable result opens in the search view with back navigation`() = runTest(dispatcher) {
        val env = connected()
        env.vm.openSearch()
        env.vm.setSearchQuery("série")
        advanceUntilIdle()
        val show = env.vm.state.value.search.results.single { it.browsable }
        env.vm.openSearchEntry(show)
        advanceUntilIdle()
        assertEquals(listOf("show"), env.vm.state.value.search.path.map { it.id })
        assertEquals(4, env.vm.state.value.search.current?.entries?.size)
        assertTrue(env.vm.state.value.search.current!!.endReached)
        env.vm.openSearchEntry(MediaEntry("s1", "Saison 1", browsable = true))
        advanceUntilIdle()
        assertEquals(2, env.vm.state.value.search.path.size)
        assertTrue(env.vm.searchBack())
        assertTrue(env.vm.searchBack())
        assertFalse(env.vm.searchBack())
        // Jouable : pas de navigation.
        env.vm.openSearchEntry(MediaEntry("m1", "Film"))
        assertTrue(env.vm.state.value.search.path.isEmpty())
        // Fermeture : saisie conservée, pile vidée.
        env.vm.openSearchEntry(show)
        advanceUntilIdle()
        env.vm.closeSearch()
        val closed = env.vm.state.value.search
        assertFalse(closed.open)
        assertTrue(closed.path.isEmpty())
        assertEquals("série", closed.query)
        close(env)
    }

    @Test
    fun `images are fetched through the controller at the bucketed width`() = runTest(dispatcher) {
        val env = connected()
        assertNull(env.vm.images.load("img-m1", 200))
        assertEquals(listOf("img-m1" to 240), env.fake.imageCalls)
        assertEquals(listOf(3), env.decoder.decoded)
        close(env)
    }

    @Test
    fun `now playing titles show series and episode code`() {
        assertEquals(
            NowPlayingTitles("Série X", "S01E10 · Le retour"),
            nowPlayingTitles(PlaybackState(title = "Le retour", seriesTitle = "Série X", season = 1, episode = 10)),
        )
        assertEquals(NowPlayingTitles("Série X", "E03"), nowPlayingTitles(PlaybackState(title = "Série X", seriesTitle = "Série X", episode = 3)))
        assertEquals(NowPlayingTitles("Film", null), nowPlayingTitles(PlaybackState(title = "Film", season = 1, episode = 2)))
        assertEquals(NowPlayingTitles(null, null), nowPlayingTitles(PlaybackState()))
    }

    @Test
    fun `entries are grouped by kind in a stable order`() {
        val entries = listOf(
            MediaEntry("1", "Ep", kind = MediaKind.EPISODE, season = 2, episode = 5),
            MediaEntry("2", "Film", kind = MediaKind.MOVIE, year = 1999),
            MediaEntry("3", "Inconnu"),
            MediaEntry("4", "Film 2", kind = MediaKind.MOVIE),
        )
        val groups = groupByKind(entries)
        assertEquals(listOf(MediaKind.MOVIE, MediaKind.EPISODE, null), groups.map { it.first })
        assertEquals(listOf("2", "4"), groups.first().second.map { it.id })
        assertEquals("S02E05", entries[0].episodeLabel())
        assertEquals("1999", entries[1].posterCaption())
        assertNull(entries[2].posterCaption())
    }

    @Test
    fun `series selector appears only for episodes of a series-capable device`() {
        val f = RemoteFeatures(series = true, queue = true)
        assertTrue(showsSeries(f, SeriesState(), PlaybackState(title = "x", seriesTitle = "S")))
        assertFalse(showsSeries(f, SeriesState(), PlaybackState(title = "Film")))
        assertFalse(showsSeries(RemoteFeatures(queue = true), SeriesState(), PlaybackState(seriesTitle = "S")))
        // Sans identifiant d'épisode courant : repérage par saison et numéro.
        val state = SeriesState(context = SeriesContext("s", "S", emptyList()))
        val playing = PlaybackState(season = 1, episode = 2)
        assertTrue(isCurrentEpisode(MediaEntry("a", "A", season = 1, episode = 2), state, playing))
        assertFalse(isCurrentEpisode(MediaEntry("b", "B", season = 2, episode = 2), state, playing))
    }
}
