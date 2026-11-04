package io.github.jmorille.zapette.firetv

import io.github.jmorille.zapette.model.Device
import io.github.jmorille.zapette.model.DeviceKind
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Simule l'appareil : `sh -c` (mots, quotes simples/doubles, antislash), puis le décodage
 * de `input text` (InputShellCommand d'AOSP : `%s` → espace). Toute métacaractère non
 * protégé fait échouer la simulation au lieu d'être interprété.
 */
private object DeviceSimulator {
    fun typed(commands: List<String>): String = buildString {
        for (command in commands) {
            val words = shellWords(command)
            when {
                words.take(2) == listOf("input", "keyevent") -> append('\n')
                words.take(2) == listOf("input", "text") -> {
                    assertEquals("un seul argument attendu : $command", 3, words.size)
                    append(decodeInputText(words[2]))
                }
                words.firstOrNull()?.startsWith("CLASSPATH=") == true -> {
                    assertEquals(listOf("app_process", "/system/bin", "com.android.commands.input.Input", "text"), words.subList(1, 5))
                    assertEquals(6, words.size)
                    append(decodeInputText(words[5]))
                }
                else -> error("commande inattendue : $command")
            }
        }
    }

    /** Découpage POSIX minimal ; échoue sur tout métacaractère hors guillemets. */
    fun shellWords(command: String): List<String> {
        val words = mutableListOf<String>()
        val current = StringBuilder()
        var inWord = false
        var i = 0
        while (i < command.length) {
            val c = command[i]
            when {
                c == ' ' -> {
                    if (inWord) words += current.toString()
                    current.clear()
                    inWord = false
                }
                c == '\'' -> {
                    val end = command.indexOf('\'', i + 1)
                    require(end >= 0) { "quote non fermée : $command" }
                    current.append(command, i + 1, end)
                    inWord = true
                    i = end
                }
                c == '\\' -> {
                    current.append(command[i + 1])
                    inWord = true
                    i++
                }
                c in "&;|<>()\$`\"*?[#~\t\n" -> error("métacaractère non protégé '$c' dans : $command")
                else -> {
                    current.append(c)
                    inWord = true
                }
            }
            i++
        }
        if (inWord) words += current.toString()
        return words
    }

    /** Copie de l'algorithme public d'InputShellCommand.sendText. */
    fun decodeInputText(text: String): String {
        val buff = StringBuilder(text)
        var escape = false
        var i = 0
        while (i < buff.length) {
            if (escape) {
                escape = false
                if (buff[i] == 's') {
                    buff.setCharAt(i, ' ')
                    buff.deleteCharAt(--i)
                }
            }
            if (buff[i] == '%') escape = true
            i++
        }
        return buff.toString()
    }
}

class FireTvTextEscapingTest {
    private val cases = listOf(
        "le film",
        "%s",
        "100%sur 100",
        "%%s",
        "%%%s%",
        "% s",
        "a%",
        "%",
        "s%s%s",
        "a  b",
        "it's",
        "\"guillemets\"",
        "a & b; c | d",
        "<balise> (parenthèse)",
        "\$HOME `id` \$(reboot)",
        "anti\\slash\\",
        "*?[a-z]#~",
        "'; reboot; echo '",
        "-d 1",
        "tab\tulation",
        "café crème",
        " ",
        "x\ny",
    )

    @Test
    fun `every special character is typed literally`() {
        for (legacy in listOf(false, true)) {
            for (text in cases) {
                val expected = text.replace("é", "e").replace("è", "e").replace('\t', ' ')
                val commands = FireTvController.textCommands(text, legacy)
                assertEquals("« $text » (legacy=$legacy) → $commands", expected, DeviceSimulator.typed(commands))
            }
        }
    }

    @Test
    fun `table of generated commands`() {
        val table = mapOf(
            "%s" to listOf("input text '%'", "input text 's'"),
            "a b" to listOf("input text 'a%sb'"),
            "% s" to listOf("input text '%%ss'"),
            "%%s" to listOf("input text '%%'", "input text 's'"),
            "it's" to listOf("input text 'it'\\''s'"),
            "a&b" to listOf("input text 'a&b'"),
            "\$(x)" to listOf("input text '\$(x)'"),
            "\\" to listOf("input text '\\'"),
        )
        table.forEach { (text, expected) -> assertEquals(text, expected, FireTvController.textCommands(text)) }
    }

    @Test
    fun `android 5 bypasses the input script that re-expands its arguments`() {
        assertEquals(
            listOf("CLASSPATH=/system/framework/input.jar app_process /system/bin com.android.commands.input.Input text '?'"),
            FireTvController.textCommands("?", legacyInput = true),
        )
    }

    @Test
    fun `controller detects the android version at connection`() = runTest {
        val commands = mutableListOf<String>()
        fun shell(sdk: String) = object : AdbShell {
            override suspend fun shell(command: String): String {
                commands += command
                return if (command.startsWith("getprop")) "$sdk\n" else ""
            }
        }
        val device = Device("f", "Fire TV", DeviceKind.FIRE_TV, "10.0.0.12")
        FireTvController(device, shell("22")).apply { connect(); sendText("a") }
        assertTrue(commands.last(), commands.last().startsWith("CLASSPATH="))
        FireTvController(device, shell("30")).apply { connect(); sendText("a") }
        assertEquals("input text 'a'", commands.last())
    }
}

class DadbShellTimeoutTest {
    @Test
    fun `device timeout drives connection and read timeouts`() {
        assertEquals(3_000 to DadbShell.KEY_APPROVAL_TIMEOUT_MS, DadbShell.timeouts(3_000, tls = false))
        assertEquals(60_000 to 60_000, DadbShell.timeouts(60_000, tls = false))
        assertEquals(3_000 to DadbShell.MIN_READ_TIMEOUT_MS, DadbShell.timeouts(3_000, tls = true))
        assertEquals(20_000 to 20_000, DadbShell.timeouts(20_000, tls = true))
    }
}
