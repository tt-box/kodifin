package io.github.ttbox.kodifin.airplay

import io.github.ttbox.kodifin.model.Device
import io.github.ttbox.kodifin.model.DeviceKind
import io.github.ttbox.kodifin.model.RemoteError
import io.github.ttbox.kodifin.model.RemoteKey
import io.github.ttbox.kodifin.testing.assertFailsWithReason
import io.github.ttbox.kodifin.testing.awaitRequest
import io.github.ttbox.kodifin.testing.release
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Mot de passe AirPlay 1 : défi HTTP Digest, realm et utilisateur « AirPlay ». */
class AirPlayAuthTest {
    private lateinit var server: MockWebServer
    private val http = OkHttpClient.Builder().retryOnConnectionFailure(false).build()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.close()
        http.release()
    }

    private fun challenge(nonce: String, extra: String = "") = MockResponse.Builder()
        .code(401)
        .addHeader("WWW-Authenticate", "Digest realm=\"AirPlay\", nonce=\"$nonce\"$extra")
        .build()

    private fun controller(password: String?) = AirPlayController(
        Device("a", "Apple TV", DeviceKind.AIRPLAY, server.hostName, server.port, password?.let { mapOf("password" to it) } ?: emptyMap()),
        http,
        0,
    )

    private fun expectedResponse(method: String, uri: String, nonce: String, password: String): String {
        val ha1 = AirPlayDigestAuth.md5("AirPlay:AirPlay:$password")
        val ha2 = AirPlayDigestAuth.md5("$method:$uri")
        return AirPlayDigestAuth.md5("$ha1:$nonce:$ha2")
    }

    @Test
    fun `digest challenge is answered then reused for the next requests`() = runTest {
        server.enqueue(challenge("n1"))
        server.enqueue(MockResponse.Builder().build())
        server.enqueue(MockResponse.Builder().build())
        val c = controller("s3cret")
        c.send(RemoteKey.STOP)
        c.seekTo(2_000)
        assertNull(server.awaitRequest().headers["Authorization"])
        val signed = server.awaitRequest().headers["Authorization"]!!
        assertTrue(signed.startsWith("Digest username=\"AirPlay\", realm=\"AirPlay\", nonce=\"n1\", uri=\"/stop\""), signed)
        assertTrue("response=\"${expectedResponse("POST", "/stop", "n1", "s3cret")}\"" in signed, signed)
        assertFalse("s3cret" in signed)
        // Défi mémorisé : la commande suivante est signée d'emblée, requête et paramètres compris.
        val next = server.awaitRequest().headers["Authorization"]!!
        assertTrue("uri=\"/scrub?position=2.000\"" in next, next)
        assertTrue("response=\"${expectedResponse("POST", "/scrub?position=2.000", "n1", "s3cret")}\"" in next, next)
        assertEquals(3, server.requestCount)
    }

    @Test
    fun `missing password is explained`() = runTest {
        server.enqueue(challenge("n1"))
        val e = assertFailsWithReason(RemoteError.PasswordRequired) { controller(null).connect() }
        assertIs<AirPlayController.AirPlayAuthException>(e)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `wrong password is refused without retry loop nor leak`() = runTest {
        server.enqueue(challenge("n1"))
        server.enqueue(challenge("n1"))
        val e = assertFailsWithReason(RemoteError.PasswordRejected) { controller("faux-mdp").connect() }
        assertIs<AirPlayController.AirPlayAuthException>(e)
        assertFalse(generateSequence<Throwable>(e) { it.cause }.any { "faux-mdp" in it.message.orEmpty() })
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `stale nonce triggers a single new attempt`() = runTest {
        server.enqueue(challenge("n1"))
        server.enqueue(MockResponse.Builder().build())
        server.enqueue(challenge("n2", ", stale=true"))
        server.enqueue(MockResponse.Builder().build())
        val c = controller("pw")
        c.send(RemoteKey.STOP)
        c.send(RemoteKey.STOP)
        server.awaitRequest()
        assertTrue("nonce=\"n1\"" in server.awaitRequest().headers["Authorization"]!!)
        assertTrue("nonce=\"n1\"" in server.awaitRequest().headers["Authorization"]!!)
        assertTrue("nonce=\"n2\"" in server.awaitRequest().headers["Authorization"]!!)
        assertEquals(4, server.requestCount)
    }

    @Test
    fun `challenge parsing`() {
        val qop = assertNotNull(AirPlayDigestAuth.parseChallenge("Digest realm=\"AirPlay\", nonce=\"abc\", qop=\"auth\", opaque=\"o\""))
        assertEquals("abc", qop.nonce)
        assertEquals("auth", qop.qop)
        assertEquals("o", qop.opaque)
        assertNull(AirPlayDigestAuth.parseChallenge("Basic realm=\"AirPlay\""))
        assertNull(AirPlayDigestAuth.parseChallenge("Digest realm=\"AirPlay\""))
        assertNull(AirPlayDigestAuth.parseChallenge("Digest realm=\"AirPlay\", nonce=\"a\", algorithm=SHA-256"))
        assertNull(AirPlayDigestAuth.parseChallenge("Digest realm=\"Air\\Play\", nonce=\"a\""))
        assertEquals("AirPlayDigestAuth(password=***)", AirPlayDigestAuth("secret").toString())
    }
}
