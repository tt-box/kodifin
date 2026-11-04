package io.github.jmorille.zapette.mediaserver

import io.github.jmorille.zapette.model.Device
import io.github.jmorille.zapette.model.DeviceKind
import io.github.jmorille.zapette.model.RemoteKey
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

/** Choix de l'utilisateur, Quick Connect et comptes sans mot de passe (Jellyfin / Emby). */
class MediaServerAccountsTest {
    private lateinit var server: MockWebServer
    private val http = OkHttpClient()
    private val sleeps = mutableListOf<Long>()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() = server.close()

    private fun json(body: String, code: Int = 200) =
        MockResponse.Builder().code(code).addHeader("Content-Type", "application/json").body(body).build()

    private fun client(flavor: MediaServerFlavor = MediaServerFlavor.JELLYFIN, path: String = "") =
        MediaServerClient(http, server.url(path).toString(), flavor, "dev-1")

    private fun quickConnect(client: MediaServerClient, timeoutMs: Long = 9_000) =
        QuickConnect(client, pollIntervalMs = 3_000, timeoutMs = timeoutMs, sleep = { sleeps += it })

    private fun assertNoMoreRequests() = assertNull(server.takeRequest(100, TimeUnit.MILLISECONDS))

    @Test
    fun `public users are listed without token and with password hints`() = runTest {
        server.enqueue(
            json(
                """[{"Id":"u1","Name":"Alice","HasPassword":true,"HasConfiguredPassword":true},
                   {"Id":"u2","Name":"Enfants","HasPassword":false,"HasConfiguredPassword":false},
                   {"Id":"u3","Name":"Bob","HasPassword":false,"HasConfiguredPassword":true},
                   {"Id":"u4","Name":""}]""",
            ),
        )
        val users = client().publicUsers()

        assertEquals(listOf("Alice", "Enfants", "Bob"), users.map { it.name })
        assertEquals(listOf(true, false, true), users.map { it.requiresPassword })
        val request = server.next()
        assertEquals("GET", request.method)
        assertEquals("/Users/Public", request.url.encodedPath)
        val auth = request.headers["Authorization"]!!
        assertTrue(auth.startsWith("MediaBrowser Client=\"Zapette\""))
        assertFalse("aucun jeton ne doit être envoyé", auth.contains("Token="))
    }

    @Test
    fun `emby public users use the emby authorization header`() = runTest {
        server.enqueue(json("""[{"Id":"e1","Name":"Salon","HasPassword":false}]"""))
        val users = client(MediaServerFlavor.EMBY).publicUsers()
        assertEquals(listOf(PublicUserDto("e1", "Salon")), users)
        val request = server.next()
        assertTrue(request.headers["X-Emby-Authorization"]!!.contains("DeviceId=\"dev-1\""))
        assertNull(request.headers["X-Emby-Token"])
    }

    @Test
    fun `base path is located before listing users`() = runTest {
        server.enqueue(MockResponse.Builder().body("<html>interface web</html>").build()) // racine
        server.enqueue(json("""{"Id":"srv"}""")) // /jellyfin/System/Info/Public
        server.enqueue(json("""[{"Id":"u1","Name":"Alice"}]"""))

        val client = client()
        assertEquals("/jellyfin", client.locateBasePath())
        assertEquals(listOf("Alice"), client.publicUsers().map { it.name })

        assertEquals("/System/Info/Public", server.next().url.encodedPath)
        assertEquals("/jellyfin/System/Info/Public", server.next().url.encodedPath)
        assertEquals("/jellyfin/Users/Public", server.next().url.encodedPath)
    }

    @Test
    fun `locate base path keeps a working url and reports an unreachable api`() = runTest {
        server.enqueue(json("""{"Id":"srv"}"""))
        assertEquals("", client().locateBasePath())
        assertEquals("/System/Info/Public", server.next().url.encodedPath)

        repeat(3) { server.enqueue(MockResponse.Builder().code(404).build()) }
        assertNull(client().locateBasePath())
        assertEquals(
            listOf("/System/Info/Public", "/jellyfin/System/Info/Public", "/emby/System/Info/Public"),
            List(3) { server.next().url.encodedPath },
        )
    }

