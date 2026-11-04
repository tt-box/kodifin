package io.github.jmorille.zapette

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.jmorille.zapette.model.RemoteException
import io.github.jmorille.zapette.ui.plex.PlexLinkDependencies
import io.github.jmorille.zapette.ui.plex.PlexLinkState
import io.github.jmorille.zapette.ui.plex.PlexLinkViewModel
import io.github.jmorille.zapette.ui.plex.PlexPinRequest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PlexLinkViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val store = ViewModelStore()

    private class Deps : PlexLinkDependencies {
        override val plexToken = MutableStateFlow<String?>(null)
        val tokenWrites = mutableListOf<String?>()
        var pins = 0
        var checks = 0
        var pinFailure: Exception? = null

        /** Réponse de chaque vérification ; une valeur non complétée simule une requête en cours. */
        var answer: suspend () -> String? = { null }

        override suspend fun setPlexToken(token: String?) {
            tokenWrites += token
            plexToken.value = token
        }

        override suspend fun createPin(): PlexPinRequest {
            pinFailure?.let { throw it }
            pins++
            return object : PlexPinRequest {
                override val code = "ABCD"
                override suspend fun check(): String? {
                    checks++
                    return answer()
                }
            }
        }
    }

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() {
        store.clear()
        Dispatchers.resetMain()
    }

    private fun newVm(deps: Deps, maxPolls: Int = 150) = ViewModelProvider.create(
        store,
        viewModelFactory { initializer { PlexLinkViewModel(deps, dispatcher, pollIntervalMs = 2_000, maxPolls = maxPolls) } },
    )[PlexLinkViewModel::class]

    @Test
    fun `already linked account is shown as linked`() = runTest(dispatcher) {
        val deps = Deps().apply { plexToken.value = "t" }
        val vm = newVm(deps)
        advanceUntilIdle()
        assertEquals(PlexLinkState.Linked, vm.state.value)
    }

    @Test
    fun `several starts create a single pin`() = runTest(dispatcher) {
        val deps = Deps()
        val vm = newVm(deps)
        vm.start()
        vm.start()
        runCurrent()
        vm.start()
        runCurrent()
        assertEquals(1, deps.pins)
        assertEquals(PlexLinkState.WaitingForCode("ABCD"), vm.state.value)
    }

    @Test
    fun `validated code stores the token`() = runTest(dispatcher) {
        val deps = Deps().apply { answer = { if (checks == 2) "tok" else null } }
        val vm = newVm(deps)
        vm.start()
        advanceUntilIdle()
        assertEquals(PlexLinkState.Linked, vm.state.value)
        assertEquals(listOf<String?>("tok"), deps.tokenWrites)
    }

    @Test
    fun `code expires after the configured number of polls`() = runTest(dispatcher) {
        val deps = Deps()
        val vm = newVm(deps, maxPolls = 3)
        vm.start()
        runCurrent()
        advanceTimeBy(5_999)
        runCurrent()
        assertEquals(PlexLinkState.WaitingForCode("ABCD"), vm.state.value)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(3, deps.checks)
        assertEquals(PlexLinkState.Failed("Code expiré, recommencez."), vm.state.value)
        assertEquals(emptyList<String?>(), deps.tokenWrites)
    }

    @Test
    fun `unlink while waiting cancels the polling`() = runTest(dispatcher) {
        val deps = Deps().apply { answer = { "tok" } }
        val vm = newVm(deps)
        vm.start()
        runCurrent()
        vm.unlink()
        advanceUntilIdle()
        assertEquals(PlexLinkState.Idle, vm.state.value)
        assertEquals(0, deps.checks)
        assertEquals(listOf<String?>(null), deps.tokenWrites)
        assertNull(deps.plexToken.value)
    }

    @Test
    fun `token validated during unlink is not saved again`() = runTest(dispatcher) {
        val pending = CompletableDeferred<String?>()
        val deps = Deps().apply { answer = { pending.await() } }
        val vm = newVm(deps)
        vm.start()
        advanceTimeBy(2_001) // première vérification en cours
        assertEquals(1, deps.checks)
        vm.unlink()
        pending.complete("tok")
        advanceUntilIdle()
        assertEquals(PlexLinkState.Idle, vm.state.value)
        assertEquals(listOf<String?>(null), deps.tokenWrites)
        assertNull(deps.plexToken.value)
    }

    @Test
    fun `plex tv error is shown`() = runTest(dispatcher) {
        val deps = Deps().apply { pinFailure = RemoteException("Réponse inattendue de plex.tv") }
        val vm = newVm(deps)
        vm.start()
        advanceUntilIdle()
        assertEquals(PlexLinkState.Failed("Réponse inattendue de plex.tv"), vm.state.value)
        // Nouvel essai possible après l'échec.
        deps.pinFailure = null
        vm.start()
        runCurrent()
        assertEquals(PlexLinkState.WaitingForCode("ABCD"), vm.state.value)
    }
}
