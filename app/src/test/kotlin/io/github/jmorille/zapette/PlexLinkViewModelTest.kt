package io.github.jmorille.zapette

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.jmorille.zapette.model.RemoteException
import io.github.jmorille.zapette.plex.PlexHomeUser
import io.github.jmorille.zapette.plex.PlexPinRefusedException
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

        var users: List<PlexHomeUser> = emptyList()
        var usersFailure: Exception? = null
        val homeRequests = mutableListOf<String>()

        /** Appels de bascule : (jeton du compte, uuid, PIN). */
        val switches = mutableListOf<Triple<String, String, String?>>()
        var switchAnswer: suspend (PlexHomeUser, String?) -> String = { user, _ -> "tok-${user.uuid}" }

        override suspend fun homeUsers(accountToken: String): List<PlexHomeUser> {
            homeRequests += accountToken
            usersFailure?.let { throw it }
            return users
        }

        override suspend fun switchUser(accountToken: String, user: PlexHomeUser, pin: String?): String {
            switches += Triple(accountToken, user.uuid, pin)
            return switchAnswer(user, pin)
        }

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

    // --- Plex Home : choix du profil après liaison.

    private val parent = PlexHomeUser(1, "u-parent", "Parent", admin = true)
    private val child = PlexHomeUser(2, "u-enfant", "Enfant", restricted = true)
    private val locked = PlexHomeUser(3, "u-verrou", "Verrou", isProtected = true)

    /** Lie le compte (jeton « tok ») et attend l'écran de choix. */
    private fun kotlinx.coroutines.test.TestScope.linkedHome(deps: Deps): PlexLinkViewModel {
        deps.answer = { "tok" }
        deps.users = listOf(parent, child, locked)
        val vm = newVm(deps)
        vm.start()
        advanceUntilIdle()
        return vm
    }

    @Test
    fun `home with several members offers the user choice`() = runTest(dispatcher) {
        val deps = Deps()
        val vm = linkedHome(deps)
        assertEquals(PlexLinkState.ChoosingUser(listOf(parent, child, locked)), vm.state.value)
        assertEquals(listOf("tok"), deps.homeRequests)
        // Le jeton du compte est enregistré dès la liaison.
        assertEquals(listOf<String?>("tok"), deps.tokenWrites)
    }

    @Test
    fun `single member or unreadable home links directly`() = runTest(dispatcher) {
        val single = Deps().apply { answer = { "tok" }; users = listOf(parent) }
        val vm = newVm(single)
        vm.start()
        advanceUntilIdle()
        assertEquals(PlexLinkState.Linked, vm.state.value)

        store.clear()
        val failing = Deps().apply { answer = { "tok" }; usersFailure = RemoteException("plex.tv injoignable") }
        val vm2 = newVm(failing)
        vm2.start()
        advanceUntilIdle()
        assertEquals(PlexLinkState.Linked, vm2.state.value)
        assertEquals(listOf<String?>("tok"), failing.tokenWrites)
    }

    @Test
    fun `choosing an unprotected user stores its token instead of the account token`() = runTest(dispatcher) {
        val deps = Deps()
        val vm = linkedHome(deps)
        vm.chooseUser(child, pin = "1234")
        advanceUntilIdle()
        assertEquals(PlexLinkState.Linked, vm.state.value)
        assertEquals(listOf(Triple("tok", "u-enfant", null as String?)), deps.switches)
        assertEquals(listOf<String?>("tok", "tok-u-enfant"), deps.tokenWrites)
        assertEquals("tok-u-enfant", deps.plexToken.value)
    }

    @Test
    fun `protected user needs a four digit pin before any request`() = runTest(dispatcher) {
        val deps = Deps()
        val vm = linkedHome(deps)
        for (bad in listOf(null, "", "12", "12a4", "12345")) {
            vm.chooseUser(locked, bad)
            advanceUntilIdle()
            assertEquals(
                PlexLinkState.ChoosingUser(listOf(parent, child, locked), error = "Code PIN à 4 chiffres requis pour Verrou."),
                vm.state.value,
            )
        }
        assertEquals(emptyList<Triple<String, String, String?>>(), deps.switches)

        vm.chooseUser(locked, " 4321 ")
        advanceUntilIdle()
        assertEquals(PlexLinkState.Linked, vm.state.value)
        assertEquals(listOf(Triple("tok", "u-verrou", "4321" as String?)), deps.switches)
        assertEquals("tok-u-verrou", deps.plexToken.value)
    }

    @Test
    fun `refused pin keeps the choice open and the account token`() = runTest(dispatcher) {
        val deps = Deps().apply { switchAnswer = { _, _ -> throw PlexPinRefusedException() } }
        val vm = linkedHome(deps)
        vm.chooseUser(locked, "0000")
        advanceUntilIdle()
        assertEquals(PlexLinkState.ChoosingUser(listOf(parent, child, locked), error = "Code PIN refusé"), vm.state.value)
        assertEquals("tok", deps.plexToken.value)

        // Nouvel essai réussi.
        deps.switchAnswer = { user, _ -> "tok-${user.uuid}" }
        vm.chooseUser(locked, "1111")
        advanceUntilIdle()
        assertEquals(PlexLinkState.Linked, vm.state.value)
        assertEquals("tok-u-verrou", deps.plexToken.value)
    }

    @Test
    fun `choice is ignored while a switch is running`() = runTest(dispatcher) {
        val pending = CompletableDeferred<String>()
        val deps = Deps().apply { switchAnswer = { _, _ -> pending.await() } }
        val vm = linkedHome(deps)
        vm.chooseUser(child)
        runCurrent()
        assertEquals(PlexLinkState.ChoosingUser(listOf(parent, child, locked), busy = true), vm.state.value)
        vm.chooseUser(parent)
        vm.keepAccount()
        runCurrent()
        assertEquals(1, deps.switches.size)
        pending.complete("tok-enfant")
        advanceUntilIdle()
        assertEquals(PlexLinkState.Linked, vm.state.value)
        assertEquals("tok-enfant", deps.plexToken.value)
    }

    @Test
    fun `keeping the main account leaves the account token`() = runTest(dispatcher) {
        val deps = Deps()
        val vm = linkedHome(deps)
        vm.keepAccount()
        advanceUntilIdle()
        assertEquals(PlexLinkState.Linked, vm.state.value)
        assertEquals(listOf<String?>("tok"), deps.tokenWrites)
        // Plus de jeton de compte en mémoire : un choix tardif est ignoré.
        vm.chooseUser(child)
        advanceUntilIdle()
        assertEquals(emptyList<Triple<String, String, String?>>(), deps.switches)
    }

    @Test
    fun `unlink during a switch does not store the profile token`() = runTest(dispatcher) {
        val pending = CompletableDeferred<String>()
        val deps = Deps().apply { switchAnswer = { _, _ -> pending.await() } }
        val vm = linkedHome(deps)
        vm.chooseUser(child)
        runCurrent()
        vm.unlink()
        pending.complete("tok-enfant")
        advanceUntilIdle()
        assertEquals(PlexLinkState.Idle, vm.state.value)
        assertEquals(listOf<String?>("tok", null), deps.tokenWrites)
        assertNull(deps.plexToken.value)
    }
}
