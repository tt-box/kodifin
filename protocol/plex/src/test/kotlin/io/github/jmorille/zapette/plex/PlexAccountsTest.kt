package io.github.jmorille.zapette.plex

import io.github.jmorille.zapette.model.Device
import io.github.jmorille.zapette.model.DeviceKind
import io.github.jmorille.zapette.model.HttpStatusException
import io.github.jmorille.zapette.model.RemoteException
import io.github.jmorille.zapette.model.RemoteKey
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/** Plex Home (choix du profil) et lecteur joint en HTTPS. */
class PlexAccountsTest {
    private val identity = PlexIdentity("zapette-test")
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() = server.close()

    private fun account() = PlexAccount(OkHttpClient(), identity, server.url("/").toString().trimEnd('/'))

    private fun json(body: String, code: Int = 200) =
        MockResponse.Builder().code(code).addHeader("Content-Type", "application/json").body(body).build()

    @Test
    fun `home users are listed with the account token`() = runTest {
        server.enqueue(
            json(
                """{"id":1,"name":"Famille","users":[
                  {"id":11,"uuid":"aa11","title":"Papa","username":"papa","protected":true,"admin":true},
                  {"id":12,"uuid":"bb22","title":"","username":"","protected":false,"restricted":true},
                  {"id":13,"uuid":"","title":"Fantôme"}]}""",
            ),
        )
        val users = account().homeUsers("tok-compte")

        assertEquals(
            listOf(
                PlexHomeUser(11, "aa11", "Papa", "papa", isProtected = true, admin = true),
                PlexHomeUser(12, "bb22", restricted = true),
            ),
            users,
        )
        assertEquals(listOf("Papa", "Utilisateur 12"), users.map { it.displayName })
        val request = server.next()
        assertEquals("GET", request.method)
        assertEquals("/api/v2/home/users", request.url.encodedPath)
        assertEquals("tok-compte", request.headers["X-Plex-Token"])
        assertEquals("zapette-test", request.headers["X-Plex-Client-Identifier"])
    }

    @Test
    fun `home users also accept a bare array`() = runTest {
        server.enqueue(json("""[{"id":5,"uuid":"cc33","title":"Salon"}]"""))
        assertEquals(listOf("cc33"), account().homeUsers("t").map { it.uuid })
    }

    @Test
    fun `switch returns the profile token and sends the pin only when given`() = runTest {
        server.enqueue(json("""{"id":11,"uuid":"aa11","authToken":"tok-papa"}"""))
        server.enqueue(json("""{"id":12,"uuid":"bb22","authenticationToken":"tok-enfant"}"""))
        val account = account()

        assertEquals("tok-papa", account.switchHomeUser("tok-compte", "aa11", "1234"))
        assertEquals("tok-enfant", account.switchHomeUser("tok-compte", "bb22", null))

        val first = server.next()
        assertEquals("POST", first.method)
        assertEquals("/api/v2/home/users/aa11/switch", first.url.encodedPath)
        assertEquals("1234", first.url.queryParameter("pin"))
        assertEquals("tok-compte", first.headers["X-Plex-Token"])
        val second = server.next()
        assertEquals("/api/v2/home/users/bb22/switch", second.url.encodedPath)
        assertNull(second.url.queryParameter("pin"))
    }

    @Test
    fun `uuid cannot escape its path segment`() = runTest {
        server.enqueue(json("""{"authToken":"t"}"""))
        account().switchHomeUser("tok", "../../pins/1", null)
        assertEquals("/api/v2/home/users/..%2F..%2Fpins%2F1/switch", server.next().url.encodedPath)
    }

    @Test
    fun `refused pin is reported without echoing it`() = runTest {
        server.enqueue(json("""{"errors":[{"code":1041}]}""", code = 401))
        val e = expectFailure<PlexPinRefusedException> { account().switchHomeUser("tok", "aa11", "9876") }
        assertEquals("Code PIN refusé", e.message)
        assertFalse(e.message!!.contains("9876"))
    }

    @Test
    fun `other switch failures and missing tokens are reported`() = runTest {
        server.enqueue(json("{}", code = 500))
        server.enqueue(json("""{"id":11}"""))
        val e = expectFailure<HttpStatusException> { account().switchHomeUser("tok", "aa11", null) }
        assertEquals(500, e.code)
        val missing = expectFailure<RemoteException> { account().switchHomeUser("tok", "aa11", null) }
        assertEquals("Jeton du profil absent de la réponse de plex.tv", missing.message)
    }

    @Test
    fun `tokens never appear in toString`() {
        assertFalse(PlexPin(1, "ABCD", "secret-pin").toString().contains("secret-pin"))
        val resource = PlexResource("Salon", "c1", accessToken = "secret-res").toString()
        assertFalse(resource.contains("secret-res"))
        assertTrue(resource.contains("Salon"))
    }

    // --- Lecteur en HTTPS : le client HTTP est fourni par l'appelant, le jeton reste local.

    private class Capture : Interceptor {
        val requests = CopyOnWriteArrayList<Request>()

        override fun intercept(chain: Interceptor.Chain): Response {
            requests += chain.request()
            return Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("".toResponseBody()).build()
        }
    }

    private suspend fun sent(host: String, extras: Map<String, String>): Request {
        val capture = Capture()
        val http = OkHttpClient.Builder().addInterceptor(capture).build()
        val device = Device("PLEX:abc", "Lecteur", DeviceKind.PLEX, host, 32500, extras + ("clientIdentifier" to "abc"))
        PlexController(device, http, identity).send(RemoteKey.SELECT)
        return capture.requests.single()
    }

    @Test
    fun `https scheme targets https with the caller client`() = runTest {
        val request = sent("192.168.1.20", mapOf("scheme" to "https", "token" to "secret"))
        assertEquals("https://192.168.1.20:32500/player/navigation/select", request.url.toString().substringBefore('?'))
        assertEquals("secret", request.header("X-Plex-Token"))
    }

    @Test
    fun `https does not relax the local host rule for the token`() = runTest {
        for (host in listOf("plex.example.com", "8.8.8.8", "2001:db8::1")) {
            val request = sent(host, mapOf("scheme" to "https", "token" to "secret"))
            assertTrue("schéma https attendu pour $host", request.url.isHttps)
            assertNull("jeton envoyé à $host", request.header("X-Plex-Token"))
        }
        val v6 = sent("fd00::5", mapOf("scheme" to "HTTPS", "token" to "secret"))
        assertEquals("https://[fd00::5]:32500/player/navigation/select", v6.url.toString().substringBefore('?'))
        assertEquals("secret", v6.header("X-Plex-Token"))
    }

    @Test
    fun `missing or other scheme stays on http`() = runTest {
        assertEquals("http", sent("10.0.0.8", emptyMap()).url.scheme)
        assertEquals("http", sent("10.0.0.8", mapOf("scheme" to "ftp")).url.scheme)
    }
}
