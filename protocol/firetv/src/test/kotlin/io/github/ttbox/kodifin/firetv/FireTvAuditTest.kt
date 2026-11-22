package io.github.ttbox.kodifin.firetv

import io.github.ttbox.kodifin.model.Device
import io.github.ttbox.kodifin.model.DeviceKind
import io.github.ttbox.kodifin.model.RemoteError
import io.github.ttbox.kodifin.model.RemoteException
import io.github.ttbox.kodifin.model.Setup
import io.github.ttbox.kodifin.model.UnsupportedAction
import io.github.ttbox.kodifin.model.UnsupportedActionException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

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

    @Test
    fun `non latin characters are rejected instead of being silently lost`() {
        try {
            FireTvController.textCommands("映画", kind = DeviceKind.FIRE_TV)
            fail("une erreur était attendue")
        } catch (e: UnsupportedActionException) {
            assertEquals(UnsupportedAction.Characters("映画"), e.action)
            assertEquals(DeviceKind.FIRE_TV, e.kind)
        }
    }

    @Test
    fun `sendText reports unsupported characters with the device kind`() = runTest {
        val shell = object : AdbShell {
            override suspend fun shell(command: String) = ""
        }
        try {
            FireTvController(Device("f", "Fire TV", DeviceKind.FIRE_TV, "10.0.0.12"), shell).sendText("a映")
            fail("une erreur était attendue")
        } catch (e: UnsupportedActionException) {
            assertEquals(UnsupportedAction.Characters("映"), e.action)
            assertEquals(DeviceKind.FIRE_TV, e.kind)
        }
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
            assertEquals(RemoteError.DeviceRefused(detail = "Error: Activity not started, unable to resolve Intent"), e.reason)
        }
    }
}

class FireTvUrlTest {
    @Test
    fun `blank url is an invalid address and sends nothing`() = runTest {
        val commands = mutableListOf<String>()
        val shell = object : AdbShell {
            override suspend fun shell(command: String) = "".also { commands += command }
        }
        try {
            FireTvController(Device("f", "Fire TV", DeviceKind.FIRE_TV, "10.0.0.12"), shell).playUrl("  ")
            fail("une erreur était attendue")
        } catch (e: RemoteException) {
            assertEquals(RemoteError.InvalidAddress(), e.reason)
        }
        assertTrue(commands.isEmpty())
    }
}