    @Test
    fun `quick connect shows a code then exchanges the approved secret for a token`() = runTest {
        server.enqueue(json("true"))
        server.enqueue(json("""{"Secret":"s3cr3t","Code":"123456","Authenticated":false}"""))
        server.enqueue(json("""{"Secret":"s3cr3t","Code":"123456","Authenticated":false}"""))
        server.enqueue(json("""{"Secret":"s3cr3t","Code":"123456","Authenticated":true}"""))
        server.enqueue(json("""{"AccessToken":"tok-qc","User":{"Id":"u9","Name":"Alice"}}"""))

        val client = client()
        val qc = quickConnect(client)
        val request = qc.start()
        assertEquals("123456", request.code)
        val result = qc.awaitApproval(request)

        assertEquals("tok-qc", result.accessToken)
        assertEquals(UserDto("u9", "Alice"), result.user)
        assertEquals("le client garde le jeton obtenu", "tok-qc", client.accessToken)
        assertEquals(listOf(3_000L, 3_000L), sleeps)

        assertEquals("/QuickConnect/Enabled", server.next().url.encodedPath)
        val initiate = server.next()
        assertEquals("POST", initiate.method)
        assertEquals("/QuickConnect/Initiate", initiate.url.encodedPath)
        assertTrue(initiate.headers["Authorization"]!!.contains("DeviceId=\"dev-1\""))
        repeat(2) {
            val poll = server.next()
            assertEquals("/QuickConnect/Connect", poll.url.encodedPath)
            assertEquals("s3cr3t", poll.url.queryParameter("secret"))
        }
        val auth = server.next()
        assertEquals("POST", auth.method)
        assertEquals("/Users/AuthenticateWithQuickConnect", auth.url.encodedPath)
        assertEquals("""{"Secret":"s3cr3t"}""", auth.body!!.utf8())
        assertNoMoreRequests()
    }

    @Test
    fun `quick connect falls back to GET on older servers`() = runTest {
        server.enqueue(json("true"))
        server.enqueue(MockResponse.Builder().code(405).build())
        server.enqueue(json("""{"Secret":"s","Code":"654321"}"""))

        assertEquals("654321", quickConnect(client()).start().code)

        server.next()
        assertEquals("POST", server.next().method)
        val retry = server.next()
        assertEquals("GET", retry.method)
        assertEquals("/QuickConnect/Initiate", retry.url.encodedPath)
    }

    @Test
    fun `disabled quick connect is reported without opening a request`() = runTest {
        server.enqueue(json("false"))
        val e = expectFailure<QuickConnectUnavailableException> { quickConnect(client()).start() }
        assertTrue(e.message!!.contains("pas activé"))
        assertEquals("/QuickConnect/Enabled", server.next().url.encodedPath)
        assertNoMoreRequests()
    }

    @Test
    fun `missing quick connect endpoint means unavailable`() = runTest {
        server.enqueue(MockResponse.Builder().code(404).build())
        expectFailure<QuickConnectUnavailableException> { quickConnect(client(MediaServerFlavor.EMBY)).start() }
        server.next()
        assertNoMoreRequests()
    }

    @Test
    fun `incomplete initiate answer is rejected`() = runTest {
        server.enqueue(json("true"))
        server.enqueue(json("""{"Code":"123456"}"""))
        val e = expectFailure<io.github.jmorille.zapette.model.RemoteException> { quickConnect(client()).start() }
        assertEquals("Réponse Quick Connect incomplète", e.message)
    }

