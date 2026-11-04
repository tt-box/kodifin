package io.github.jmorille.zapette.cast

import io.github.jmorille.zapette.model.Device
import io.github.jmorille.zapette.model.DeviceKind
import io.github.jmorille.zapette.model.RemoteException
import io.github.jmorille.zapette.model.RemoteKey
import io.github.jmorille.zapette.model.UnsupportedActionException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import kotlin.coroutines.EmptyCoroutineContext

private class FakeMedia(
    override var positionMs: Long = 30_000,
    override var durationMs: Long = 60_000,
) : CastMediaPort {
    override var title: String? = "Film"
    override var isPlaying = true
    val calls = mutableListOf<String>()
    var failure: Exception? = null

    private fun record(call: String) {
        failure?.let { throw it }
        calls += call
    }

    override fun togglePlayback() = record("toggle")
    override fun stop() = record("stop")
    override fun next() = record("next")
    override fun previous() = record("previous")
    override fun seek(positionMs: Long) = record("seek:$positionMs")
    override suspend fun load(url: String, contentType: String, title: String) = record("load:$url|$contentType|$title")
}

private class FakeSession(
    override val deviceId: String?,
    override var isConnected: Boolean = true,
    override var isConnecting: Boolean = false,
    override var media: CastMediaPort? = FakeMedia(),
) : CastSessionPort {
    override var volume = 0.5
    override var isMute = false
}

/** Faux SDK : routes, session courante et SessionManager. */
private class FakePlatform : CastPlatform {
    val routes = mutableListOf(
        CastRoute("route-salon", "192.168.1.20", "cast-salon"),
        CastRoute("route-chambre", "192.168.1.21", "cast-chambre"),
    )
    var current: FakeSession? = null
    val selected = mutableListOf<String>()
    var ended = 0
    val listeners = mutableListOf<CastSessionEvents>()
    var readyFailure: Exception? = null

    /** Réaction à la sélection d'une route ; par défaut la session s'ouvre aussitôt. */
    var onSelect: (CastRoute) -> Unit = { route ->
        val session = FakeSession(route.deviceId)
        current = session
        listeners.toList().forEach { it.started(session) }
    }

    override suspend fun ready() {
        readyFailure?.let { throw it }
    }

    override fun routes(): List<CastRoute> = routes
    override fun currentSession(): CastSessionPort? = current
    override fun selectRoute(route: CastRoute) {
        selected += route.id
        onSelect(route)
    }

    override fun endCurrentSession() {
        ended++
        current = null
    }

    override fun listen(events: CastSessionEvents): AutoCloseable {
        listeners += events
        return AutoCloseable { listeners -= events }
    }
}

private fun chromecast(id: String = "route-salon", host: String = "192.168.1.20") =
    Device(id = id, name = "Salon", kind = DeviceKind.CHROMECAST, host = host)

/** Parcourt la chaîne des causes (les coroutines peuvent recopier l'exception pour la pile d'appel). */
private inline fun <reified T : Throwable> Throwable.hasCause(): Boolean =
    generateSequence(cause) { it.cause }.any { it is T }

@OptIn(ExperimentalCoroutinesApi::class)
class CastControllerTest {
    private val platform = FakePlatform()

    private fun controller(device: Device = chromecast()) = CastController(device, platform, EmptyCoroutineContext)

    private suspend inline fun <reified T : Throwable> assertThrows(block: () -> Unit): T {
        try {
            block()
        } catch (e: Throwable) {
            if (e is T) return e
            throw AssertionError("${T::class.simpleName} attendue, ${e::class.simpleName} levée", e)
        }
        fail("${T::class.simpleName} attendue")
        throw IllegalStateException()
    }

    @Test
    fun `route is found by id first, then by address`() {
        assertEquals("route-chambre", controller(chromecast("route-chambre", "192.168.1.20")).route()?.id)
        assertEquals("route-chambre", controller(chromecast("manuel", "[192.168.1.21]")).route()?.id)
        assertNull(controller(chromecast("manuel", "192.168.1.99")).route())
    }

