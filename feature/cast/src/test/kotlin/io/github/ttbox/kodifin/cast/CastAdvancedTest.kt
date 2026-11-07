package io.github.ttbox.kodifin.cast

import io.github.ttbox.kodifin.model.Device
import io.github.ttbox.kodifin.model.DeviceKind
import io.github.ttbox.kodifin.model.PowerAction
import io.github.ttbox.kodifin.model.RemoteError
import io.github.ttbox.kodifin.model.RemoteException
import io.github.ttbox.kodifin.model.RepeatMode
import io.github.ttbox.kodifin.model.UnsupportedAction
import io.github.ttbox.kodifin.model.UnsupportedActionException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import kotlin.coroutines.EmptyCoroutineContext

/** Lecteur Cast complet : pistes, vitesse, file et modes. */
private class RichMedia : CastMediaPort {
    override val title: String? = "Épisode"
    override var isPlaying = true
    override var positionMs = 50_000L
    override var durationMs = 60_000L
    override var nowPlaying: CastNowPlaying? = CastNowPlaying(
        title = "Pilote", seriesTitle = "Série", season = 1, episode = 2,
        imageUrl = "http://img/poster.jpg", backdropUrl = "http://img/fanart.jpg",
    )
    override var tracks = listOf(
        CastTrack(1, CastTrackType.VIDEO, "Vidéo", null),
        CastTrack(2, CastTrackType.AUDIO, "Français", "fr"),
        CastTrack(3, CastTrackType.AUDIO, null, "en"),
        CastTrack(4, CastTrackType.TEXT, "Sous-titres FR", "fr"),
        CastTrack(5, CastTrackType.TEXT, "", null),
    )
    override var activeTrackIds = setOf(1L, 2L)
    var rate = 1.0
    var mode = CastRepeat.OFF
    override val playbackRate get() = rate
    override val repeat get() = mode
    override var queueItems = listOf(
        CastQueueItem(10, "Un", "Artiste", "http://img/1.jpg"),
        CastQueueItem(11, null, null, null),
        CastQueueItem(12, "Trois", null, null),
    )
    override var currentItemId: Int? = 11
    override var contentUrl: String? = "http://nas/film.mp4"
    override val contentType: String? = "video/mp4"
    val calls = mutableListOf<String>()

    override fun togglePlayback() { calls += "toggle" }
    override fun stop() { calls += "stop" }
    override fun next() { calls += "next" }
    override fun previous() { calls += "previous" }
    override fun seek(positionMs: Long) { calls += "seek:$positionMs" }
    override suspend fun load(url: String, contentType: String, title: String, options: CastLoadOptions) = Unit
    override fun setTextTrackStyle(style: CastSubtitleStyle) = Unit
    override fun setActiveTracks(ids: Set<Long>) { activeTrackIds = ids }
    override fun setPlaybackRate(rate: Double) { this.rate = rate }
    override fun setRepeat(mode: CastRepeat) { this.mode = mode }
    override fun jumpTo(itemId: Int) { calls += "jump:$itemId" }
    override fun removeItems(itemIds: List<Int>) { calls += "remove:$itemIds" }
}

private class RichSession(override var media: CastMediaPort?) : CastSessionPort {
    override val deviceId = "cast-salon"
    override val isConnected = true
    override val isConnecting = false
    override var volume = 0.4
    override var isMute = false
    override val appName = "Jellyfin"
}

private class SinglePlatform(val session: RichSession) : CastPlatform {
    var ended = 0
    override suspend fun ready() = Unit
    override fun routes() = listOf(CastRoute("route-salon", "192.168.1.20", "cast-salon"))
    override fun currentSession(): CastSessionPort? = session.takeIf { ended == 0 }
    override fun selectRoute(route: CastRoute) = Unit
    override fun endCurrentSession() { ended++ }
    override fun listen(events: CastSessionEvents) = AutoCloseable {}
}

