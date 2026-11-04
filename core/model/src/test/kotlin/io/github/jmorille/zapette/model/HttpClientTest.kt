package io.github.jmorille.zapette.model

import io.github.jmorille.zapette.testing.assertFailsWithMessage
import io.github.jmorille.zapette.testing.assertNoRequest
import io.github.jmorille.zapette.testing.awaitRequest
import io.github.jmorille.zapette.testing.release
import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HttpClientTest {
    private lateinit var a: MockWebServer
    private lateinit var b: MockWebServer
    private val client = zapetteHttpClient()

    @Before
    fun setUp() {
        a = MockWebServer().apply { start() }
        b = MockWebServer().apply { start() }
    }

    @After
    fun tearDown() {
        a.close()
        b.close()
        client.release()
    }

    private fun redirect(code: Int, location: String) =
        MockResponse.Builder().code(code).setHeader("Location", location).build()

    /** Requête authentifiée avec les jetons propres aux protocoles et un corps sensible. */
    private fun sensitivePost() = Request.Builder()
        .url(a.url("/Users/AuthenticateByName"))
        .header("X-Plex-Token", "plex-secret")
        .header("X-Emby-Token", "emby-secret")
        .header("X-Emby-Authorization", "MediaBrowser Token=\"emby-secret\"")
        .post("""{"Username":"u","Pw":"mot-de-passe"}""".toRequestBody(JSON_MEDIA_TYPE))
        .build()

    @Test
    fun `default factory keeps the historical timeouts and disables automatic redirects`() {
        assertEquals(5_000, client.connectTimeoutMillis)
        assertEquals(15_000, client.readTimeoutMillis)
        assertEquals(30_000, client.callTimeoutMillis)
        assertFalse(client.followRedirects)
        assertFalse(client.followSslRedirects)
        assertTrue(client.interceptors.any { it is SameHostRedirectInterceptor })
    }

    @Test
    fun `302 to another port is refused and the target receives nothing`() = runBlocking {
        a.enqueue(redirect(302, b.url("/vol").toString()))
        b.enqueue(MockResponse.Builder().body("volé").build())
        val e = assertFailsWithMessage<RemoteException>("Redirection refusée") { client.fetchString(sensitivePost()) }
        assertIs<RedirectRefusedException>(e.cause)
        assertTrue(e.message!!.contains(b.port.toString()))
        assertEquals(1, a.requestCount)
        b.assertNoRequest()
        assertEquals(0, b.requestCount)
    }

    @Test
    fun `307 to another host never replays headers nor body`() = runBlocking {
        // Autre nom d'hôte pour la même machine : un hôte différent au sens de la politique.
        val otherHost = if (b.hostName == "localhost") "127.0.0.1" else "localhost"
        val target = b.url("/vol").newBuilder().host(otherHost).build()
        a.enqueue(redirect(307, target.toString()))
        b.enqueue(MockResponse.Builder().body("volé").build())
        val e = assertFailsWithMessage<RemoteException>("hôte ou port différent") { client.fetchString(sensitivePost()) }
        assertTrue(e.message!!.contains(otherHost))
        val first = a.awaitRequest()
        assertEquals("plex-secret", first.headers["X-Plex-Token"])
        b.assertNoRequest()
        assertEquals(0, b.requestCount)
    }

    @Test
    fun `refusal also applies to direct execute calls as an IOException`() {
        a.enqueue(redirect(308, b.url("/").toString()))
        assertFailsWithMessage<RedirectRefusedException>("Redirection refusée") {
            client.newCall(Request.Builder().url(a.url("/")).build()).execute().close()
        }
        assertEquals(0, b.requestCount)
    }

    @Test
    fun `same host redirect is followed and keeps method body and headers on 307`() = runBlocking {
        a.enqueue(redirect(307, "/nouveau"))
        a.enqueue(MockResponse.Builder().body("ok").build())
        assertEquals("ok", client.fetchString(sensitivePost()))
        a.awaitRequest()
        val second = a.awaitRequest()
        assertEquals("/nouveau", second.url.encodedPath)
        assertEquals("POST", second.method)
        assertEquals("plex-secret", second.headers["X-Plex-Token"])
        assertTrue(second.body!!.utf8().contains("mot-de-passe"))
    }

    @Test
    fun `302 on POST is followed as a GET without body`() = runBlocking {
        a.enqueue(redirect(302, a.url("/fin").toString()))
        a.enqueue(MockResponse.Builder().body("ok").build())
        assertEquals("ok", client.fetchString(sensitivePost()))
        a.awaitRequest()
        val second = a.awaitRequest()
        assertEquals("GET", second.method)
        assertEquals(0L, second.bodySize)
        assertNull(second.headers["Content-Type"])
    }

    @Test
    fun `redirect loops are bounded`() = runBlocking {
        a.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) =
                MockResponse.Builder().code(302).setHeader("Location", "/boucle").build()
        }
        val http = zapetteHttpClient(maxRedirects = 3)
        try {
            assertFailsWithMessage<RemoteException>("Trop de redirections") {
                http.fetchString(Request.Builder().url(a.url("/")).build())
            }
            // Requête initiale + 3 redirections suivies, puis arrêt.
            assertEquals(4, a.requestCount)
        } finally {
            http.release()
        }
    }

    @Test
    fun `redirect without Location is returned as is`() = runBlocking {
        a.enqueue(MockResponse.Builder().code(302).build())
        val e = assertFailsWith<HttpStatusException> { client.fetchString(Request.Builder().url(a.url("/")).build()) }
        assertEquals(302, e.code)
    }

    @Test
    fun `one shot bodies are not replayed`() {
        a.enqueue(redirect(307, "/autre"))
        val oneShot = object : okhttp3.RequestBody() {
            override fun contentType() = "text/plain".toMediaType()
            override fun isOneShot() = true
            override fun writeTo(sink: okio.BufferedSink) {
                sink.writeUtf8("une fois")
            }
        }
        val response = client.newCall(Request.Builder().url(a.url("/")).post(oneShot).build()).execute()
        response.use { assertEquals(307, it.code) }
        assertEquals(1, a.requestCount)
    }

    @Test
    fun `same origin rules`() {
        fun same(from: String, to: String) = SameHostRedirectInterceptor.isSameOrigin(from.toHttpUrl(), to.toHttpUrl())
        assertTrue(same("http://nas:8096/a", "http://nas:8096/b"))
        assertTrue(same("http://nas:8096/a", "https://nas:8920/b"))
        assertTrue(same("http://NAS/a", "http://nas/b"))
        assertFalse(same("http://nas:8096/a", "http://nas:8097/b"))
        assertFalse(same("https://nas/a", "http://nas/b"))
        assertFalse(same("http://nas/a", "http://evil/b"))
        assertFalse(same("http://nas/a", "https://nas.evil/b"))
    }

    @Test
    fun `negative redirect limit is rejected`() {
        assertFailsWithMessage<IllegalArgumentException>("maxRedirects") { SameHostRedirectInterceptor(-1) }
    }

    @Test
    fun `builder extension applies the same policy`() {
        val http = OkHttpClient.Builder().sameHostRedirectsOnly(2).build()
        assertFalse(http.followRedirects)
        assertEquals(1, http.interceptors.count { it is SameHostRedirectInterceptor })
        assertEquals(0, TimeUnit.MILLISECONDS.toSeconds(http.callTimeoutMillis.toLong()))
    }
}
