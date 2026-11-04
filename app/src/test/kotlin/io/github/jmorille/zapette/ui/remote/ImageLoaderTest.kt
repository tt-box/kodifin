package io.github.jmorille.zapette.ui.remote

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ImageLoaderTest {
    private val dispatcher = StandardTestDispatcher()

    /** Faux décodeur : l'« image » est le texte des octets, sa taille sa longueur. */
    private object TextDecoder : ImageDecoder<String> {
        override fun decode(bytes: ByteArray, targetWidthPx: Int): String? =
            String(bytes).takeIf { it != "illisible" }
        override fun sizeOf(image: String) = image.length
    }

    private class Server {
        val requests = mutableListOf<Pair<String, Int>>()
        var gate: CompletableDeferred<Unit>? = null
        var cancelled = 0
        var failure: Exception? = null
        suspend fun fetch(key: String, width: Int): ByteArray? {
            requests += key to width
            failure?.let { throw it }
            try {
                gate?.await()
            } catch (e: kotlinx.coroutines.CancellationException) {
                cancelled++
                throw e
            }
            return if (key == "absente") null else "img:$key".toByteArray()
        }
    }

    private fun TestScope.loader(server: Server, maxBytes: Long = 1_000) =
        ImageLoader(backgroundScope, dispatcher, TextDecoder, maxBytes) { k, w -> server.fetch(k, w) }

    @Test
    fun `second load is served from memory cache`() = runTest(dispatcher) {
        val server = Server()
        val loader = loader(server)
        assertEquals("img:a", loader.load("a", 200))
        assertEquals("img:a", loader.load("a", 200))
        assertEquals("img:a", loader.cached("a", 230))
        // Largeur demandée arrondie au palier supérieur (partage entre cellules proches).
        assertEquals(listOf("a" to 240), server.requests)
        assertEquals(0, loader.inFlightCount)
    }

    @Test
    fun `concurrent loads of the same image share one request`() = runTest(dispatcher) {
        val server = Server().apply { gate = CompletableDeferred() }
        val loader = loader(server)
        val first = async { loader.load("a", 300) }
        val second = async { loader.load("a", 310) }
        runCurrent()
        assertEquals(1, server.requests.size)
        assertEquals(1, loader.inFlightCount)
        server.gate!!.complete(Unit)
        assertEquals("img:a", first.await())
        assertEquals("img:a", second.await())
        assertEquals(1, server.requests.size)
        assertEquals(0, loader.inFlightCount)
    }

    @Test
    fun `request is cancelled when its only waiter leaves the screen`() = runTest(dispatcher) {
        val server = Server().apply { gate = CompletableDeferred() }
        val loader = loader(server)
        val job = async { loader.load("a", 200) }
        runCurrent()
        job.cancel()
        runCurrent()
        assertEquals(1, server.cancelled)
        assertEquals(0, loader.inFlightCount)
        assertNull(loader.cached("a", 200))
    }

    @Test
    fun `request survives while another waiter still needs it`() = runTest(dispatcher) {
        val server = Server().apply { gate = CompletableDeferred() }
        val loader = loader(server)
        val leaving = async { loader.load("a", 200) }
        val staying = async { loader.load("a", 200) }
        runCurrent()
        leaving.cancel()
        runCurrent()
        assertEquals(0, server.cancelled)
        server.gate!!.complete(Unit)
        assertEquals("img:a", staying.await())
    }

    @Test
    fun `missing or failing images are not requested again`() = runTest(dispatcher) {
        val server = Server()
        val loader = loader(server)
        assertNull(loader.load("absente", 200))
        assertNull(loader.load("absente", 200))
        server.failure = IllegalStateException("réseau")
        assertNull(loader.load("b", 200))
        assertNull(loader.load("b", 200))
        assertEquals(listOf("absente" to 240, "b" to 240), server.requests)
        // Reconnexion : tout est oublié, l'image est redemandée.
        loader.clear()
        server.failure = null
        assertEquals("img:b", loader.load("b", 200))
    }

    @Test
    fun `memory cache is bounded by size and evicts the least recently used`() = runTest(dispatcher) {
        val server = Server()
        // Chaque image fait 5 octets (« img:x ») : le cache en garde deux.
        val loader = loader(server, maxBytes = 10)
        loader.load("a", 100)
        loader.load("b", 100)
        loader.cached("a", 100) // « a » redevient récent.
        loader.load("c", 100)
        assertEquals("img:a", loader.cached("a", 100))
        assertNull(loader.cached("b", 100))
        assertEquals("img:c", loader.cached("c", 100))
        assertEquals(2, loader.cachedCount)
    }

    @Test
    fun `lru cache tracks size and drops values larger than its capacity`() {
        val cache = SizedLruCache<String, String>(6) { it.length }
        cache.put("x", "abc")
        cache.put("y", "def")
        assertEquals(6, cache.currentSize)
        cache.put("x", "a")
        assertEquals(4, cache.currentSize)
        cache.put("z", "trop-grand")
        assertNull(cache["z"])
        assertTrue(cache.currentSize <= 6)
        cache.clear()
        assertEquals(0, cache.count)
    }

    @Test
    fun `width buckets and sample size`() {
        assertEquals(120, ImageLoader.bucket(1))
        assertEquals(480, ImageLoader.bucket(400))
        assertEquals(960, ImageLoader.bucket(5_000))
        assertEquals(1, BitmapImageDecoder.sampleSize(300, 240))
        assertEquals(4, BitmapImageDecoder.sampleSize(1_000, 200))
        assertEquals(8, BitmapImageDecoder.sampleSize(2_000, 240))
        assertFalse(BitmapImageDecoder.sampleSize(500, 0) > 1)
    }

    @Test
    fun `unreadable bytes give no image`() = runTest(dispatcher) {
        val loader = ImageLoader(backgroundScope, dispatcher, TextDecoder, 100) { _, _ -> "illisible".toByteArray() }
        assertNull(loader.load("x", 100))
        assertEquals(0, loader.inFlightCount)
    }
}
