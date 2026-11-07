package io.github.ttbox.kodifin.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import io.github.ttbox.kodifin.cast.CastMaxBitrate
import io.github.ttbox.kodifin.cast.CastSettings
import io.github.ttbox.kodifin.cast.CastSubtitleStyle
import io.github.ttbox.kodifin.cast.SubtitleBackground
import io.github.ttbox.kodifin.cast.SubtitleColor
import io.github.ttbox.kodifin.cast.SubtitleSize
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** DataStore réel sur un dossier temporaire, distinct du fichier des appareils. */
class SettingsRepositoryTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private var scope = CoroutineScope(Dispatchers.IO + Job())
    private lateinit var file: File
    private lateinit var store: DataStore<Preferences>
    private lateinit var repo: SettingsRepository

    @Before
    fun setUp() {
        file = File(tmp.root, "${SettingsRepository.FILE_NAME}.preferences_pb")
        open()
    }

    private fun open() {
        store = testStore(scope, file)
        repo = SettingsRepository(store)
    }

    @After
    fun tearDown() = scope.cancel()

    @Test
    fun `defaults when nothing is stored`() = runBlocking {
        val s = repo.settings.first()
        assertEquals(AppSettings(), s)
        assertFalse(s.keepScreenOn)
        assertTrue(s.cast.autoDisconnect)
        assertEquals(CastMaxBitrate.AUTO, s.cast.maxBitrate)
        assertFalse(repo.keepScreenOn.first())
    }

    @Test
    fun `updates are persisted and survive a reopen`() = runBlocking {
        val expected = AppSettings(
            keepScreenOn = true,
            cast = CastSettings(
                maxBitrate = CastMaxBitrate.P720,
                subtitles = CastSubtitleStyle(SubtitleSize.HUGE, SubtitleColor.YELLOW, SubtitleBackground.OPAQUE),
                autoDisconnect = false,
            ),
        )
        repo.update { expected }
        assertEquals(expected, repo.settings.first())
        assertTrue(repo.keepScreenOn.first())
        assertEquals(expected.cast, repo.cast.first())

        scope.cancel()
        scope = CoroutineScope(Dispatchers.IO + Job())
        open()
        assertEquals(expected, repo.settings.first())
    }

    @Test
    fun `update transforms the current value`() = runBlocking {
        repo.update { it.copy(keepScreenOn = true) }
        repo.update { it.copy(cast = it.cast.copy(maxBitrate = CastMaxBitrate.P480)) }
        val s = repo.settings.first()
        assertTrue(s.keepScreenOn)
        assertEquals(CastMaxBitrate.P480, s.cast.maxBitrate)
    }

    @Test
    fun `unknown enum names fall back to defaults`() = runBlocking {
        store.edit {
            it[SettingsRepository.CAST_MAX_BITRATE] = "P4K"
            it[SettingsRepository.CAST_SUBTITLE_COLOR] = "MAGENTA"
            it[SettingsRepository.CAST_SUBTITLE_SIZE] = "LARGE"
        }
        val s = repo.settings.first()
        assertEquals(CastMaxBitrate.AUTO, s.cast.maxBitrate)
        assertEquals(SubtitleColor.WHITE, s.cast.subtitles.textColor)
        assertEquals(SubtitleSize.LARGE, s.cast.subtitles.size)
    }

    @Test
    fun `corrupted file is replaced by defaults`() = runBlocking {
        scope.cancel()
        file.writeBytes(byteArrayOf(0x7F, 0x00, 0x13, 0x37))
        scope = CoroutineScope(Dispatchers.IO + Job())
        open()
        assertEquals(AppSettings(), repo.settings.first())
    }
}
