package io.github.jmorille.zapette.firetv

import io.github.jmorille.zapette.model.Capability
import io.github.jmorille.zapette.model.Device
import io.github.jmorille.zapette.model.MediaTypes
import io.github.jmorille.zapette.model.RemoteController
import io.github.jmorille.zapette.model.RemoteException
import io.github.jmorille.zapette.model.RemoteKey
import io.github.jmorille.zapette.model.requireKey
import java.text.Normalizer

/** Exécution de commandes shell sur l'appareil (ADB). Abstraite pour les tests. */
interface AdbShell {
    suspend fun shell(command: String): String
    suspend fun close() {}
}

/**
 * Fire TV (et Android TV / Google TV) via le débogage ADB réseau (port 5555).
 * Prérequis côté téléviseur : options développeur → débogage ADB activé, puis
 * acceptation de la clé de Zapette à la première connexion.
 */
class FireTvController(
    override val device: Device,
    private val shell: AdbShell,
) : RemoteController {

    override val capabilities = setOf(
        Capability.NAVIGATION, Capability.PLAYBACK, Capability.VOLUME, Capability.PLAY_URL, Capability.TEXT_INPUT,
    )
    override val supportedKeys = KEYCODES.keys

    override suspend fun connect() {
        shell.shell("echo ok")
    }

    override suspend fun send(key: RemoteKey) {
        requireKey(key)
        shell.shell("input keyevent ${KEYCODES.getValue(key)}")
    }

    override suspend fun sendText(text: String) {
        textCommands(text).forEach { shell.shell(it) }
    }

    override suspend fun playUrl(url: String, title: String?) {
        require(url.isNotBlank()) { "URL vide" }
        val output = shell.shell(
            "am start -a android.intent.action.VIEW -d ${shellQuote(url)} -t ${shellQuote(mimeFor(url))}",
        )
        // `am start` renvoie 0 même quand aucune application ne sait ouvrir l'URL.
        output.lineSequence().firstOrNull { it.startsWith("Error") }?.let {
            throw RemoteException("Lecture impossible sur ${device.name} : ${it.trim()}")
        }
    }

    suspend fun launchApp(packageName: String) {
        require(PACKAGE_NAME.matches(packageName)) { "Nom de paquet invalide" }
        shell.shell("monkey -p $packageName -c android.intent.category.LAUNCHER 1")
    }

    override suspend fun disconnect() = shell.close()

    companion object {
        private val PACKAGE_NAME = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+$")

        /** Codes `KeyEvent` Android. */
        val KEYCODES: Map<RemoteKey, Int> = mapOf(
            RemoteKey.UP to 19,
            RemoteKey.DOWN to 20,
            RemoteKey.LEFT to 21,
            RemoteKey.RIGHT to 22,
            RemoteKey.SELECT to 23,
            RemoteKey.BACK to 4,
            RemoteKey.HOME to 3,
            RemoteKey.MENU to 82,
            RemoteKey.INFO to 165,
            RemoteKey.PLAY_PAUSE to 85,
            RemoteKey.STOP to 86,
            RemoteKey.NEXT to 87,
            RemoteKey.PREVIOUS to 88,
            RemoteKey.REWIND to 89,
            RemoteKey.FAST_FORWARD to 90,
            RemoteKey.VOLUME_UP to 24,
            RemoteKey.VOLUME_DOWN to 25,
            RemoteKey.MUTE to 164,
        )

        private const val KEYCODE_ENTER = 66

        /** Protège un argument pour `sh` : entoure de quotes simples et échappe celles du texte. */
        fun shellQuote(value: String): String {
            require('\u0000' !in value) { "Caractère nul interdit" }
            return "'" + value.replace("'", "'\\''") + "'"
        }

        /**
         * Traduit un texte en commandes `input text`. Contraintes de `input text` :
         * - l'espace s'écrit `%s` et toute séquence `%s` littérale devient un espace, d'où la
         *   coupure entre `%` et `s` en deux commandes ;
         * - seul l'ASCII imprimable est accepté : les accents sont retirés (« télé » → « tele »),
         *   les autres caractères sont refusés plutôt que d'être perdus silencieusement ;
         * - un retour à la ligne devient la touche Entrée.
         */
        fun textCommands(text: String): List<String> {
            val ascii = Normalizer.normalize(text, Normalizer.Form.NFD).replace(COMBINING_MARKS, "")
            val unsupported = ascii.filter { it != '\n' && it != '\r' && it != '\t' && it !in ' '..'~' }
            if (unsupported.isNotEmpty()) {
                throw RemoteException(
                    "Caractères non pris en charge par la saisie ADB : ${unsupported.toSet().joinToString("")}",
                )
            }
            val commands = mutableListOf<String>()
            ascii.replace("\r\n", "\n").replace('\r', '\n').split('\n').forEachIndexed { index, line ->
                if (index > 0) commands += "input keyevent $KEYCODE_ENTER"
                val chunk = StringBuilder()
                var previous: Char? = null
                for (c in line.replace('\t', ' ')) {
                    if (c == 's' && previous == '%') {
                        commands += "input text ${shellQuote(chunk.toString())}"
                        chunk.clear()
                    }
                    if (c == ' ') chunk.append("%s") else chunk.append(c)
                    previous = c
                }
                if (chunk.isNotEmpty()) commands += "input text ${shellQuote(chunk.toString())}"
            }
            return commands
        }

        private val COMBINING_MARKS = Regex("\\p{Mn}+")

        // Type générique (audio, image ou vidéo, sous-type joker) attendu par l'intention VIEW.
        fun mimeFor(url: String): String {
            val mime = MediaTypes.forUrl(url)
            return when {
                MediaTypes.isAudio(mime) -> "audio/*"
                MediaTypes.isImage(mime) -> "image/*"
                else -> "video/*"
            }
        }
    }
}