    @Test
    fun `code expires after the timeout without authenticating`() = runTest {
        repeat(3) { server.enqueue(json("""{"Secret":"s","Code":"1","Authenticated":false}""")) }
        val qc = quickConnect(client(), timeoutMs = 9_000)
        expectFailure<QuickConnectExpiredException> { qc.awaitApproval(QuickConnectDto("s", "1")) }
        assertEquals(listOf(3_000L, 3_000L, 3_000L), sleeps)
        repeat(3) { assertEquals("/QuickConnect/Connect", server.next().url.encodedPath) }
        assertNoMoreRequests()
    }

    @Test
    fun `forgotten request is reported as expired`() = runTest {
        server.enqueue(MockResponse.Builder().code(404).build())
        expectFailure<QuickConnectExpiredException> { quickConnect(client()).awaitApproval(QuickConnectDto("s", "1")) }
        assertEquals(1, sleeps.size)
        server.next()
        assertNoMoreRequests()
    }

    @Test
    fun `invalid polling settings are refused`() = runTest {
        val c = client()
        expectFailure<IllegalArgumentException> { QuickConnect(c, pollIntervalMs = 0) }
        expectFailure<IllegalArgumentException> { QuickConnect(c, pollIntervalMs = 5_000, timeoutMs = 1_000) }
    }

    @Test
    fun `secrets never appear in toString`() {
        val qc = QuickConnectDto("s3cr3t", "123456", true).toString()
        assertFalse(qc.contains("s3cr3t"))
        assertTrue(qc.contains("123456"))
        val auth = AuthResult("tok-secret", UserDto("u1", "Alice")).toString()
        assertFalse(auth.contains("tok-secret"))
        assertTrue(auth.contains("Alice"))
        val client = MediaServerClient(http, "http://10.0.0.2:8096", MediaServerFlavor.JELLYFIN, "d", "tok-secret").toString()
        assertFalse(client.contains("tok-secret"))
    }

    @Test
    fun `emby user without password authenticates with an empty password`() = runTest {
        server.enqueue(json("""{"AccessToken":"tok-e","User":{"Id":"e1","Name":"Salon"}}"""))
        server.enqueue(json("""[{"Id":"tv","SupportsRemoteControl":true}]"""))
        server.enqueue(MockResponse.Builder().code(204).build())

        val device = Device("s", "Emby", DeviceKind.EMBY, server.hostName, server.port, mapOf("username" to "Salon"))
        val controller = MediaServerController.create(device, http, "dev-1")
        controller.connect()
        controller.send(RemoteKey.SELECT)

        val auth = server.next()
        assertEquals("/Users/AuthenticateByName", auth.url.encodedPath)
        assertEquals("""{"Username":"Salon","Pw":""}""", auth.body!!.utf8())
        assertTrue(auth.headers["X-Emby-Authorization"]!!.startsWith("MediaBrowser Client=\"Zapette\""))
        assertNull("aucun jeton avant authentification", auth.headers["X-Emby-Token"])
        assertEquals("tok-e", server.next().headers["X-Emby-Token"])
        assertEquals("/Sessions/tv/Command", server.next().url.encodedPath)
        assertEquals("tok-e", controller.persistableExtras()["token"])
    }

    @Test
    fun `quick connect token drives the controller without any password`() = runTest {
        server.enqueue(json("""[{"Id":"tv","SupportsRemoteControl":true}]"""))
        server.enqueue(MockResponse.Builder().code(204).build())
        val device = Device(
            "s", "Jellyfin", DeviceKind.JELLYFIN, server.hostName, server.port,
            mapOf("token" to "tok-qc", "userId" to "u9", "username" to "Alice"),
        )
        val controller = MediaServerController.create(device, http, "dev-1")
        controller.connect()
        controller.send(RemoteKey.PLAY_PAUSE)

        val sessions = server.next()
        assertEquals("/Sessions", sessions.url.encodedPath)
        assertEquals("u9", sessions.url.queryParameter("ControllableByUserId"))
        assertTrue(sessions.headers["Authorization"]!!.contains("Token=\"tok-qc\""))
        assertEquals("/Sessions/tv/Playing/PlayPause", server.next().url.encodedPath)
        assertNoMoreRequests()
    }
}
