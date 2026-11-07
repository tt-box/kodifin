package io.github.ttbox.kodifin.model

import io.github.ttbox.kodifin.testing.assertFailsWithReason
import io.github.ttbox.kodifin.testing.awaitRequest
import io.github.ttbox.kodifin.testing.release
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.SocketEffect
import okhttp3.Call
import okhttp3.Callback
import okhttp3.EventListener
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.asResponseBody
import okio.Buffer
import okio.ForwardingSource
import okio.buffer
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.lang.reflect.Proxy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class HttpTest {
    private lateinit var server: MockWebServer
    private val client = OkHttpClient()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.close()
        client.release()
    }

    private fun request() = Request.Builder().url(server.url("/x")).build()

    @Test
    fun `fetchString returns the body on 2xx`() = runBlocking {
        server.enqueue(MockResponse.Builder().body("ok").build())
        assertEquals("ok", client.fetchString(request()))
        assertEquals("/x", server.awaitRequest().url.encodedPath)
    }

    @Test
    fun `fetchString honours the declared charset and strips a UTF-8 BOM`() = runBlocking {
        server.enqueue(
            MockResponse.Builder()
                .body(Buffer().write(byteArrayOf(0xE9.toByte())))
                .setHeader("Content-Type", "text/plain; charset=ISO-8859-1")
                .build(),
        )
        server.enqueue(
            MockResponse.Builder()
                .body(Buffer().write(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())).writeUtf8("été"))
                .build(),
        )
        assertEquals("é", client.fetchString(request()))
        assertEquals("été", client.fetchString(request()))
    }

    @Test
    fun `fetchString maps non 2xx to HttpStatusException`() = runBlocking {
        server.enqueue(MockResponse.Builder().code(401).body("refusé").build())
        val e = assertFailsWith<HttpStatusException> { client.fetchString(request()) }
        assertEquals(RemoteError.Http(401), e.reason)
        assertEquals(401, e.code)
    }

    @Test
    fun `fetchString wraps a truncated body in RemoteException`() = runBlocking {
        server.enqueue(
            MockResponse.Builder()
                .body("x".repeat(64 * 1024))
                .onResponseBody(SocketEffect.ShutdownConnection)
                .build(),
        )
        val e = assertFailsWithReason(RemoteError.Interrupted("localhost")) { client.fetchString(request()) }
        assertFalse(e is HttpStatusException)
        assertIs<java.io.IOException>(e.cause)
        Unit
    }

    @Test
    fun `fetchString wraps connection failures in RemoteException`() = runBlocking {
        val url = server.url("/x")
        server.close()
        val e = assertFailsWithReason(RemoteError.Unreachable(url.host)) {
            client.fetchString(Request.Builder().url(url).build())
        }
        assertIs<java.io.IOException>(e.cause)
        Unit
    }

    @Test
    fun `body larger than the default limit is rejected`() = runBlocking {
        server.enqueue(MockResponse.Builder().body(Buffer().write(ByteArray((DEFAULT_MAX_BODY_BYTES + 1).toInt()))).build())
        assertFailsWithReason(RemoteError.ResponseTooLarge("localhost", DEFAULT_MAX_BODY_BYTES)) { client.fetchString(request()) }
        Unit
    }

    @Test
    fun `body limit is configurable and inclusive`() = runBlocking {
        server.enqueue(MockResponse.Builder().body("0123456789").build())
        server.enqueue(MockResponse.Builder().body("0123456789A").build())
        assertEquals("0123456789", client.fetchString(request(), maxBodyBytes = 10))
        assertFailsWithReason(RemoteError.ResponseTooLarge("localhost", 10)) { client.fetchString(request(), maxBodyBytes = 10) }
        Unit
    }

    @Test
    fun `chunked body without Content-Length is still bounded`() = runBlocking {
        // Pas d'en-tête Content-Length : seule la lecture bornée protège la mémoire.
        server.enqueue(MockResponse.Builder().chunkedBody("y".repeat(10_000), 512).build())
        assertFailsWithReason(RemoteError.ResponseTooLarge("localhost", 1_000)) { client.fetchString(request(), maxBodyBytes = 1_000) }
        Unit
    }

    @Test
    fun `cancelling during the call cancels the OkHttp call`() = runBlocking {
        val canceled = CountDownLatch(1)
        val http = OkHttpClient.Builder()
            .eventListener(object : EventListener() {
                override fun canceled(call: Call) = canceled.countDown()
            })
            .build()
        // Réponse retenue : l'appel reste en cours tant que le test ne l'annule pas.
        server.enqueue(MockResponse.Builder().headersDelay(30, TimeUnit.SECONDS).body("tard").build())
        try {
            // Démarrage immédiat : awaitRequest() bloque le fil de runBlocking.
            val job = async(start = CoroutineStart.UNDISPATCHED) { http.fetchString(request()) }
            server.awaitRequest()
            job.cancel()
            assertFailsWith<CancellationException> { job.await() }
            assertTrue(canceled.await(2, TimeUnit.SECONDS), "Call.cancel() non appelé")
        } finally {
            http.release()
        }
    }

    @Test
    fun `response delivered after cancellation is closed`() = runBlocking {
        var callback: Callback? = null
        var cancelled = false
        val request = request()
        val call = Proxy.newProxyInstance(Call::class.java.classLoader, arrayOf(Call::class.java)) { _, method, args ->
            when (method.name) {
                "enqueue" -> callback = args[0] as Callback
                "cancel" -> cancelled = true
                "request" -> return@newProxyInstance request
                "isCanceled" -> return@newProxyInstance cancelled
                "hashCode" -> return@newProxyInstance 0
                "equals" -> return@newProxyInstance false
                "toString" -> return@newProxyInstance "FakeCall"
            }
            null
        } as Call

        val job = async(start = CoroutineStart.UNDISPATCHED) { call.await() }
        assertNotNull(callback, "enqueue non appelé")
        job.cancel()
        assertTrue(cancelled, "Call.cancel() non appelé à l'annulation")

        var bodyClosed = false
        val source = object : ForwardingSource(Buffer().writeUtf8("corps")) {
            override fun close() {
                bodyClosed = true
                super.close()
            }
        }
        val response = Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body(source.buffer().asResponseBody("text/plain".toMediaType(), 5))
            .build()
        callback!!.onResponse(call, response)
        assertTrue(bodyClosed, "réponse livrée après annulation non fermée")
        assertFailsWith<CancellationException> { withTimeout(1_000) { job.await() } }
        Unit
    }
}
