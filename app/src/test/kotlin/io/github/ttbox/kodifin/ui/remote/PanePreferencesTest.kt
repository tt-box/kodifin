package io.github.ttbox.kodifin.ui.remote

import androidx.compose.runtime.snapshots.Snapshot
import io.github.ttbox.kodifin.data.AppSettings
import io.github.ttbox.kodifin.data.SettingsStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Disposition tablette : relue au démarrage, enregistrée à chaque changement. */
class PanePreferencesTest {
    private val scope = CoroutineScope(Dispatchers.Default + Job())

    private class FakeStore(initial: AppSettings) : SettingsStore {
        val state = MutableStateFlow(initial)
        var failWrites = false
        var writeAttempts = 0

        /** Lecture retenue jusqu'à l'achèvement de [gate] : simule un disque lent au démarrage. */
        var gate: CompletableDeferred<Unit> = CompletableDeferred<Unit>().apply { complete(Unit) }
        override val settings = flow {
            gate.await()
            emitAll(state)
        }
        override suspend fun update(transform: (AppSettings) -> AppSettings) {
            writeAttempts++
            if (failWrites) throw IOException("stockage plein")
            state.value = transform(state.value)
        }
    }

    @After
    fun tearDown() {
        scope.cancel()
        PanePreferences.reset()
    }

    @Test
    fun `saved choices are restored then changes are saved`() = runBlocking {
        val store = FakeStore(AppSettings(detailsInPane = true))
        PanePreferences.bind(store, scope)
        withTimeout(5_000) {
            while (!PanePreferences.detailsInPane) kotlinx.coroutines.delay(10)
        }
        assertFalse(PanePreferences.hidden)

        PanePreferences.hidden = true
        Snapshot.sendApplyNotifications()
        val saved = withTimeout(5_000) { store.state.first { it.paneHidden } }
        assertTrue(saved.detailsInPane)
        assertEquals(AppSettings(paneHidden = true, detailsInPane = true), saved)
    }

    @Test
    fun `a change made before the saved choices are read is not overwritten`() = runBlocking {
        val store = FakeStore(AppSettings(paneHidden = false, detailsInPane = true))
        store.gate = CompletableDeferred()
        val job = PanePreferences.bind(store, scope)
        // L'utilisateur masque le volet avant la fin de la lecture du disque.
        PanePreferences.hidden = true
        store.gate.complete(Unit)
        val saved = withTimeout(5_000) { store.state.first { it.paneHidden } }
        // Le choix local est gardé et enregistré ; l'autre choix vient du disque.
        assertTrue(PanePreferences.hidden)
        assertTrue(PanePreferences.detailsInPane)
        assertTrue(saved.detailsInPane)
        assertTrue(job.isActive)
    }

    @Test
    fun `write failures are swallowed and later changes still saved`() = runBlocking {
        val store = FakeStore(AppSettings()).apply { failWrites = true }
        val job = PanePreferences.bind(store, scope)
        withTimeout(5_000) { while (store.writeAttempts == 0) kotlinx.coroutines.delay(10) }
        assertTrue(job.isActive)
        store.failWrites = false
        PanePreferences.detailsInPane = true
        Snapshot.sendApplyNotifications()
        val saved = withTimeout(5_000) { store.state.first { it.detailsInPane } }
        assertFalse(saved.paneHidden)
    }
}
