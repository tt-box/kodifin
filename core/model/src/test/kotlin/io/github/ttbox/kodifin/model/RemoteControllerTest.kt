package io.github.ttbox.kodifin.model

import io.github.ttbox.kodifin.testing.assertUnsupported
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
        val cases: List<Pair<UnsupportedAction, suspend () -> Unit>> = listOf(
            UnsupportedAction.PlayUrl to { controller.playUrl("http://nas/a.mp4") },
            UnsupportedAction.Volume to { controller.setVolume(10) },
            UnsupportedAction.Seek to { controller.seekTo(1_000) },
            UnsupportedAction.TextInput to { controller.sendText("abc") },
        )
        cases.forEach { (expected, action) ->
            val e = assertUnsupported(expected) { action() }
            assertEquals(DeviceKind.ROKU, e.kind)
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
        assertUnsupported(UnsupportedAction.Key(RemoteKey.HOME)) { controller.send(RemoteKey.HOME) }
        assertEquals(listOf(RemoteKey.UP), controller.sent)
    }
}