class CastAdvancedTest {
    private val media = RichMedia()
    private val session = RichSession(media)
    private val platform = SinglePlatform(session)
    private val device = Device(id = "route-salon", name = "Salon", kind = DeviceKind.CHROMECAST, host = "192.168.1.20")
    private val fetched = mutableListOf<String>()
    private val controller = CastController(
        device, platform, EmptyCoroutineContext,
        imageFetcher = { url -> fetched += url; byteArrayOf(1, 2) },
    )

    @Test
    fun `playback state carries series, artwork and the sender app`() = runTest {
        val state = controller.playbackState().first()
        assertEquals("Pilote", state.title)
        assertEquals("Série", state.seriesTitle)
        assertEquals(1, state.season)
        assertEquals(2, state.episode)
        assertEquals("Jellyfin", state.appName)
        assertEquals("http://img/poster.jpg", state.artworkKey)
        assertEquals("http://img/fanart.jpg", state.backdropKey)
        // Pas d'artiste déduit du sous-titre pour un épisode.
        assertNull(state.artist)
    }

    @Test
    fun `music subtitle becomes the artist when no artist is published`() = runTest {
        media.nowPlaying = CastNowPlaying(title = "Titre", subtitle = "Groupe", album = "Album")
        val state = controller.playbackState().first()
        assertEquals("Groupe", state.artist)
        assertEquals("Album", state.album)
    }

    @Test
    fun `no artwork keys without an image fetcher`() = runTest {
        val bare = CastController(device, platform, EmptyCoroutineContext)
        val state = bare.playbackState().first()
        assertNull(state.artworkKey)
        assertNull(bare.image("http://img/poster.jpg", 300))
        assertTrue(bare.queue().all { it.imageKey == null })
    }

    @Test
    fun `media details list audio and text tracks with the active ones`() = runTest {
        val d = controller.mediaDetails()
        assertEquals(listOf("Français", "en"), d.audioTracks.map { it.label })
        assertEquals(2, d.currentAudio)
        assertEquals(listOf("Sous-titres FR", "Piste 2"), d.subtitles.map { it.label })
        assertNull(d.currentSubtitle)
        assertEquals(RepeatMode.OFF, d.repeat)
        assertEquals(false, d.shuffle)
        assertEquals(1.0, d.playbackRate!!, 0.0)
        assertEquals(1, d.speed)
    }

    @Test
    fun `no media gives paused details`() = runTest {
        session.media = null
        assertEquals(0, controller.mediaDetails().speed)
        assertEquals(emptyList<Any>(), controller.queue())
        assertNull(controller.queuePosition())
    }

    @Test
    fun `track selection replaces only tracks of the same kind`() = runTest {
        controller.setAudioTrack(3)
        assertEquals(setOf(1L, 3L), media.activeTrackIds)
        controller.setSubtitle(4)
        assertEquals(setOf(1L, 3L, 4L), media.activeTrackIds)
        controller.setSubtitle(null)
        assertEquals(setOf(1L, 3L), media.activeTrackIds)
    }

    @Test
    fun `cycling tracks wraps around, subtitles through off`() = runTest {
        controller.nextAudioTrack()
        assertEquals(setOf(1L, 3L), media.activeTrackIds)
        controller.nextAudioTrack()
        assertEquals(setOf(1L, 2L), media.activeTrackIds)
        controller.nextSubtitle()
        assertTrue(4L in media.activeTrackIds)
        controller.nextSubtitle()
        assertTrue(5L in media.activeTrackIds)
        controller.nextSubtitle()
        assertFalse(media.activeTrackIds.any { it == 4L || it == 5L })
    }

    @Test
    fun `cycling without tracks does nothing`() = runTest {
        media.tracks = emptyList()
        controller.nextSubtitle()
        assertEquals(setOf(1L, 2L), media.activeTrackIds)
    }

