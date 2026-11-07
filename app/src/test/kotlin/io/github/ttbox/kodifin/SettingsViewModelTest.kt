package io.github.ttbox.kodifin

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.ttbox.kodifin.cast.CastMaxBitrate
import io.github.ttbox.kodifin.cast.SubtitleBackground
import io.github.ttbox.kodifin.cast.SubtitleColor
import io.github.ttbox.kodifin.cast.SubtitleSize
import io.github.ttbox.kodifin.data.AppSettings
import io.github.ttbox.kodifin.data.SettingsStore
import io.github.ttbox.kodifin.ui.settings.SettingsViewModel
import io.github.ttbox.kodifin.text.UiText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val viewModels = ViewModelStore()

    /** Faux magasin en mémoire ; peut simuler un stockage indisponible. */
    private class FakeStore : SettingsStore {
        val state = MutableStateFlow(AppSettings())
        var failing = false
        var writes = 0
        override val settings = state

        override suspend fun update(transform: (AppSettings) -> AppSettings) {
            if (failing) throw IOException("disque plein")
            writes++
            state.value = transform(state.value)
        }
    }

    private val store = FakeStore()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() {
        viewModels.clear()
        Dispatchers.resetMain()
    }

    private fun vm(): SettingsViewModel = ViewModelProvider.create(
        viewModels,
        viewModelFactory { initializer { SettingsViewModel(store) } },
    )[SettingsViewModel::class]

    @Test
    fun `settings are null until loaded then follow the store`() = runTest(dispatcher) {
        val vm = vm()
        assertNull(vm.settings.value)
        advanceUntilIdle()
        assertEquals(AppSettings(), vm.settings.value)
        store.state.value = AppSettings(keepScreenOn = true)
        advanceUntilIdle()
        assertTrue(vm.settings.value!!.keepScreenOn)
    }

    @Test
    fun `each setter updates only its field`() = runTest(dispatcher) {
        val vm = vm()
        vm.setKeepScreenOn(true)
        vm.setMaxBitrate(CastMaxBitrate.P1080)
        vm.setSubtitleSize(SubtitleSize.SMALL)
        vm.setSubtitleColor(SubtitleColor.CYAN)
        vm.setSubtitleBackground(SubtitleBackground.NONE)
        vm.setCastAutoDisconnect(false)
        advanceUntilIdle()
        val s = store.state.value
        assertTrue(s.keepScreenOn)
        assertEquals(CastMaxBitrate.P1080, s.cast.maxBitrate)
        assertEquals(SubtitleSize.SMALL, s.cast.subtitles.size)
        assertEquals(SubtitleColor.CYAN, s.cast.subtitles.textColor)
        assertEquals(SubtitleBackground.NONE, s.cast.subtitles.background)
        assertFalse(s.cast.autoDisconnect)
        assertEquals(6, store.writes)
        assertEquals(s, vm.settings.value)
    }

    @Test
    fun `write failure is reported then cleared by the next success`() = runTest(dispatcher) {
        val vm = vm()
        store.failing = true
        vm.setKeepScreenOn(true)
        advanceUntilIdle()
        assertEquals(UiText.Res(R.string.settings_error_not_saved), vm.error.value)
        assertFalse(store.state.value.keepScreenOn)
        store.failing = false
        vm.setKeepScreenOn(true)
        advanceUntilIdle()
        assertNull(vm.error.value)
        assertTrue(store.state.value.keepScreenOn)
    }
}
