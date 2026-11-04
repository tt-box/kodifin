package io.github.jmorille.zapette.firetv

import io.github.jmorille.zapette.model.Device
import io.github.jmorille.zapette.model.DeviceKind
import io.github.jmorille.zapette.model.RemoteException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files

private class FakeLink(private val behaviour: (String) -> ShellResult) : AdbLink {
    var closed = false
    override fun shell(command: String): ShellResult = behaviour(command)
    override fun close() {
        closed = true
    }
}

class FireTvTextTest {
    @Test
    fun `literal percent s is split so input text does not turn it into a space`() {
        assertEquals(
            listOf("input text '100%'", "input text 'sur%s100'"),
            FireTvController.textCommands("100%sur 100"),
        )
    }

    @Test
    fun `accents are stripped and quotes stay quoted`() {
        assertEquals(listOf("input text 'tele%sl'\\''ete'"), FireTvController.textCommands("télé l'été"))
    }

    @Test
    fun `new lines become enter key and empty text sends nothing`() {
        assertEquals(
            listOf("input text 'a'", "input keyevent 66", "input text 'b'"),
            FireTvController.textCommands("a\r\nb"),
        )
        assertTrue(FireTvController.textCommands("").isEmpty())
    }

    @Test(expected = RemoteException::class)
    fun `non latin characters are rejected instead of being silently lost`() {
        FireTvController.textCommands("映画")
    }

    @Test
    fun `mime type ignores fragments`() {
        assertEquals("audio/*", FireTvController.mimeFor("http://nas/a.mp3#t=10"))
        assertEquals("audio/*", FireTvController.mimeFor("http://nas/a.OPUS?x=1"))
        assertEquals("image/*", FireTvController.mimeFor("http://nas/p.webp"))
        assertEquals("video/*", FireTvController.mimeFor("http://nas/live.m3u8"))
        assertEquals("video/*", FireTvController.mimeFor("http://nas.local/stream"))
    }

    @Test
    fun `am start errors are reported`() = runTest {
        val shell = object : AdbShell {
            override suspend fun shell(command: String) = "Starting: Intent { }\nError: Activity not started, unable to resolve Intent"
        }
        try {
            FireTvController(Device("f", "Fire TV", DeviceKind.FIRE_TV, "10.0.0.12"), shell).playUrl("http://x/a.mp4")
            fail("une erreur était attendue")
        } catch (e: RemoteException) {
            assertTrue(e.message!!.contains("unable to resolve"))
        }
    }
}

class DadbShellTest {
    @Test
    fun `a stale kept connection is replaced and the command retried once`() = runTest {
        val links = mutableListOf<FakeLink>()
        var calls = 0
        val shell = DadbShell("tv") {
            FakeLink { calls++; if (calls == 2) throw IOException("socket mort") else ShellResult("ok", 0) }.also { links += it }
        }
        assertEquals("ok", shell.shell("echo 1"))
        assertEquals("ok", shell.shell("echo 2"))
        assertEquals(2, links.size)
        assertTrue(links[0].closed)
        assertFalse(links[1].closed)
    }

    @Test
    fun `a fresh connection failure is not retried`() = runTest {
        var opened = 0
        val shell = DadbShell("tv") { opened++; FakeLink { throw IOException("refusé") } }
        try {
            shell.shell("echo")
            fail("une erreur était attendue")
        } catch (e: RemoteException) {
            assertEquals(1, opened)
        }
    }

    @Test
    fun `non zero exit code becomes a RemoteException`() = runTest {
        val shell = DadbShell("tv") { FakeLink { ShellResult("monkey aborted", 252) } }
        try {
            shell.shell("monkey -p x.y 1")
            fail("une erreur était attendue")
        } catch (e: RemoteException) {
            assertTrue(e.message!!.contains("252"))
        }
    }

    @Test
    fun `close drops the connection and the next command reconnects`() = runTest {
        val links = mutableListOf<FakeLink>()
        val shell = DadbShell("tv") { FakeLink { ShellResult("", 0) }.also { links += it } }
        shell.shell("echo")
        shell.close()
        assertTrue(links.single().closed)
        shell.shell("echo")
        assertEquals(2, links.size)
    }
}

class AdbKeysTest {
    @Test
    fun `key pair is generated once then reused, and regenerated when corrupt`() {
        val dir = Files.createTempDirectory("adbkeys").toFile()
        try {
            AdbKeys.load(dir)
            val private = File(dir, "adbkey")
            val first = private.readText()
            AdbKeys.load(dir)
            assertEquals(first, private.readText())
            private.writeText("corrompu")
            AdbKeys.load(dir)
            assertTrue(private.readText().contains("PRIVATE KEY"))
            assertFalse(File(dir, "adbkey.tmp").exists())
        } finally {
            dir.deleteRecursively()
        }
    }
}
