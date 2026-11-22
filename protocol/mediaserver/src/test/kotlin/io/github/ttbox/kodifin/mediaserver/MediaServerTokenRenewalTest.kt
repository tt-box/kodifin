package io.github.ttbox.kodifin.mediaserver

import io.github.ttbox.kodifin.model.Device
import io.github.ttbox.kodifin.model.DeviceKind
import io.github.ttbox.kodifin.model.HttpStatusException
import io.github.ttbox.kodifin.model.RemoteError
import io.github.ttbox.kodifin.model.RemoteException
import io.github.ttbox.kodifin.model.RemoteKey
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Jeton révoqué : aucune boucle d'`AuthenticateByName` (verrouillage du compte), au plus une
 * tentative de renouvellement, puis session expirée sans plus contacter le serveur.
 */
class MediaServerTokenRenewalTest {
    private lateinit var server: MockWebServer
    private val requests = CopyOnWriteArrayList<RecordedRequest>()
    private val http = OkHttpClient()

    @Before
    fun setUp() {
        server = MockWebServer()
    }

    @After
    fun tearDown() {
        server.close()
        http.dispatcher.executorService.shutdown()
        http.connectionPool.evictAll()
    }

    private fun start(handler: (RecordedRequest) -> MockResponse) {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                return handler(request)
            }
        }
        server.start()
    }

    private fun status(code: Int) = MockResponse.Builder().code(code).build()
    private fun ok(body: String) = MockResponse.Builder().addHeader("Content-Type", "application/json").body(body).build()
    private fun authCount() = requests.count { it.url.encodedPath == "/Users/AuthenticateByName" }
    private fun RecordedRequest.token() = headers["Authorization"]?.substringAfter("Token=\"", "")?.substringBefore('"')

    private fun controller(extras: Map<String, String>): MediaServerController {
        val device = Device("s", "Serveur", DeviceKind.JELLYFIN, server.hostName, server.port, extras)
        val client = MediaServerClient(http, MediaServerController.baseUrl(device), MediaServerFlavor.JELLYFIN, "dev", extras["token"])
        return MediaServerController(device, client, pollIntervalMs = 0, ownDeviceId = "dev")
    }

    private suspend fun failure(block: suspend () -> Unit): RemoteException = expectFailure<RemoteException>(block)

    @Test
    fun `revoked token without password in memory tries the empty password only once`() = runTest {
        start { status(401) }
        val c = controller(mapOf("token" to "vieux", "userId" to "u1", "username" to "jo"))
        repeat(3) {
            val e = failure { c.playbackState().first() }
            assertEquals(RemoteError.SessionExpired, e.reason)
            assertFalse("pas d'erreur HTTP brute", e is HttpStatusException)
        }
        assertEquals("une seule tentative, pas de boucle (verrouillage du compte)", 1, authCount())
        assertEquals("""{"Username":"jo","Pw":""}""", requests.single { it.url.encodedPath == "/Users/AuthenticateByName" }.body!!.utf8())
        assertEquals("une requête refusée, un essai, puis plus rien", 2, requests.size)
    }

    @Test
    fun `revoked token without username never calls AuthenticateByName`() = runTest {
        start { status(401) }
        val c = controller(mapOf("token" to "vieux", "userId" to "u1"))
        assertEquals(RemoteError.SessionExpired, failure { c.playbackState().first() }.reason)
        assertEquals(RemoteError.SessionExpired, failure { c.playbackState().first() }.reason)
        assertEquals(0, authCount())
        assertEquals(1, requests.size)
    }

    @Test
    fun `account without password connected by stored token renews it with the empty password`() = runTest {
        start { r ->
            when {
                r.url.encodedPath == "/Users/AuthenticateByName" -> ok("""{"AccessToken":"neuf","User":{"Id":"u1"}}""")
                r.token() == "vieux" -> status(401)
                r.url.encodedPath == "/Sessions" -> ok("""[{"Id":"tv","SupportsRemoteControl":true}]""")
                else -> status(204)
            }
        }
        val c = controller(mapOf("token" to "vieux", "userId" to "u1", "username" to "Salon"))
        c.connect()
        assertEquals(listOf("/Sessions", "/Users/AuthenticateByName", "/Sessions"), requests.map { it.url.encodedPath })
        assertEquals("""{"Username":"Salon","Pw":""}""", requests[1].body!!.utf8())
        assertEquals("neuf", c.persistableExtras()["token"])
        c.send(RemoteKey.SELECT)
        assertEquals(1, authCount())
    }

    @Test
    fun `revoked token with a changed password tries a single authentication`() = runTest {
        start { status(401) }
        val c = controller(mapOf("token" to "vieux", "sessionId" to "tv", "username" to "jo", "password" to "ancien"))
        val first = failure { c.send(RemoteKey.STOP) }
        assertEquals(RemoteError.CredentialsRejected, first.reason)
        repeat(5) {
            val e = failure { c.playbackState().first() }
            assertEquals(RemoteError.SessionExpired, e.reason)
        }
        assertEquals(RemoteError.SessionExpired, failure { c.targets() }.reason)
        assertEquals(1, authCount())
        assertEquals(listOf("/Sessions/tv/Playing/Stop", "/Users/AuthenticateByName"), requests.map { it.url.encodedPath })
    }

    @Test
    fun `revoked stored token at connect is reported as an expired session`() = runTest {
        start { status(401) }
        val e = failure { controller(mapOf("token" to "vieux", "userId" to "u1", "username" to "jo")).connect() }
        assertEquals(RemoteError.SessionExpired, e.reason)
        assertFalse(e is HttpStatusException)
        // Un seul essai du mot de passe vide (compte sans mot de passe), refusé ici.
        assertEquals(1, authCount())
    }

    @Test
    fun `revoked stored token at connect is renewed with the password in memory`() = runTest {
        start { r ->
            when {
                r.url.encodedPath == "/Users/AuthenticateByName" -> ok("""{"AccessToken":"neuf","User":{"Id":"u1"}}""")
                r.token() == "vieux" -> status(401)
                else -> ok("""[{"Id":"tv","SupportsRemoteControl":true}]""")
            }
        }
        val c = controller(mapOf("token" to "vieux", "username" to "jo", "password" to "pw"))
        c.connect()
        assertEquals(listOf("/Sessions", "/Users/AuthenticateByName", "/Sessions"), requests.map { it.url.encodedPath })
        assertEquals("neuf", c.persistableExtras()["token"])
    }

    @Test
    fun `account without password renews with the empty password that worked`() = runTest {
        var tokens = 0
        start { r ->
            when {
                r.url.encodedPath == "/Users/AuthenticateByName" -> ok("""{"AccessToken":"t${++tokens}","User":{"Id":"u1"}}""")
                r.token() == "t1" && r.url.encodedPath != "/Sessions" -> status(401)
                r.url.encodedPath == "/Sessions" -> ok("""[{"Id":"tv","SupportsRemoteControl":true}]""")
                else -> status(204)
            }
        }
        val c = controller(mapOf("username" to "Salon"))
        c.connect()
        c.send(RemoteKey.SELECT)
        assertEquals(2, authCount())
        requests.filter { it.url.encodedPath == "/Users/AuthenticateByName" }.forEach {
            assertEquals("""{"Username":"Salon","Pw":""}""", it.body!!.utf8())
        }
        assertEquals("t2", c.persistableExtras()["token"])
    }

    @Test
    fun `network failure during renewal is not a lockout`() = runTest {
        var authAttempts = 0
        start { r ->
            when {
                r.url.encodedPath == "/Users/AuthenticateByName" ->
                    if (++authAttempts == 1) status(503) else ok("""{"AccessToken":"neuf","User":{"Id":"u1"}}""")
                r.token() == "neuf" -> status(204)
                else -> status(401)
            }
        }
        val c = controller(mapOf("token" to "vieux", "sessionId" to "tv", "username" to "jo", "password" to "pw"))
        val e = expectFailure<HttpStatusException> { c.send(RemoteKey.STOP) }
        assertEquals(503, e.code)
        c.send(RemoteKey.STOP)
        assertEquals(2, authCount())
        assertTrue(requests.last().token() == "neuf")
    }
}
