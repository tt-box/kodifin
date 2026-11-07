package io.github.ttbox.kodifin.discovery

import io.github.ttbox.kodifin.model.Device
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

/** Faux NsdManager : le test déclenche lui-même les rappels. */
private class FakeNsd : NsdBackend {
    lateinit var events: NsdBackend.Events
    var stopped = 0

    override fun startDiscovery(serviceType: String, events: NsdBackend.Events): AutoCloseable {
        this.events = events
        return AutoCloseable { stopped++ }
    }
}

private class FakeWatch(val onUpdate: (NsdResolution) -> Unit, val onLost: () -> Unit) : AutoCloseable {
    var closed = 0
    override fun close() {
        closed++
    }
}

private class FakeService(
    override val name: String,
    private val watchable: Boolean = false,
    private val watchFails: Boolean = false,
    private val result: suspend () -> NsdResolution? = { null },
) : NsdBackend.Service {
    val watches = mutableListOf<FakeWatch>()
    var resolves = 0

    override fun watch(onUpdate: (NsdResolution) -> Unit, onLost: () -> Unit): AutoCloseable? {
        if (watchFails) throw IllegalArgumentException("callback déjà enregistré")
        if (!watchable) return null
        return FakeWatch(onUpdate, onLost).also { watches += it }
    }

    override suspend fun resolve(): NsdResolution? {
        resolves++
        return result()
    }
}

private fun ip(literal: String): InetAddress = InetAddress.getByName(literal)

private fun resolution(vararg hosts: String, name: String? = null, port: Int = 8080) =
    NsdResolution(name, hosts.map(::ip), port)

@OptIn(ExperimentalCoroutinesApi::class)
class NsdSourceTest {

    private val nsd = FakeNsd()

    private fun kotlinx.coroutines.test.TestScope.start(
        source: DiscoverySource = NsdSource(nsd, NsdMapping.KODI, Mutex()),
    ): Pair<MutableList<List<Device>>, kotlinx.coroutines.Job> {
        val seen = mutableListOf<List<Device>>()
        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { source.devices().collect { seen += it } }
        return seen to job
    }

    @Test
    fun `legacy resolution publishes the device with its preferred address`() = runTest {
        val (seen, _) = start()
        nsd.events.found(FakeService("""Salon\032TV""", result = { resolution("fe80::1", "192.168.1.10", port = 8080) }))
        runCurrent()
        val device = seen.last().single()
        assertEquals("Salon TV", device.name)
        assertEquals("192.168.1.10", device.host)
        assertEquals("KODI:192.168.1.10:8080", device.id)
    }

    @Test
    fun `duplicate announcements are resolved only once`() = runTest {
        start()
        val service = FakeService("kodi", result = { resolution("192.168.1.10") })
        nsd.events.found(service)
        nsd.events.found(service)
        runCurrent()
        assertEquals(1, service.resolves)
    }

    @Test
    fun `a service lost before its resolution is not recreated`() = runTest {
        val (seen, _) = start()
        val gate = CompletableDeferred<NsdResolution?>()
        nsd.events.found(FakeService("kodi", result = { gate.await() }))
        runCurrent()
        nsd.events.lost("kodi")
        gate.complete(resolution("192.168.1.10"))
        runCurrent()
        assertTrue(seen.all { it.isEmpty() })
    }

    @Test
    fun `watched service follows address changes and is removed without host`() = runTest {
        val (seen, _) = start()
        val service = FakeService("kodi", watchable = true)
        nsd.events.found(service)
        val watch = service.watches.single()

        watch.onUpdate(resolution("192.168.1.10"))
        assertEquals("192.168.1.10", seen.last().single().host)
        watch.onUpdate(resolution("192.168.1.11"))
        assertEquals("192.168.1.11", seen.last().single().host)
        // Seule une IPv6 lien-local : aucun hôte exploitable → retiré.
        watch.onUpdate(resolution("fe80::1"))
        assertTrue(seen.last().isEmpty())
        assertEquals(0, service.resolves)
    }

    @Test
    fun `watcher lost callback removes the device and closes the watcher`() = runTest {
        val (seen, _) = start()
        val service = FakeService("kodi", watchable = true)
        nsd.events.found(service)
        val watch = service.watches.single()
        watch.onUpdate(resolution("192.168.1.10"))
        watch.onLost()
        assertTrue(seen.last().isEmpty())
        assertEquals(1, watch.closed)
        // Mise à jour tardive après la perte : ignorée.
        watch.onUpdate(resolution("192.168.1.10"))
        assertTrue(seen.last().isEmpty())
    }

