package io.github.ttbox.kodifin.firetv

import io.github.ttbox.kodifin.model.Capability
import io.github.ttbox.kodifin.model.Device
import io.github.ttbox.kodifin.model.DeviceKind
import io.github.ttbox.kodifin.model.MediaTypes
import io.github.ttbox.kodifin.model.RemoteController
import io.github.ttbox.kodifin.model.RemoteError
import io.github.ttbox.kodifin.model.RemoteException
import io.github.ttbox.kodifin.model.RemoteKey
import io.github.ttbox.kodifin.model.UnsupportedAction
import io.github.ttbox.kodifin.model.UnsupportedActionException
import io.github.ttbox.kodifin.model.requireKey
import java.text.Normalizer

/** Exécution de commandes shell sur l'appareil (ADB). Abstraite pour les tests. */
interface AdbShell {
    suspend fun shell(command: String): String
    suspend fun close() {}
}

/**
 * Fire TV (et Android TV / Google TV) via le débogage ADB réseau (port 5555).
 * Prérequis côté téléviseur : options développeur → débogage ADB activé, puis
 * acceptation de la clé de Kodifin à la première connexion.
 */
class FireTvController(
    override val device: Device,
    private val shell: AdbShell,
) : RemoteController {

    // Pas de [Capability.VOLUME] : le volume ne se règle que par les touches VOLUME_UP/DOWN
    // (keyevents), il n'est ni lisible ni réglable en absolu (comme Roku).
    override val capabilities = setOf(
        Capability.NAVIGATION, Capability.PLAYBACK, Capability.PLAY_URL, Capability.TEXT_INPUT,
    )
    override val supportedKeys = KEYCODES.keys

    /** Android 5.x : le script `input` réinterprète son argument (voir [textCommands]). */
    @Volatile
    private var legacyInput = false

    override suspend fun connect() {
        // Vérifie la connexion et repère la version d'Android pour la saisie de texte.
        val sdk = shell.shell("getprop ro.build.version.sdk").trim().toIntOrNull()
        legacyInput = sdk != null && sdk <= LEGACY_INPUT_MAX_SDK
    }

    override suspend fun send(key: RemoteKey) {
        requireKey(key)
        shell.shell("input keyevent ${KEYCODES.getValue(key)}")
    }

    override suspend fun sendText(text: String) {
        textCommands(text, legacyInput, device.kind).forEach { shell.shell(it) }
    }

    override suspend fun playUrl(url: String, title: String?) {
        require(url.isNotBlank()) { "URL vide" }
        val output = shell.shell(
            "am start -a android.intent.action.VIEW -d ${shellQuote(url)} -t ${shellQuote(mimeFor(url))}",
        )
        // `am start` renvoie 0 même quand aucune application ne sait ouvrir l'URL.
        output.lineSequence().firstOrNull { it.startsWith("Error") }?.let {
            throw RemoteException(RemoteError.DeviceRefused(detail = it.trim()))
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

        /** Dernière version (Android 5.1) dont le script `input` développe `$*` sans guillemets. */
        const val LEGACY_INPUT_MAX_SDK = 22

        /**
         * Sous Android 5.x, `/system/bin/input` relance la classe Java avec `$*` non protégé :
         * l'argument subit un second découpage et un développement des jokers (`*`, `?`, `[`),
         * « ? » devenant par exemple le nom d'un fichier de la racine. On appelle alors la
         * classe directement, comme le fait ce script, avec un argument correctement protégé.
         */
        private const val LEGACY_INPUT =
            "CLASSPATH=/system/framework/input.jar app_process /system/bin com.android.commands.input.Input"

        /**
         * Traduit un texte en commandes `input text`. Deux niveaux d'interprétation :
         * - le shell `sh -c` de l'appareil : chaque morceau est entouré de quotes simples
         *   ([shellQuote]), ce qui neutralise espaces, `&`, `;`, `|`, `<`, `>`, `(`, `)`, `$`,
         *   `` ` ``, `\`, `"`, `*`, `?`… ; la quote simple elle-même s'écrit `'\''` ;
         * - `input text` (InputShellCommand, AOSP) : seule la séquence `%s` est spéciale et
         *   devient un espace ; un `%` suivi d'autre chose reste littéral. L'espace (et la
         *   tabulation) s'écrit donc `%s`, et une séquence « %s » réellement tapée est coupée
         *   entre `%` et `s` en deux commandes, sans quoi elle deviendrait un espace.
         *
         * Seul l'ASCII imprimable est accepté : les accents sont retirés (« télé » → « tele »),
         * les autres caractères sont refusés plutôt que d'être perdus silencieusement ; un
         * retour à la ligne devient la touche Entrée. [kind] : type d'appareil porté par l'erreur.
         */
        fun textCommands(text: String, legacyInput: Boolean = false, kind: DeviceKind? = null): List<String> {
            val ascii = Normalizer.normalize(text, Normalizer.Form.NFD).replace(COMBINING_MARKS, "")
            val unsupported = ascii.filter { it != '\n' && it != '\r' && it != '\t' && it !in ' '..'~' }
            if (unsupported.isNotEmpty()) {
                throw UnsupportedActionException(
                    UnsupportedAction.Characters(unsupported.toSet().joinToString("")),
                    kind,
                )
            }
            val input = if (legacyInput) LEGACY_INPUT else "input"
            val commands = mutableListOf<String>()
            ascii.replace("\r\n", "\n").replace('\r', '\n').split('\n').forEachIndexed { index, line ->
                if (index > 0) commands += "input keyevent $KEYCODE_ENTER"
                inputTextChunks(line).forEach { commands += "$input text ${shellQuote(it)}" }
            }
            return commands
        }

        /**
         * Découpe une ligne en arguments pour `input text` : espaces encodés `%s`, et nouvel
         * argument chaque fois qu'un `%` littéral précède un `s` littéral.
         */
        internal fun inputTextChunks(line: String): List<String> {
            val chunks = mutableListOf<String>()
            val chunk = StringBuilder()
            var previous: Char? = null
            for (c in line.replace('\t', ' ')) {
                if (c == 's' && previous == '%') {
                    chunks += chunk.toString()
                    chunk.clear()
                }
                if (c == ' ') chunk.append("%s") else chunk.append(c)
                previous = c
            }
            if (chunk.isNotEmpty()) chunks += chunk.toString()
            return chunks
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