class AdbIdentityLockTest {
    @Test
    fun `identity loads concurrently with key regeneration under the same lock`() {
        val dir = Files.createTempDirectory("adbid").toFile()
        val pool = java.util.concurrent.Executors.newFixedThreadPool(4)
        try {
            AdbKeys.load(dir)
            val tasks = (1..8).map { i ->
                pool.submit<Unit> {
                    if (i % 2 == 0) {
                        // Régénération forcée (paire corrompue) pendant les lectures de l'identité.
                        AdbKeys.locked(dir) { File(dir, "adbkey").writeText("corrompu"); AdbKeys.load(dir) }
                    } else {
                        val identity = AdbIdentity.load(dir)
                        assertTrue(identity.adbPublicKey.isNotBlank())
                    }
                }
            }
            tasks.forEach { it.get(60, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
            dir.deleteRecursively()
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
        val shell = DadbShell("tv:5555") { opened++; throw IOException("refusé") }
        val e = failure { shell.shell("echo") }
        assertEquals(1, opened)
        assertEquals(RemoteError.SetupRequired(Setup.ADB_DEBUGGING), e.reason)
        assertTrue(e.cause is IOException)
    }

    @Test
    fun `connection errors are told apart from an authorization refusal`() = runTest {
        assertEquals(RemoteError.Timeout, failure { DadbShell("tv:5555") { throw SocketTimeoutException() }.shell("echo") }.reason)
        assertEquals(RemoteError.Unreachable("tv"), failure { DadbShell("tv:5555") { throw UnknownHostException() }.shell("echo") }.reason)
        assertEquals(RemoteError.Unreachable("tv"), failure { DadbShell("tv:5555") { throw NoRouteToHostException() }.shell("echo") }.reason)
        assertEquals(
            RemoteError.SetupRequired(Setup.ADB_DEBUGGING),
            failure { DadbShell("tv:5555") { throw ConnectException("refusé") }.shell("echo") }.reason,
        )
        // Commande coupée sur une connexion neuve : ni refus d'autorisation, ni nouvel essai.
        assertEquals(
            RemoteError.Interrupted("tv"),
            failure { DadbShell("tv:5555") { FakeLink { throw IOException("coupé") } }.shell("echo") }.reason,
        )
    }

    @Test
    fun `a read timeout on a kept connection is not replayed`() = runTest {
        val commands = mutableListOf<String>()
        var calls = 0
        val shell = DadbShell("tv") {
            FakeLink { commands += it; calls++; if (calls == 2) throw SocketTimeoutException("Read timed out") else ShellResult("", 0) }
        }
        shell.shell("echo")
        val e = failure { shell.shell("am start x") }
        assertEquals(RemoteError.Timeout, e.reason)
        // La commande a pu être exécutée par le téléviseur : elle n'est pas rejouée.
        assertEquals(listOf("echo", "am start x"), commands)
    }

    @Test
    fun `non zero exit code becomes a RemoteException`() = runTest {
        val shell = DadbShell("tv") { FakeLink { ShellResult("monkey aborted", 252) } }
        try {
            shell.shell("monkey -p x.y 1")
            fail("une erreur était attendue")
        } catch (e: RemoteException) {
            assertEquals(RemoteError.DeviceRefused(detail = "monkey aborted", code = "252"), e.reason)
        }
    }

    @Test
    fun `close drops the connection and no command reopens it`() = runTest {
        val links = mutableListOf<FakeLink>()
        val shell = DadbShell("tv") { FakeLink { ShellResult("", 0) }.also { links += it } }
        shell.shell("echo")
        shell.close()
        assertTrue(links.single().closed)
        assertEquals(RemoteError.Interrupted("tv"), failure { shell.shell("echo") }.reason)
        assertEquals(1, links.size)
    }

    @Test
    fun `close during a command fails it without reconnecting`() = runBlocking {
        val started = CountDownLatch(1)
        val links = mutableListOf<BlockingLink>()
        var calls = 0
        val shell = DadbShell("tv") {
            BlockingLink { calls++; if (calls == 1) ShellResult("", 0) else { started.countDown(); null } }.also { links += it }
        }
        shell.shell("echo")
        val pending = async(Dispatchers.IO) { runCatching { shell.shell("input text a") }.exceptionOrNull() }
        assertTrue(started.await(5, TimeUnit.SECONDS))
        shell.close()
        val e = withTimeout(5_000) { pending.await() }
        assertEquals(RemoteError.Interrupted("tv"), (e as RemoteException).reason)
        // Pas de nouvelle connexion ouverte (elle ne serait jamais fermée), pas de second envoi.
        assertEquals(1, links.size)
        assertEquals(2, calls)
    }

    @Test
    fun `a connection opened while closing is closed at once`() = runBlocking {
        val connecting = CountDownLatch(1)
        val release = CountDownLatch(1)
        val links = mutableListOf<FakeLink>()
        val shell = DadbShell("tv") {
            connecting.countDown()
            release.await(5, TimeUnit.SECONDS)
            FakeLink { ShellResult("", 0) }.also { links += it }
        }
        val pending = async(Dispatchers.IO) { runCatching { shell.shell("echo") }.exceptionOrNull() }
        assertTrue(connecting.await(5, TimeUnit.SECONDS))
        shell.close()
        release.countDown()
        val e = withTimeout(5_000) { pending.await() }
        assertEquals(RemoteError.Interrupted("tv"), (e as RemoteException).reason)
        assertTrue(links.single().closed)
    }

    private inline fun failure(block: () -> Unit): RemoteException {
        try {
            block()
        } catch (e: RemoteException) {
            return e
        }
        fail("une erreur était attendue")
        error("inatteignable")
    }
}

/** Lien dont la commande reste bloquée (réponse `null`) jusqu'à sa fermeture, comme un socket. */
private class BlockingLink(private val behaviour: () -> ShellResult?) : AdbLink {
    private val closedLatch = CountDownLatch(1)
    override fun shell(command: String): ShellResult {
        behaviour()?.let { return it }
        closedLatch.await(5, TimeUnit.SECONDS)
        throw SocketException("Socket closed")
    }

    override fun close() = closedLatch.countDown()
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