    @Test
    fun `failing watch registration falls back to a one-shot resolution`() = runTest {
        val (seen, _) = start()
        val service = FakeService("kodi", watchFails = true, result = { resolution("192.168.1.10") })
        nsd.events.found(service)
        runCurrent()
        assertEquals(1, service.resolves)
        assertEquals(1, seen.last().size)
    }

    @Test
    fun `start failure closes the flow cleanly`() = runTest {
        var completed = false
        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            NsdSource(nsd, NsdMapping.KODI, Mutex()).devices().collect {}
            completed = true
        }
        nsd.events.failed(3)
        runCurrent()
        assertTrue(completed)
        assertFalse(job.isCancelled)
        assertEquals(1, nsd.stopped)
    }

    @Test
    fun `cancellation stops discovery and closes every watcher`() = runTest {
        val (_, job) = start()
        val a = FakeService("a", watchable = true)
        val b = FakeService("b", watchable = true)
        nsd.events.found(a)
        nsd.events.found(b)
        job.cancel()
        runCurrent()
        assertEquals(1, nsd.stopped)
        assertEquals(1, a.watches.single().closed)
        assertEquals(1, b.watches.single().closed)
    }

    @Test
    fun `a hanging resolution times out and does not block the next one`() = runTest {
        val lock = Mutex()
        val (seen, _) = start(NsdSource(nsd, NsdMapping.KODI, lock, resolveTimeoutMs = 10_000))
        nsd.events.found(FakeService("muet", result = { awaitCancellation() }))
        nsd.events.found(FakeService("kodi", result = { resolution("192.168.1.10") }))
        runCurrent()
        assertTrue(seen.isEmpty())
        advanceTimeBy(10_001)
        runCurrent()
        assertEquals("192.168.1.10", seen.last().single().host)
        assertFalse(lock.isLocked)
    }

    @Test
    fun `a throwing resolution does not break the flow`() = runTest {
        val (seen, job) = start()
        nsd.events.found(FakeService("ko", result = { throw IllegalArgumentException("listener déjà utilisé") }))
        nsd.events.found(FakeService("kodi", result = { resolution("192.168.1.10") }))
        runCurrent()
        assertTrue(job.isActive)
        assertEquals(1, seen.last().size)
    }

    @Test
    fun `unknown service type yields no device`() = runTest {
        val (seen, _) = start(NsdSource(nsd, "_inconnu._tcp", Mutex()))
        nsd.events.found(FakeService("x", result = { resolution("192.168.1.10") }))
        runCurrent()
        assertTrue(seen.last().isEmpty())
    }
}

class NsdTrackerTest {
    @Test
    fun `state transitions`() {
        val t = NsdTracker()
        assertTrue(t.found("a"))
        assertFalse(t.found("a"))
        assertNull(t.resolved("inconnu", device("x")))
        assertEquals(listOf("x"), t.resolved("a", device("x"))?.map { it.id })
        assertEquals(emptyList<Device>(), t.resolved("a", null))
        assertNull(t.lost("inconnu"))
        assertEquals(emptyList<Device>(), t.lost("a"))
        // Résolution après perte : pas de résurrection.
        assertNull(t.resolved("a", device("x")))
        assertTrue(t.snapshot.isEmpty())
    }

    @Test
    fun `watchers are closed on loss, replacement, late attach and closeAll`() {
        val t = NsdTracker()
        val closed = mutableListOf<String>()
        fun watcher(id: String) = AutoCloseable { closed += id }

        t.found("a")
        assertTrue(t.attach("a", watcher("a1")))
        assertTrue(t.attach("a", watcher("a2")))
        assertEquals(listOf("a1"), closed)
        t.lost("a")
        assertEquals(listOf("a1", "a2"), closed)
        assertFalse(t.attach("a", watcher("a3")))
        assertEquals("a3", closed.last())

        t.found("b")
        t.attach("b", watcher("b1"))
        t.closeAll()
        assertEquals("b1", closed.last())
        assertFalse(t.found("c"))
        assertFalse(t.attach("b", watcher("b2")))
        assertEquals("b2", closed.last())
    }
}
