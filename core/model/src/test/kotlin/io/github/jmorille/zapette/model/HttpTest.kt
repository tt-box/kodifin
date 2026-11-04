package io.github.jmorille.zapette.model

import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.SocketEffect
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

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
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }

    private fun request() = Request.Builder().url(server.url("/x")).build()

    @Test
    fun `fetchString returns the body on 2xx`() = runBlocking {
        server.enqueue(MockResponse.Builder().body("ok").build())
        assertEquals("ok", client.fetchString(request()))
    }

    @Test
    fun `fetchString maps non 2xx to HttpStatusException`() = runBlocking {
        server.enqueue(MockResponse.Builder().code(401).body("refusé").build())
        try {
            client.fetchString(request())
            fail("exception attendue")
        } catch (e: HttpStatusException) {
            assertEquals(401, e.code)
        }
    }

    @Test
    fun `fetchString wraps a truncated body in RemoteException`() = runBlocking {
        server.enqueue(
            MockResponse.Builder()
                .body("x".repeat(64 * 1024))
                .onResponseBody(SocketEffect.ShutdownConnection)
                .build(),
        )
        try {
            client.fetchString(request())
            fail("exception attendue")
        } catch (e: RemoteException) {
            assertTrue(e !is HttpStatusException)
        }
    }

    @Test
    fun `fetchString wraps connection failures in RemoteException`() = runBlocking {
        val url = server.url("/x")
        server.close()
        try {
            client.fetchString(Request.Builder().url(url).build())
            fail("exception attendue")
        } catch (e: RemoteException) {
            assertTrue(e.message!!.contains(url.host))
        }
    }
}
