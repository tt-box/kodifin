package io.github.jmorille.zapette.firetv

import io.github.jmorille.zapette.model.Device
import io.github.jmorille.zapette.model.DeviceKind
import io.github.jmorille.zapette.model.RemoteKey
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

private class RecordingShell : AdbShell {
    val commands = mutableListOf<String>()
    override suspend fun shell(command: String): String {
        commands += command
        return ""
    }
}

class FireTvTest {
    private val device = Device("f", "Fire TV", DeviceKind.FIRE_TV, "10.0.0.12")

    @Test
    fun `keys become input keyevent commands`() = runTest {
        val shell = RecordingShell()
        val c = FireTvController(device, shell)
        c.send(RemoteKey.SELECT)
        c.send(RemoteKey.PLAY_PAUSE)
        assertEquals(listOf("input keyevent 23", "input keyevent 85"), shell.commands)
    }

    @Test
    fun `urls are shell quoted so they cannot inject commands`() = runTest {
        val shell = RecordingShell()
        FireTvController(device, shell).playUrl("http://x/a.mp4'; reboot; echo '")
        assertEquals(
            "am start -a android.intent.action.VIEW -d 'http://x/a.mp4'\\''; reboot; echo '\\''' -t 'video/*'",
            shell.commands.single(),
        )
    }

    @Test
    fun `text spaces are encoded for input text`() = runTest {
        val shell = RecordingShell()
        FireTvController(device, shell).sendText("le film")
        assertEquals("input text 'le%sfilm'", shell.commands.single())
    }

    @Test(expected = IllegalArgumentException::class)
    fun `launchApp rejects suspicious package names`() = runTest {
        FireTvController(device, RecordingShell()).launchApp("com.app; reboot")
    }
}
