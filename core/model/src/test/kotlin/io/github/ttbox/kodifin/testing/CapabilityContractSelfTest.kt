package io.github.ttbox.kodifin.testing

import io.github.ttbox.kodifin.model.Capability
import io.github.ttbox.kodifin.model.Device
import io.github.ttbox.kodifin.model.DeviceKind
import io.github.ttbox.kodifin.model.PlaybackState
import io.github.ttbox.kodifin.model.RemoteController
import io.github.ttbox.kodifin.model.RemoteKey
import io.github.ttbox.kodifin.model.UnsupportedAction
import io.github.ttbox.kodifin.model.UnsupportedActionException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertFailsWith

/** Le contrat des capacités vérifie aussi le flux d'état annoncé par NOW_PLAYING. */
class CapabilityContractSelfTest {
    private fun controller(state: () -> Flow<PlaybackState>?) = object : RemoteController {
        override val device = Device("c", "Contrat", DeviceKind.ROKU, "127.0.0.1", 1)
        override val capabilities = setOf(Capability.NOW_PLAYING, Capability.NAVIGATION)
        override val supportedKeys = emptySet<RemoteKey>()
        override suspend fun send(key: RemoteKey) = Unit
        override fun playbackState(): Flow<PlaybackState> = state() ?: super.playbackState()
    }

    private fun honors(state: () -> Flow<PlaybackState>?) = runBlocking {
        assertHonorsCapabilities(controller(state), nowPlayingProbeMs = 100)
    }

    @Test
    fun `emitting or waiting flows are accepted`() {
        honors { flowOf(PlaybackState(title = "Film")) }
        honors { flow { awaitCancellation() } }
        honors { flow { throw IllegalStateException("non connecté") } }
    }

    @Test
    fun `default empty flow is refused`() {
        assertFailsWith<AssertionError> { honors { null } }
        assertFailsWith<AssertionError> { honors { emptyFlow() } }
    }

    @Test
    fun `flow completing without any state is refused`() {
        assertFailsWith<AssertionError> { honors { flow { } } }
    }

    @Test
    fun `unsupported flow is refused`() {
        assertFailsWith<AssertionError> {
            honors { flow { throw UnsupportedActionException(UnsupportedAction.Volume) } }
        }
    }
}
