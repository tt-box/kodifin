package io.github.ttbox.kodifin.model

import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Dns
import okhttp3.OkHttpClient
import okio.Buffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.CopyOnWriteArrayList

/** Politique d'hôte des pochettes : appareil contrôlé ou LAN uniquement, jamais d'hôte public. */
class ImageHostPolicyTest {
    private fun ip(literal: String): InetAddress = InetAddress.getByName(literal)

    /** Résolveur factice qui journalise les noms demandés. */
    private class FakeResolver(private val table: Map<String, List<String>>) : HostResolver {
        val asked = CopyOnWriteArrayList<String>()
        override fun invoke(host: String): List<InetAddress> {
            asked += host
            return table[host]?.map { InetAddress.getByName(it) } ?: throw UnknownHostException(host)
        }
    }

    private fun image() = MockResponse.Builder().addHeader("Content-Type", "image/png").body(Buffer().write(byteArrayOf(4, 2))).build()

    @Test
    fun `device host is always allowed, without resolution`() {
        val resolver = FakeResolver(emptyMap())
        assertEquals(emptyList<InetAddress>(), imageHostAddresses("192.168.1.9", "192.168.1.9", resolver))
        assertEquals(emptyList<InetAddress>(), imageHostAddresses("TV.local", "tv.local", resolver))
        assertEquals(emptyList<InetAddress>(), imageHostAddresses("::1", "[::1]", resolver))
        // Boucle locale : seulement si c'est l'appareil.
        assertEquals(emptyList<InetAddress>(), imageHostAddresses("127.0.0.1", "127.0.0.1", resolver))
        assertTrue(resolver.asked.isEmpty())
    }

    @Test
    fun `lan hosts are allowed only when every address is private`() {
        val resolver = FakeResolver(
            mapOf(
                "nas.lan" to listOf("192.168.1.20"),
                "nas.example.org" to listOf("10.0.0.2", "fd00::2"),
                "mixte.example.org" to listOf("10.0.0.2", "8.8.8.8"),
                "i.ytimg.com" to listOf("142.250.74.110", "2a00:1450:4007::6e"),
                "boucle.example" to listOf("127.0.0.1"),
                "vide.example" to emptyList(),
            ),
        )
        val device = "192.168.1.9"
        assertEquals(listOf(ip("192.168.1.20")), imageHostAddresses("nas.lan", device, resolver))
        assertEquals(listOf(ip("10.0.0.2"), ip("fd00::2")), imageHostAddresses("nas.example.org", device, resolver))
        assertNull("hôte mixte public/privé", imageHostAddresses("mixte.example.org", device, resolver))
        assertNull("CDN public", imageHostAddresses("i.ytimg.com", device, resolver))
        assertNull("boucle hors appareil", imageHostAddresses("boucle.example", device, resolver))
        assertNull(imageHostAddresses("vide.example", device, resolver))
        assertNull("irrésolu", imageHostAddresses("inconnu.example", device, resolver))
    }

    @Test
    fun `literal addresses are judged without dns`() {
        val resolver = FakeResolver(emptyMap())
        val device = "192.168.1.9"
        listOf("10.1.2.3", "172.16.0.4", "192.168.0.7", "169.254.3.3", "fe80::1", "fd12::5", "[fd12::5]", "::ffff:192.168.1.5")
            .forEach { assertEquals(it, listOf(ip(it.trim('[', ']'))), imageHostAddresses(it, device, resolver)) }
        listOf("8.8.8.8", "142.250.74.110", "2a00::1", "127.0.0.1", "::1", "0.0.0.0", "::ffff:8.8.8.8", "172.32.0.1")
            .forEach { assertNull("« $it » doit être refusée", imageHostAddresses(it, device, resolver)) }
        // Formes numériques ambiguës : jamais transmises au résolveur.
        listOf("134744072", "0x08080808", "8.8.2056", "010.0.0.1", "", "a..b", "-x")
            .forEach { assertNull("« $it » doit être refusée", imageHostAddresses(it, device, resolver)) }
        assertTrue(resolver.asked.isEmpty())
    }

    @Test
    fun `public host is refused without any request`() = runTest {
        MockWebServer().use { web ->
            web.start()
            // Le DNS du client renverrait vers le serveur de test : si la politique laissait
            // passer, la requête y arriverait.
            val http = OkHttpClient.Builder().dns(Dns { listOf(InetAddress.getByName(web.hostName)) }).build()
            val resolver = FakeResolver(mapOf("i.ytimg.com" to listOf("142.250.74.110"), "mixte.example" to listOf("192.168.1.2", "1.1.1.1")))
            val device = "192.168.1.9"
            assertNull(http.fetchImage("http://i.ytimg.com:${web.port}/vi/x/hq.jpg", device, resolve = resolver))
            assertNull(http.fetchImage("http://mixte.example:${web.port}/a.jpg", device, resolve = resolver))
            assertNull(http.fetchImage("http://8.8.8.8:${web.port}/a.jpg", device, resolve = resolver))
            // Le serveur de test lui-même (boucle locale) n'est pas l'appareil : refusé aussi.
            assertNull(http.fetchImage(web.url("/a.jpg").toString(), device, resolve = resolver))
            assertEquals(0, web.requestCount)
            assertEquals(listOf("i.ytimg.com", "mixte.example"), resolver.asked.take(2))
        }
    }

    @Test
    fun `device host is fetched`() = runTest {
        MockWebServer().use { web ->
            web.enqueue(image())
            web.start()
            val url = web.url("/art.png")
            assertArrayEquals(byteArrayOf(4, 2), OkHttpClient().fetchImage(url.toString(), url.host, resolve = FakeResolver(emptyMap())))
            assertEquals(1, web.requestCount)
        }
    }

    @Test
    fun `vetted lan host is reached on its pinned addresses only`() = runTest {
        MockWebServer().use { web ->
            web.enqueue(image())
            web.start()
            // Le DNS du client ne connaît pas « nas.lan » : seule l'adresse épinglée est utilisée.
            val http = OkHttpClient.Builder().dns(Dns { throw UnknownHostException(it) }).build()
            val pinned = listOf(InetAddress.getByName(web.hostName))
            val bytes = http.fetchVettedImage("http://nas.lan:${web.port}/cover.png", DEFAULT_MAX_BODY_BYTES) {
                if (it == "nas.lan") pinned else null
            }
            assertArrayEquals(byteArrayOf(4, 2), bytes)
            assertEquals("nas.lan:${web.port}", web.takeRequest().headers["Host"])
            assertNull(http.fetchVettedImage("http://autre.lan:${web.port}/x.png", DEFAULT_MAX_BODY_BYTES) { null })
            assertEquals(1, web.requestCount)
        }
    }

    @Test
    fun `plex local host rules are unchanged`() {
        assertTrue(LocalNetwork.isLocalHost("127.0.0.1"))
        assertTrue(LocalNetwork.isLocalHost("salon.local"))
        assertFalse(LocalNetwork.isLocalHost("salon.local."))
        assertFalse(LocalNetwork.isLocalHost("8.8.8.8"))
        assertFalse(LocalNetwork.isLocalAddress(ip("127.0.0.1"), allowLoopback = false))
        assertTrue(LocalNetwork.isLocalAddress(ip("::ffff:10.0.0.1"), allowLoopback = false))
    }
}