    @Test
    fun `speed steps through the receiver rates and stays in range`() = runTest {
        controller.faster()
        assertEquals(1.25, media.playbackRate, 0.0)
        controller.slower(); controller.slower()
        assertEquals(0.75, media.playbackRate, 0.0)
        media.rate = 2.0
        controller.faster()
        assertEquals(2.0, media.playbackRate, 0.0)
        media.rate = 0.5
        controller.slower()
        assertEquals(0.5, media.playbackRate, 0.0)
    }

    @Test
    fun `next rate rounds an unlisted rate to its neighbours`() {
        assertEquals(1.25, CastController.nextRate(1.1, +1), 0.0)
        assertEquals(1.0, CastController.nextRate(1.1, -1), 0.0)
        assertEquals(2.0, CastController.nextRate(3.0, +1), 0.0)
    }

    @Test
    fun `seek by is clamped to the media`() = runTest {
        controller.seekBy(30_000)
        controller.seekBy(-80_000)
        assertEquals(2, media.calls.count { it.startsWith("seek:") })
        assertEquals("seek:0", media.calls.last())
    }

    @Test
    fun `repeat and shuffle map to cast queue modes`() = runTest {
        controller.setRepeat(RepeatMode.ONE)
        assertEquals(CastRepeat.SINGLE, media.repeat)
        controller.setShuffle(true)
        assertEquals(CastRepeat.ALL_AND_SHUFFLE, media.repeat)
        controller.setRepeat(RepeatMode.ALL)
        assertEquals(CastRepeat.ALL_AND_SHUFFLE, media.repeat)
        val d = controller.mediaDetails()
        assertEquals(RepeatMode.ALL, d.repeat)
        assertEquals(true, d.shuffle)
        controller.setShuffle(false)
        assertEquals(CastRepeat.ALL, media.repeat)
        controller.setRepeat(RepeatMode.OFF)
        assertEquals(CastRepeat.OFF, media.repeat)
    }

    @Test
    fun `queue lists receiver items and acts on item ids`() = runTest {
        val queue = controller.queue()
        assertEquals(listOf("Un", "Élément 11", "Trois"), queue.map { it.title })
        assertEquals("http://img/1.jpg", queue.first().imageKey)
        assertEquals(1, controller.queuePosition())
        controller.playQueueItem(2)
        controller.removeQueueItem(0)
        controller.clearQueue()
        assertEquals(listOf("jump:12", "remove:[10]", "remove:[10, 12]"), media.calls)
        try {
            controller.playQueueItem(9)
            fail("index hors file")
        } catch (e: RemoteException) {
            assertEquals(RemoteError.ItemNotFound, e.reason)
        }
    }

    @Test
    fun `current media is transferable only when it is an http address`() = runTest {
        val media = controller.currentMedia()!!
        assertEquals("http://nas/film.mp4", media.url)
        assertEquals("Pilote", media.title)
        assertEquals("video/mp4", media.mimeType)
        assertEquals(50_000L, media.positionMs)
        this@CastAdvancedTest.media.contentUrl = "yt:dQw4w9WgXcQ"
        assertNull(controller.currentMedia())
        session.media = null
        assertNull(controller.currentMedia())
    }

    @Test
    fun `images go through the fetcher`() = runTest {
        assertEquals(2, controller.image("http://img/poster.jpg", 300)?.size)
        assertEquals(listOf("http://img/poster.jpg"), fetched)
    }

    @Test
    fun `quit app ends the cast session, other actions are refused`() = runTest {
        assertEquals(setOf(PowerAction.QUIT_APP), controller.availablePowerActions())
        try {
            controller.power(PowerAction.REBOOT)
            fail("action refusée attendue")
        } catch (e: UnsupportedActionException) {
            assertEquals(UnsupportedAction.Power(PowerAction.REBOOT), e.action)
            assertEquals(DeviceKind.CHROMECAST, e.kind)
        }
        controller.power(PowerAction.QUIT_APP)
        assertEquals(1, platform.ended)
    }
}