    @Test
    fun `isOurs compares the session device with our route`() {
        val c = controller()
        assertTrue(c.isOurs(FakeSession("cast-salon")))
        assertFalse(c.isOurs(FakeSession("cast-chambre")))
        assertFalse(c.isOurs(FakeSession(null)))
        assertFalse(c.isOurs(null))
        platform.routes[0] = CastRoute("route-salon", "192.168.1.20", null)
        assertFalse(c.isOurs(FakeSession("cast-salon")))
        platform.routes.clear()
        assertFalse(c.isOurs(FakeSession("cast-salon")))
    }

    @Test
    fun `an existing connected session to our device is reused`() = runTest {
        platform.current = FakeSession("cast-salon")
        controller().connect()
        assertTrue(platform.selected.isEmpty())
    }

    @Test
    fun `connect selects our route and stops listening afterwards`() = runTest {
        platform.current = FakeSession("cast-chambre")
        controller().connect()
        assertEquals(listOf("route-salon"), platform.selected)
        assertTrue(platform.listeners.isEmpty())
    }

    @Test
    fun `a session already connecting to our device is awaited without reselecting`() = runTest {
        val connecting = FakeSession("cast-salon", isConnected = false, isConnecting = true)
        platform.current = connecting
        val job = async { controller().connect() }
        runCurrent()
        assertTrue(platform.selected.isEmpty())
        platform.listeners.single().started(connecting)
        job.await()
        assertTrue(platform.listeners.isEmpty())
    }

    @Test
    fun `a session started for another device is ignored and the wait times out`() = runTest {
        platform.onSelect = { platform.listeners.toList().forEach { it.started(FakeSession("cast-chambre")) } }
        val result = async { runCatching { controller().connect() } }
        advanceTimeBy(15_001)
        val error = result.await().exceptionOrNull()
        assertTrue(error is RemoteException)
        assertTrue(error!!.message!!.contains("ne répond pas"))
        assertEquals(1, platform.ended)
        assertTrue(platform.listeners.isEmpty())
    }

    @Test
    fun `session failures become RemoteException`() = runTest {
        platform.onSelect = { platform.listeners.toList().forEach { it.failed("Échec de la session Cast (2005)") } }
        val e = assertThrows<RemoteException> { controller().connect() }
        assertEquals("Échec de la session Cast (2005)", e.message)
        assertTrue(platform.listeners.isEmpty())
    }

    @Test
    fun `missing route is reported`() = runTest {
        platform.routes.clear()
        val e = assertThrows<RemoteException> { controller().connect() }
        assertTrue(e.message!!.contains("n'est plus visible"))
    }

    @Test
    fun `sdk initialisation errors are translated`() = runTest {
        platform.readyFailure = IllegalStateException("pas sur le thread principal")
        assertThrows<RemoteException> { controller().connect() }
        val unavailable = RemoteException("Google Cast indisponible")
        platform.readyFailure = unavailable
        assertEquals(unavailable.message, assertThrows<RemoteException> { controller().connect() }.message)
    }

    @Test
    fun `keys map to media actions`() = runTest {
        val session = FakeSession("cast-salon")
        val media = FakeMedia(positionMs = 5_000, durationMs = 60_000)
        session.media = media
        platform.current = session
        val c = controller()

        c.send(RemoteKey.PLAY_PAUSE)
        c.send(RemoteKey.STOP)
        c.send(RemoteKey.NEXT)
        c.send(RemoteKey.PREVIOUS)
        c.send(RemoteKey.REWIND)
        media.positionMs = 55_000
        c.send(RemoteKey.FAST_FORWARD)
        assertEquals(listOf("toggle", "stop", "next", "previous", "seek:0", "seek:60000"), media.calls)

        session.volume = 0.98
        c.send(RemoteKey.VOLUME_UP)
        assertEquals(1.0, session.volume, 1e-9)
        c.send(RemoteKey.VOLUME_DOWN)
        assertEquals(0.95, session.volume, 1e-9)
        c.send(RemoteKey.MUTE)
        assertTrue(session.isMute)
        c.send(RemoteKey.MUTE)
        assertFalse(session.isMute)

        assertThrows<UnsupportedActionException> { c.send(RemoteKey.UP) }
    }

