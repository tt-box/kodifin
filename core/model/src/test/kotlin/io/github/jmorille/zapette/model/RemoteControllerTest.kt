package io.github.jmorille.zapette.model

import io.github.jmorille.zapette.testing.assertFailsWithMessage
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Contrôleur minimal : n'implémente que les membres abstraits pour exercer les défauts. */
private class MinimalController : RemoteController {
    override val device = Device("r", "Salon", DeviceKind.ROKU, "10.0.0.5")
    override val capabilities = setOf(Capability.NAVIGATION)
    override val supportedKeys = setOf(RemoteKey.UP)
    val sent = mutableListOf<RemoteKey>()

    override suspend fun send(key: RemoteKey) {
        requireKey(key)
        sent += key
    }
}

class RemoteControllerTest {
    private val controller = MinimalController()

    @Test
    fun `default actions raise UnsupportedActionException naming the protocol`() = runTest {
        val cases: List<Pair<String, suspend () -> Unit>> = listOf(
            "Lecture d'URL" to { controller.playUrl("http://nas/a.mp4") },
            "Volume absolu" to { controller.setVolume(10) },
            "Recherche" to { controller.seekTo(1_000) },
            "Saisie de texte" to { controller.sendText("abc") },
        )
        cases.forEach { (label, action) ->
            val e = assertFailsWithMessage<UnsupportedActionException>(label) { action() }
            assertTrue(e.message!!.contains("Roku"), e.message)
            // Sous-type d'UnsupportedOperationException : les appelants génériques le reconnaissent.
            assertTrue(e is UnsupportedOperationException)
        }
    }

    @Test
    fun `default lifecycle is a no-op and playback state is empty`() = runTest {
        controller.connect()
        controller.disconnect()
        assertEquals(emptyList<PlaybackState>(), controller.playbackState().toList())
    }

    @Test
    fun `requireKey rejects unsupported keys`() = runTest {
        controller.send(RemoteKey.UP)
        assertEquals(listOf(RemoteKey.UP), controller.sent)
        assertFailsWithMessage<UnsupportedActionException>("Touche HOME") { controller.send(RemoteKey.HOME) }
        assertEquals(listOf(RemoteKey.UP), controller.sent)
    }
}
