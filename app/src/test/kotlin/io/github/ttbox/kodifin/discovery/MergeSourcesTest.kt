package io.github.ttbox.kodifin.discovery

import io.github.ttbox.kodifin.model.Device
import io.github.ttbox.kodifin.model.DeviceKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

internal fun device(id: String, name: String = id) = Device(id = id, name = name, kind = DeviceKind.KODI, host = "10.0.0.1")

/** Faux verrou multicast : trace acquisition et libération. */
internal class FakeLock(private val failOnAcquire: Boolean = false) : MulticastLockPort {
    override var isHeld = false
    var released = 0
    override fun acquire() {
        if (failOnAcquire) throw SecurityException("CHANGE_WIFI_MULTICAST_STATE manquante")
        isHeld = true
    }

    override fun release() {
        isHeld = false
        released++
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class MergeSourcesTest {

    @Test
    fun `sources are merged, deduplicated by id and sorted case-insensitively`() = runTest {
        val sources = listOf(
            DiscoverySource { flowOf(listOf(device("1", "beta"))) },
            DiscoverySource { flowOf(listOf(device("1", "beta (doublon)"), device("3", "Charlie"), device("2", "Alpha"))) },
        )
        val seen = mutableListOf<List<Device>>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            mergeSources(sources) { null }.collect { seen += it }
        }
        runCurrent()
        assertEquals(listOf("Alpha", "beta", "Charlie"), seen.last().map { it.name })
    }

    @Test
    fun `a failing source does not erase the others`() = runTest {
        val sources = listOf(
            DiscoverySource { flowOf(listOf(device("ok"))) },
            DiscoverySource {
                flow {
                    emit(listOf(device("ko")))
                    throw IOException("réseau")
                }
            },
            DiscoverySource { flow { throw IllegalStateException("démarrage") } },
        )
        val seen = mutableListOf<List<Device>>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            mergeSources(sources) { null }.collect { seen += it }
        }
        runCurrent()
        assertEquals(listOf("ok"), seen.last().map { it.id })
    }

    @Test
    fun `later updates replace the previous list of the same source`() = runTest {
        val state = MutableStateFlow(listOf(device("a")))
        val seen = mutableListOf<List<Device>>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            mergeSources(listOf(DiscoverySource { state }, DiscoverySource { flowOf(listOf(device("z"))) })) { null }
                .collect { seen += it }
        }
        runCurrent()
        state.value = listOf(device("b"))
        runCurrent()
        assertEquals(listOf("b", "z"), seen.last().map { it.id })
    }

    @Test
    fun `multicast lock is held while collecting and released on cancellation`() = runTest {
        val lock = FakeLock()
        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            mergeSources(listOf(DiscoverySource { flowOf(listOf(device("a"))) })) { lock }.collect {}
        }
        runCurrent()
        assertTrue(lock.isHeld)
        job.cancel()
        runCurrent()
        assertFalse(lock.isHeld)
        assertEquals(1, lock.released)
    }

    @Test
    fun `multicast lock is released when the collector fails`() = runTest {
        val lock = FakeLock()
        val result = runCatching {
            mergeSources(listOf(DiscoverySource { flowOf(listOf(device("a"))) })) { lock }
                .collect { throw IOException("collecteur") }
        }
        assertTrue(result.exceptionOrNull() is IOException)
        assertFalse(lock.isHeld)
        assertEquals(1, lock.released)
    }

    @Test
    fun `multicast lock is released after first()`() = runTest {
        val lock = FakeLock()
        val first = mergeSources(listOf(DiscoverySource { flowOf(listOf(device("a"))) })) { lock }.first()
        assertEquals("a", first.single().id)
        assertFalse(lock.isHeld)
    }

    @Test
    fun `DiscoveryManager scans its injected sources`() = runTest {
        val lock = FakeLock()
        val manager = DiscoveryManager(listOf(DiscoverySource { flowOf(listOf(device("b"), device("a"))) })) { lock }
        assertEquals(listOf("a", "b"), manager.scan().first().map { it.id })
        assertEquals(1, lock.released)
    }

    @Test
    fun `discovery continues without the multicast lock`() = runTest {
        val refused = FakeLock(failOnAcquire = true)
        val first = mergeSources(listOf(DiscoverySource { flowOf(listOf(device("a"))) })) { refused }.first()
        assertEquals("a", first.single().id)
        assertEquals(0, refused.released)

        val noWifi = mergeSources(listOf(DiscoverySource { flowOf(listOf(device("b"))) })) {
            throw IllegalStateException("pas de WifiManager")
        }.first()
        assertEquals("b", noWifi.single().id)
    }

    @Test
    fun `periodic reruns every 15 s in virtual time`() = runTest {
        var runs = 0
        val seen = mutableListOf<List<Device>>()
        val job = backgroundScope.launch {
            periodic {
                runs++
                if (runs == 2) throw IOException("échec ponctuel")
                listOf(device("r$runs"))
            }.collect { seen += it }
        }
        runCurrent()
        assertEquals(1, runs)
        advanceTimeBy(DEFAULT_PERIOD_MS - 1)
        runCurrent()
        assertEquals(1, runs)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(2, runs)
        advanceTimeBy(2 * DEFAULT_PERIOD_MS)
        runCurrent()
        assertEquals(4, runs)
        // L'échec ponctuel publie une liste vide, la boucle continue.
        assertEquals(listOf(listOf("r1"), emptyList(), listOf("r3"), listOf("r4")), seen.map { l -> l.map { it.id } })

        job.cancel()
        advanceTimeBy(10 * DEFAULT_PERIOD_MS)
        runCurrent()
        assertEquals(4, runs)
    }

    @Test
    fun `periodic stops when cancelled during a discovery`() = runTest {
        var started = 0
        var finished = 0
        val job = backgroundScope.launch {
            periodic(periodMs = 1_000) {
                started++
                delay(5_000)
                finished++
                emptyList()
            }.collect {}
        }
        advanceTimeBy(2_000)
        job.cancel()
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(1, started)
        assertEquals(0, finished)
        assertTrue(job.isCancelled)
    }

    @Test
    fun `a stray inner timeout does not silently stop the periodic source`() = runTest {
        var runs = 0
        val seen = mutableListOf<List<Device>>()
        backgroundScope.launch {
            periodic(periodMs = 1_000) {
                runs++
                // Délai interne non rattrapé : CancellationException alors que la source reste active.
                if (runs == 1) withTimeout(10) { delay(1_000) }
                listOf(device("r$runs"))
            }.collect { seen += it }
        }
        advanceTimeBy(1_500)
        runCurrent()
        assertEquals(2, runs)
        assertEquals(listOf(emptyList(), listOf("r2")), seen.map { l -> l.map { it.id } })
    }

    @Test
    fun `attempt rethrows the cancellation of its own coroutine`() = runTest {
        val job = launch {
            attempt { delay(10_000) }
            throw AssertionError("attempt aurait dû propager l'annulation")
        }
        advanceTimeBy(100)
        job.cancel(CancellationException("écran fermé"))
        job.join()
        assertTrue(job.isCancelled)
    }
}
