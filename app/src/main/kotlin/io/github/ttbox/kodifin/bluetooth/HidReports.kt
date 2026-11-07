package io.github.ttbox.kodifin.bluetooth

import androidx.annotation.StringRes
import io.github.ttbox.kodifin.R
import io.github.ttbox.kodifin.model.RemoteKey
import io.github.ttbox.kodifin.text.UiText

/**
 * Touche envoyée en Bluetooth HID (spécification publique « HID Usage Tables » de l'USB-IF) :
 * clavier (page 0x07) ou contrôle multimédia (page 0x0C, « Consumer »).
 */
sealed interface HidKey {
    data class Keyboard(val usage: Int, val shift: Boolean = false, val altGr: Boolean = false) : HidKey
    data class Consumer(val usage: Int) : HidKey
}

/** Rapport HID prêt à envoyer : identifiant de rapport et charge utile (sans l'identifiant). */
class HidReport(val id: Int, val data: ByteArray) {
    override fun equals(other: Any?) = other is HidReport && other.id == id && other.data.contentEquals(data)
    override fun hashCode() = 31 * id + data.contentHashCode()
    override fun toString() = "HidReport($id, ${data.joinToString(" ") { "%02x".format(it) }})"
}

/** Disposition du clavier configurée sur la TV : le HID transmet des positions de touches, pas des caractères. */
enum class KeyboardLayout(@param:StringRes val labelRes: Int) {
    QWERTY_US(R.string.sys_keyboard_layout_qwerty_us),
    AZERTY_FR(R.string.sys_keyboard_layout_azerty_fr),
    ;

    /** Libellé affiché dans le sélecteur de disposition. */
    val text: UiText get() = UiText.Res(labelRes)
}

/** Texte converti en touches ; [skipped] : caractères sans touche dans la disposition. */
data class TypedText(val keys: List<HidKey>, val skipped: Set<Char>)

/**
 * Clavier + télécommande multimédia HID : descripteur de rapport, rapports d'appui et de
 * relâchement, correspondance des touches de la télécommande et saisie de texte.
 */
object HidReports {
    const val KEYBOARD_REPORT_ID = 1
    const val CONSUMER_REPORT_ID = 2

    private const val MOD_LEFT_SHIFT = 0x02
    private const val MOD_RIGHT_ALT = 0x40

    /**
     * Descripteur : clavier « boot » à 6 touches (rapport 1) et contrôle multimédia 16 bits
     * (rapport 2), comme un clavier sans fil avec touches média.
     */
    val DESCRIPTOR: ByteArray = bytes(
        // Clavier
        0x05, 0x01, // Usage Page (Generic Desktop)
        0x09, 0x06, // Usage (Keyboard)
        0xA1, 0x01, // Collection (Application)
        0x85, KEYBOARD_REPORT_ID, // Report ID
        0x05, 0x07, //   Usage Page (Keyboard/Keypad)
        0x19, 0xE0, 0x29, 0xE7, // Usage Min/Max : modificateurs
        0x15, 0x00, 0x25, 0x01, // Logical 0..1
        0x75, 0x01, 0x95, 0x08, // 8 bits
        0x81, 0x02, //   Input (Data, Var, Abs)
        0x75, 0x08, 0x95, 0x01, // 1 octet réservé
        0x81, 0x01, //   Input (Const)
        0x05, 0x08, //   Usage Page (LEDs)
        0x19, 0x01, 0x29, 0x05, // 5 voyants
        0x75, 0x01, 0x95, 0x05,
        0x91, 0x02, //   Output (Data, Var, Abs)
        0x75, 0x03, 0x95, 0x01,
        0x91, 0x01, //   Output (Const) : bourrage
        0x05, 0x07, //   Usage Page (Keyboard/Keypad)
        0x19, 0x00, 0x29, 0x65, // touches 0..101
        0x15, 0x00, 0x25, 0x65,
        0x75, 0x08, 0x95, 0x06, // 6 touches simultanées
        0x81, 0x00, //   Input (Data, Array)
        0xC0, // End Collection
        // Contrôle multimédia
        0x05, 0x0C, // Usage Page (Consumer)
        0x09, 0x01, // Usage (Consumer Control)
        0xA1, 0x01, // Collection (Application)
        0x85, CONSUMER_REPORT_ID, // Report ID
        0x15, 0x00, 0x26, 0xFF, 0x03, // Logical 0..1023
        0x19, 0x00, 0x2A, 0xFF, 0x03, // Usage 0..1023
        0x75, 0x10, 0x95, 0x01, // 1 × 16 bits
        0x81, 0x00, //   Input (Data, Array)
        0xC0, // End Collection
    )