    @Test
    fun `volume and seek requests are clamped`() = runTest {
        val session = FakeSession("cast-salon")
        val media = FakeMedia(durationMs = 60_000)
        session.media = media
        platform.current = session
        val c = controller()
        c.setVolume(150)
        assertEquals(1.0, session.volume, 1e-9)
        c.setVolume(-5)
        assertEquals(0.0, session.volume, 1e-9)
        c.setVolume(42)
        assertEquals(0.42, session.volume, 1e-9)
        c.seekTo(90_000)
        c.seekTo(-1)
        assertEquals(listOf("seek:60000", "seek:0"), media.calls)
    }

    @Test
    fun `playUrl loads the media with its type and a default title`() = runTest {
        val media = FakeMedia()
        platform.current = FakeSession("cast-salon", media = media)
        val url = "http://nas/films/vacances.mkv"
        controller().playUrl(url, null)
        controller().playUrl(url, "Vacances")
        val type = CastMedia.contentType(url)
        assertEquals(listOf("load:$url|$type|vacances.mkv", "load:$url|$type|Vacances"), media.calls)
    }

    @Test
    fun `sdk errors are translated into RemoteException`() = runTest {
        val media = FakeMedia()
        platform.current = FakeSession("cast-salon", media = media)
        val c = controller()

        media.failure = IllegalStateException("session fermée")
        assertTrue(assertThrows<RemoteException> { c.send(RemoteKey.PLAY_PAUSE) }.hasCause<IllegalStateException>())
        media.failure = IOException("canal fermé")
        assertTrue(assertThrows<RemoteException> { c.playUrl("http://nas/a.mp4", null) }.hasCause<IOException>())
        val refused = RemoteException("Chargement du média refusé")
        media.failure = refused
        val passed = assertThrows<RemoteException> { c.playUrl("http://nas/a.mp4", null) }
        assertEquals(refused.message, passed.message)
        assertFalse(passed.hasCause<IOException>())
    }

    @Test
    fun `missing player is reported`() = runTest {
        platform.current = FakeSession("cast-salon", media = null)
        val e = assertThrows<RemoteException> { controller().send(RemoteKey.STOP) }
        assertTrue(e.message!!.contains("Aucun lecteur"))
    }

    @Test
    fun `disconnect only ends the session of this device`() = runTest {
        platform.current = FakeSession("cast-chambre")
        controller().disconnect()
        assertEquals(0, platform.ended)

        platform.current = FakeSession("cast-salon")
        controller().disconnect()
        assertEquals(1, platform.ended)

        // Erreur du SDK : ignorée à la déconnexion.
        platform.readyFailure = RemoteException("Google Cast indisponible")
        controller().disconnect()
        assertEquals(1, platform.ended)
    }

    @Test
    fun `playback state is polled every second`() = runTest {
        val session = FakeSession("cast-salon")
        val media = FakeMedia(positionMs = 1_000, durationMs = 60_000)
        session.media = media
        platform.current = session
        val states = async { controller().playbackState().take(2).toList() }
        runCurrent()
        media.positionMs = 2_000
        session.isMute = true
        advanceTimeBy(1_001)
        val (first, second) = states.await()
        assertEquals("Film", first.title)
        assertTrue(first.isPlaying)
        assertEquals(1_000L, first.positionMs)
        assertEquals(60_000L, first.durationMs)
        assertEquals(50, first.volumePercent)
        assertFalse(first.muted)
        assertEquals(2_000L, second.positionMs)
        assertTrue(second.muted)
    }

    @Test
    fun `playback state without player and after disconnection`() = runTest {
        val session = FakeSession("cast-salon", media = null)
        platform.current = session
        val c = controller()
        val idle = c.playbackState().first()
        assertNull(idle.title)
        assertFalse(idle.isPlaying)
        assertEquals(0L, idle.positionMs)

        val states = async { runCatching { c.playbackState().take(2).toList() } }
        runCurrent()
        session.isConnected = false
        advanceTimeBy(1_001)
        assertTrue(states.await().exceptionOrNull() is RemoteException)
    }
}
