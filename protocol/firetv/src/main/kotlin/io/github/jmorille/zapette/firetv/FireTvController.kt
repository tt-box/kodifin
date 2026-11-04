package io.github.jmorille.zapette.firetv

import io.github.jmorille.zapette.model.Capability
import io.github.jmorille.zapette.model.Device
import io.github.jmorille.zapette.model.RemoteController
import io.github.jmorille.zapette.model.RemoteKey
import io.github.jmorille.zapette.model.requireKey

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
        // `input text` interprète les espaces comme séparateurs : on les encode en %s.
        shell.shell("input text ${shellQuote(text.replace(" ", "%s"))}")
    }

    override suspend fun playUrl(url: String, title: String?) {
        shell.shell("am start -a android.intent.action.VIEW -d ${shellQuote(url)} -t ${shellQuote(mimeFor(url))}")
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

        /** Protège un argument pour `sh` : entoure de quotes simples et échappe celles du texte. */
        fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

        fun mimeFor(url: String): String =
            when (url.substringBefore('?').substringAfterLast('.', "").lowercase()) {
                "mp3", "aac", "m4a", "flac", "ogg" -> "audio/*"
                "jpg", "jpeg", "png" -> "image/*"
                else -> "video/*"
            }
    }
}