    fun press(key: HidKey): HidReport = when (key) {
        is HidKey.Keyboard -> {
            var mods = 0
            if (key.shift) mods = mods or MOD_LEFT_SHIFT
            if (key.altGr) mods = mods or MOD_RIGHT_ALT
            HidReport(KEYBOARD_REPORT_ID, byteArrayOf(mods.toByte(), 0, key.usage.toByte(), 0, 0, 0, 0, 0))
        }
        is HidKey.Consumer -> HidReport(CONSUMER_REPORT_ID, byteArrayOf((key.usage and 0xFF).toByte(), (key.usage shr 8).toByte()))
    }

    fun release(key: HidKey): HidReport = when (key) {
        is HidKey.Keyboard -> HidReport(KEYBOARD_REPORT_ID, ByteArray(8))
        is HidKey.Consumer -> HidReport(CONSUMER_REPORT_ID, ByteArray(2))
    }

    // Pages HID : clavier (0x07) et multimédia (0x0C).
    val ENTER = HidKey.Keyboard(0x28)
    val ESCAPE = HidKey.Keyboard(0x29)
    val BACKSPACE = HidKey.Keyboard(0x2A)
    val KEY_HOME = HidKey.Keyboard(0x4A)
    val APPLICATION = HidKey.Keyboard(0x65)
    val AC_HOME = HidKey.Consumer(0x223)
    val AC_BACK = HidKey.Consumer(0x224)
    val CONSUMER_MENU = HidKey.Consumer(0x40)
    val POWER = HidKey.Consumer(0x30)
    val CHANNEL_UP = HidKey.Consumer(0x9C)
    val CHANNEL_DOWN = HidKey.Consumer(0x9D)

    /** Touche HID de chaque touche de télécommande (les variantes se testent depuis l'écran dédié). */
    fun keyFor(key: RemoteKey): HidKey = when (key) {
        RemoteKey.UP -> HidKey.Keyboard(0x52)
        RemoteKey.DOWN -> HidKey.Keyboard(0x51)
        RemoteKey.LEFT -> HidKey.Keyboard(0x50)
        RemoteKey.RIGHT -> HidKey.Keyboard(0x4F)
        RemoteKey.SELECT -> ENTER
        RemoteKey.BACK -> AC_BACK
        RemoteKey.HOME -> AC_HOME
        RemoteKey.MENU -> CONSUMER_MENU
        RemoteKey.INFO -> APPLICATION
        RemoteKey.PLAY_PAUSE -> HidKey.Consumer(0xCD)
        RemoteKey.STOP -> HidKey.Consumer(0xB7)
        RemoteKey.NEXT -> HidKey.Consumer(0xB5)
        RemoteKey.PREVIOUS -> HidKey.Consumer(0xB6)
        RemoteKey.REWIND -> HidKey.Consumer(0xB4)
        RemoteKey.FAST_FORWARD -> HidKey.Consumer(0xB3)
        RemoteKey.VOLUME_UP -> HidKey.Consumer(0xE9)
        RemoteKey.VOLUME_DOWN -> HidKey.Consumer(0xEA)
        RemoteKey.MUTE -> HidKey.Consumer(0xE2)
    }

    /** Touches à taper pour [text] dans la disposition [layout] de la TV. */
    fun type(text: String, layout: KeyboardLayout): TypedText {
        val map = when (layout) {
            KeyboardLayout.QWERTY_US -> QWERTY
            KeyboardLayout.AZERTY_FR -> AZERTY
        }
        val skipped = linkedSetOf<Char>()
        val keys = text.mapNotNull { c -> map[c] ?: run { skipped += c; null } }
        return TypedText(keys, skipped)
    }

