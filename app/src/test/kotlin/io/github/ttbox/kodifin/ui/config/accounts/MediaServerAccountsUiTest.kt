package io.github.ttbox.kodifin.ui.config.accounts

import io.github.ttbox.kodifin.R
import io.github.ttbox.kodifin.mediaserver.MediaServerFlavor
import io.github.ttbox.kodifin.mediaserver.PublicUserDto
import io.github.ttbox.kodifin.mediaserver.QuickConnect
import io.github.ttbox.kodifin.model.DeviceKind
import io.github.ttbox.kodifin.text.UiText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/** Logique des composables de comptes Jellyfin/Emby, sur un faux serveur HTTP en mémoire. */
class MediaServerAccountsUiTest {
    /** Répond selon le chemin ; les chemins inconnus renvoient 404. Aucun accès réseau. */
    private class FakeServer(private val routes: Map<String, () -> Pair<Int, String>>) : Interceptor {
        val paths = CopyOnWriteArrayList<String>()

        override fun intercept(chain: Interceptor.Chain): Response {
            val path = chain.request().url.encodedPath
            paths += path
            val (code, body) = routes[path]?.invoke() ?: (404 to "")
            return Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(code).message("R")
                .body(body.toResponseBody("application/json".toMediaType())).build()
        }
    }

    private fun http(server: FakeServer) = OkHttpClient.Builder().addInterceptor(server).build()

    @Test
    fun `flavor follows the device kind`() {
        assertEquals(MediaServerFlavor.EMBY, flavorOf(DeviceKind.EMBY))
        assertEquals(MediaServerFlavor.JELLYFIN, flavorOf(DeviceKind.JELLYFIN))
    }

    @Test
    fun `public users are loaded under the detected base path`() = runTest {
        val server = FakeServer(
            mapOf(
                "/jellyfin/System/Info/Public" to { 200 to """{"Id":"srv"}""" },
                "/jellyfin/Users/Public" to { 200 to """[{"Id":"u1","Name":"Alice","HasPassword":true}]""" },
            ),
        )
        val client = mediaServerClient(DeviceKind.JELLYFIN, "http://10.0.0.2:8096", http(server), "dev")
        val state = loadPublicUsers(client)
        assertEquals(PublicUsersState.Loaded(listOf(PublicUserDto("u1", "Alice", hasPassword = true))), state)
        assertEquals(
            listOf("/System/Info/Public", "/jellyfin/System/Info/Public", "/jellyfin/Users/Public"),
            server.paths,
        )
    }

    @Test
    fun `unreachable server gives a readable error`() = runTest {
        val server = FakeServer(emptyMap())
        val state = loadPublicUsers(mediaServerClient(DeviceKind.EMBY, "http://10.0.0.2:8096", http(server), "dev"))
        assertEquals(
            PublicUsersState.Failed(UiText.Res(R.string.config_server_not_found_check_address)),
            state,
        )
    }

    @Test
    fun `quick connect reports each step and returns the login`() = runTest {
        var polls = 0
        val server = FakeServer(
            mapOf(
                "/System/Info/Public" to { 200 to """{"Id":"srv"}""" },
                "/QuickConnect/Enabled" to { 200 to "true" },
                "/QuickConnect/Initiate" to { 200 to """{"Secret":"sec","Code":"246810"}""" },
                "/QuickConnect/Connect" to { polls++; 200 to """{"Authenticated":${polls >= 2}}""" },
                "/Users/AuthenticateWithQuickConnect" to { 200 to """{"AccessToken":"tok","User":{"Id":"u1","Name":"Alice"}}""" },
            ),
        )
        val client = mediaServerClient(DeviceKind.JELLYFIN, "http://10.0.0.2:8096", http(server), "dev")
        val states = mutableListOf<QuickConnectState>()
        val login = runQuickConnect(client, QuickConnect(client, pollIntervalMs = 1, timeoutMs = 10, sleep = {}), Dispatchers.Unconfined) {
            states += it
        }

        assertEquals(QuickConnectLogin("tok", "u1", "Alice"), login)
        assertEquals(
            listOf(
                QuickConnectState.Starting,
                QuickConnectState.WaitingForApproval("246810"),
                QuickConnectState.Authenticated("Alice"),
            ),
            states,
        )
        assertEquals(2, polls)
        assertEquals("QuickConnectLogin(token=***, userId=u1, userName=Alice)", login.toString())
    }

    @Test
    fun `disabled quick connect ends in a failure state`() = runTest {
        val server = FakeServer(
            mapOf(
                "/System/Info/Public" to { 200 to """{"Id":"srv"}""" },
                "/QuickConnect/Enabled" to { 200 to "false" },
            ),
        )
        val client = mediaServerClient(DeviceKind.JELLYFIN, "http://10.0.0.2:8096", http(server), "dev")
        val states = mutableListOf<QuickConnectState>()
        val login = runQuickConnect(client, QuickConnect(client, sleep = {}), Dispatchers.Unconfined) { states += it }

        assertNull(login)
        assertEquals(
            listOf(
                QuickConnectState.Starting,
                QuickConnectState.Failed(UiText.Res(R.string.error_setup_quick_connect_disabled)),
            ),
            states,
        )
        assertFalse("aucune demande ouverte", "/QuickConnect/Initiate" in server.paths)
    }

    @Test
    fun `exception without message falls back to a generic label`() {
        assertEquals(UiText.Res(R.string.config_server_unreachable), errorText(IllegalStateException()))
        assertEquals(UiText.Raw("boom"), errorText(IllegalStateException("boom")))
    }
}
