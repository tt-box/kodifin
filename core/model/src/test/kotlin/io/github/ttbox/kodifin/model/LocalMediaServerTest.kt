package io.github.ttbox.kodifin.model

import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.net.InetAddress
import java.net.Socket

/** Serveur de partage local : fichiers publiés seulement, requêtes partielles, en-têtes DLNA. */
class LocalMediaServerTest {
    private val server = LocalMediaServer()
    private val http = OkHttpClient()
    private val data = ByteArray(1000) { (it % 251).toByte() }
    private val opened = mutableListOf<Long>()

    private fun publish(length: Long = data.size.toLong(), name: String = "Mon film été.mkv"): String {
        server.start(InetAddress.getLoopbackAddress())
        return server.publish(
            LocalMedia(name, "video/x-matroska", length) { offset ->
                opened += offset
                ByteArrayInputStream(data, offset.toInt(), data.size - offset.toInt())
            },
        )
    }

    @After
    fun tearDown() = server.stop()

    private fun get(url: String, range: String? = null, method: String = "GET") = http.newCall(
        Request.Builder().url(url).method(method, null).apply { range?.let { header("Range", it) } }.build(),
    ).execute()

    @Test
    fun `whole file is served with dlna headers and an encoded name`() {
        val url = publish()
        assertTrue(url.matches(Regex("http://127\\.0\\.0\\.1:\\d+/m/[0-9a-f]{32}/Mon%20film%20%C3%A9t%C3%A9\\.mkv")))
        get(url).use {
            assertEquals(200, it.code)
            assertEquals("video/x-matroska", it.header("Content-Type"))
            assertEquals("bytes", it.header("Accept-Ranges"))
            assertEquals("Streaming", it.header("transferMode.dlna.org"))
            assertTrue(it.header("contentFeatures.dlna.org")!!.startsWith("DLNA.ORG_OP=01"))
            assertArrayEquals(data, it.body.bytes())
        }
        assertEquals(1, server.publishedCount)
    }

    @Test
    fun `ranges seek into the file`() {
        val url = publish()
        get(url, "bytes=100-199").use {
            assertEquals(206, it.code)
            assertEquals("bytes 100-199/1000", it.header("Content-Range"))
            assertArrayEquals(data.copyOfRange(100, 200), it.body.bytes())
        }
        get(url, "bytes=990-").use { assertArrayEquals(data.copyOfRange(990, 1000), it.body.bytes()) }
        get(url, "bytes=-5").use { assertArrayEquals(data.copyOfRange(995, 1000), it.body.bytes()) }
        get(url, "bytes=5000-").use {
            assertEquals(416, it.code)
            assertEquals("bytes */1000", it.header("Content-Range"))
        }
        assertEquals(listOf(100L, 990L, 995L), opened)
    }

    @Test
    fun `head answers without a body and unknown length disables ranges`() {
        get(publish(), method = "HEAD").use {
            assertEquals(200, it.code)
            assertEquals("1000", it.header("Content-Length"))
        }
        assertTrue(opened.isEmpty())
        val unknown = publish(length = -1, name = "flux.mp3")
        get(unknown, "bytes=10-").use {
            assertEquals(200, it.code)
            assertNull(it.header("Accept-Ranges"))
            assertArrayEquals(data, it.body.bytes())
        }
    }

    @Test
    fun `only published tokens are served`() {
        val url = publish()
        val base = url.substringBefore("/m/")
        get("$base/m/${"0".repeat(32)}/x.mkv").use { assertEquals(404, it.code) }
        get("$base/etc/passwd").use { assertEquals(404, it.code) }
        get(url, method = "DELETE").use { assertEquals(405, it.code) }
        server.unpublish(url)
        get(url).use { assertEquals(404, it.code) }
    }

    @Test
    fun `malformed requests are refused`() {
        val url = publish()
        val port = url.substringAfter("127.0.0.1:").substringBefore('/').toInt()
        Socket(InetAddress.getLoopbackAddress(), port).use { s ->
            s.getOutputStream().write("BONJOUR\r\n\r\n".toByteArray())
            assertTrue(s.getInputStream().bufferedReader().readLine().startsWith("HTTP/1.1 400"))
        }
    }

    @Test
    fun `stop forgets everything and publish then fails`() {
        publish()
        server.stop()
        assertFalse(server.isRunning)
        assertEquals(0, server.publishedCount)
        try {
            server.publish(LocalMedia("a.mp4", "video/mp4", 1) { ByteArrayInputStream(ByteArray(1)) })
            throw AssertionError("publication refusée attendue")
        } catch (_: IllegalStateException) {
        }
    }

    @Test
    fun `range parsing and helpers`() {
        assertEquals(0L..9L, LocalMediaServer.parseRange("bytes=0-9", 100))
        assertEquals(10L..99L, LocalMediaServer.parseRange("bytes=10-500", 100))
        assertEquals(0L..99L, LocalMediaServer.parseRange("bytes=-500", 100))
        assertNull(LocalMediaServer.parseRange("bytes=0-1,5-6", 100))
        assertNull(LocalMediaServer.parseRange("octets=0-1", 100))
        assertNull(LocalMediaServer.parseRange("bytes=9-3", 100))
        assertNull(LocalMediaServer.parseRange("bytes=x-", 100))
        assertNull(LocalMediaServer.parseRange("bytes=-0", 100))
        assertNull(LocalMediaServer.parseRange("bytes=0-", 0))
        assertEquals("a_b.mp4", LocalMediaServer.encodeName("a/b.mp4"))
        assertEquals("media", LocalMediaServer.encodeName("  "))
        assertNull(LocalMediaServer.tokenOf("http://h/autre"))
    }

    @Test
    fun `fetch image accepts bounded images only`() = runTest {
        MockWebServer().use { web ->
            web.enqueue(MockResponse.Builder().addHeader("Content-Type", "image/png").body(Buffer().write(byteArrayOf(1, 2))).build())
            web.enqueue(MockResponse.Builder().addHeader("Content-Type", "text/html").body("x").build())
            web.enqueue(MockResponse.Builder().code(404).build())
            web.enqueue(MockResponse.Builder().addHeader("Content-Type", "image/png").body(Buffer().write(ByteArray(50))).build())
            web.start()
            assertArrayEquals(byteArrayOf(1, 2), http.fetchImage(web.url("/a.png").toString()))
            assertNull(http.fetchImage(web.url("/b").toString()))
            assertNull(http.fetchImage(web.url("/c").toString()))
            assertNull(http.fetchImage(web.url("/d").toString(), maxBytes = 10))
            assertNull(http.fetchImage("pas une url"))
        }
        assertNull(http.fetchImage("http://127.0.0.1:1/injoignable.png"))
    }
}