    private val COMMON: Map<Char, HidKey> = mapOf(
        ' ' to HidKey.Keyboard(0x2C),
        '\n' to ENTER,
        '\t' to HidKey.Keyboard(0x2B),
    )

    private val QWERTY: Map<Char, HidKey> = buildMap {
        putAll(COMMON)
        ('a'..'z').forEachIndexed { i, c ->
            put(c, HidKey.Keyboard(0x04 + i))
            put(c.uppercaseChar(), HidKey.Keyboard(0x04 + i, shift = true))
        }
        "1234567890".forEachIndexed { i, c -> put(c, HidKey.Keyboard(0x1E + i)) }
        "!@#$%^&*()".forEachIndexed { i, c -> put(c, HidKey.Keyboard(0x1E + i, shift = true)) }
        val plain = "-=[]\\;'`,./"
        val shifted = "_+{}|:\"~<>?"
        val usages = intArrayOf(0x2D, 0x2E, 0x2F, 0x30, 0x31, 0x33, 0x34, 0x35, 0x36, 0x37, 0x38)
        usages.forEachIndexed { i, u ->
            put(plain[i], HidKey.Keyboard(u))
            put(shifted[i], HidKey.Keyboard(u, shift = true))
        }
    }

    /** AZERTY français (positions des touches d'un clavier QWERTY physique). */
    private val AZERTY: Map<Char, HidKey> = buildMap {
        putAll(COMMON)
        // Lettres : seules A/Q, Z/W et M changent de place.
        val swaps = mapOf('a' to 'q', 'q' to 'a', 'z' to 'w', 'w' to 'z')
        ('a'..'z').forEach { c ->
            val physical = swaps[c] ?: c
            if (c == 'm') return@forEach
            val usage = 0x04 + (physical - 'a')
            put(c, HidKey.Keyboard(usage))
            put(c.uppercaseChar(), HidKey.Keyboard(usage, shift = true))
        }
        put('m', HidKey.Keyboard(0x33))
        put('M', HidKey.Keyboard(0x33, shift = true))
        // Rangée des chiffres : symboles sans Maj, chiffres avec Maj.
        "&é\"'(-è_çà".forEachIndexed { i, c -> put(c, HidKey.Keyboard(0x1E + i)) }
        "1234567890".forEachIndexed { i, c -> put(c, HidKey.Keyboard(0x1E + i, shift = true)) }
        put(')', HidKey.Keyboard(0x2D)); put('°', HidKey.Keyboard(0x2D, shift = true))
        put('=', HidKey.Keyboard(0x2E)); put('+', HidKey.Keyboard(0x2E, shift = true))
        put('$', HidKey.Keyboard(0x30)); put('£', HidKey.Keyboard(0x30, shift = true))
        put('*', HidKey.Keyboard(0x31)); put('µ', HidKey.Keyboard(0x31, shift = true))
        put('ù', HidKey.Keyboard(0x34)); put('%', HidKey.Keyboard(0x34, shift = true))
        put(',', HidKey.Keyboard(0x10)); put('?', HidKey.Keyboard(0x10, shift = true))
        put(';', HidKey.Keyboard(0x36)); put('.', HidKey.Keyboard(0x36, shift = true))
        put(':', HidKey.Keyboard(0x37)); put('/', HidKey.Keyboard(0x37, shift = true))
        put('!', HidKey.Keyboard(0x38)); put('§', HidKey.Keyboard(0x38, shift = true))
        put('<', HidKey.Keyboard(0x64)); put('>', HidKey.Keyboard(0x64, shift = true))
        // AltGr
        put('@', HidKey.Keyboard(0x27, altGr = true))
        put('#', HidKey.Keyboard(0x20, altGr = true))
        put('~', HidKey.Keyboard(0x1F, altGr = true))
        put('{', HidKey.Keyboard(0x21, altGr = true))
        put('[', HidKey.Keyboard(0x22, altGr = true))
        put('|', HidKey.Keyboard(0x23, altGr = true))
        put('\\', HidKey.Keyboard(0x25, altGr = true))
        put(']', HidKey.Keyboard(0x2D, altGr = true))
        put('}', HidKey.Keyboard(0x2E, altGr = true))
        put('€', HidKey.Keyboard(0x08, altGr = true))
    }

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }
}
