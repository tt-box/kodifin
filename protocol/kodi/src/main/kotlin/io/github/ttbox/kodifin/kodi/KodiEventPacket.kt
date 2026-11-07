package io.github.ttbox.kodifin.kodi

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

/**
 * Encodeur des paquets du Kodi Event Server (UDP, port 9777 par défaut), d'après la
 * spécification publique (wiki Kodi « EventServer » et en-tête `xbmcclient.h`).
 *
 * En-tête de 32 octets, entiers en gros-boutiste :
 * - 4 octets : signature `XBMC` ;
 * - 1 octet : version majeure (2), 1 octet : version mineure (0) ;
 * - 2 octets : type de paquet ;
 * - 4 octets : numéro de séquence (1 à N) ;
 * - 4 octets : nombre total de paquets du message (N) ;
 * - 2 octets : taille de la charge utile de ce paquet ;
 * - 4 octets : identifiant du client (UID, constant pour une connexion) ;
 * - 10 octets réservés (zéros).
 *
 * Un datagramme ne dépasse pas [MAX_PACKET_SIZE] octets : une charge utile plus longue
 * est découpée en plusieurs paquets numérotés.
 */
object KodiEventPacket {
    const val HEADER_SIZE = 32
    const val MAX_PACKET_SIZE = 1024
    const val MAX_PAYLOAD_SIZE = MAX_PACKET_SIZE - HEADER_SIZE
    const val MAJOR_VERSION: Byte = 2
    const val MINOR_VERSION: Byte = 0
    private val SIGNATURE = byteArrayOf('X'.code.toByte(), 'B'.code.toByte(), 'M'.code.toByte(), 'C'.code.toByte())

    /** Types de paquet utilisés (valeurs de `xbmcclient.h`). */
    enum class Type(val code: Int) {
        HELO(0x01),
        BYE(0x02),
        BUTTON(0x03),
        PING(0x05),
    }

    /** Drapeaux d'un paquet BUTTON. */
    object ButtonFlags {
        const val USE_NAME = 0x01
        const val DOWN = 0x02
        const val UP = 0x04
        const val USE_AMOUNT = 0x08
        const val QUEUE = 0x10
        const val NO_REPEAT = 0x20

        /** Appui unique sans répétition automatique (pas de relâchement à envoyer). */
        const val TAP = DOWN or NO_REPEAT or QUEUE
    }

    /** Type d'icône annoncé par HELO : aucune (rien n'est envoyé au-delà du nom). */
    const val ICON_NONE: Byte = 0x00

    /**
     * Découpe [payload] en paquets complets (en-tête + charge utile). Un message sans charge
     * utile donne un paquet unique.
     */
    fun encode(type: Type, uid: Int, payload: ByteArray = ByteArray(0)): List<ByteArray> {
        val chunks = if (payload.isEmpty()) {
            listOf(payload)
        } else {
            (payload.indices step MAX_PAYLOAD_SIZE).map { payload.copyOfRange(it, minOf(it + MAX_PAYLOAD_SIZE, payload.size)) }
        }
        return chunks.mapIndexed { index, chunk ->
            ByteBuffer.allocate(HEADER_SIZE + chunk.size).apply {
                put(SIGNATURE)
                put(MAJOR_VERSION)
                put(MINOR_VERSION)
                putShort(type.code.toShort())
                putInt(index + 1)
                putInt(chunks.size)
                putShort(chunk.size.toShort())
                putInt(uid)
                put(ByteArray(10))
                put(chunk)
            }.array()
        }
    }

    /** HELO : nom du client, sans icône ; le port et les champs réservés sont à zéro. */
    fun helo(uid: Int, deviceName: String): ByteArray {
        val payload = ByteArrayOutputStream().apply {
            writeString(deviceName.take(MAX_NAME_LENGTH))
            write(ICON_NONE.toInt())
            write(ByteArray(2)) // port (inutilisé)
            write(ByteArray(4)) // réservé 1
            write(ByteArray(4)) // réservé 2
        }.toByteArray()
        return encode(Type.HELO, uid, payload).single()
    }

    fun bye(uid: Int): ByteArray = encode(Type.BYE, uid).single()

    fun ping(uid: Int): ByteArray = encode(Type.PING, uid).single()

    /**
     * BUTTON désigné par son nom dans une table de correspondance de Kodi ([map] : `KB` clavier,
     * `R1` télécommande…) : le code est alors nul et le drapeau [ButtonFlags.USE_NAME] ajouté.
     */
    fun buttonByName(uid: Int, map: String, name: String, flags: Int = ButtonFlags.TAP, amount: Int = 0): ByteArray =
        button(uid, code = 0, flags = flags or ButtonFlags.USE_NAME, amount = amount, map = map, name = name)

    /** BUTTON désigné par son code (table [map], ou code de touche brut si [map] est vide). */
    fun buttonByCode(uid: Int, code: Int, map: String = "", flags: Int = ButtonFlags.TAP, amount: Int = 0): ByteArray =
        button(uid, code = code, flags = flags and ButtonFlags.USE_NAME.inv(), amount = amount, map = map, name = "")

    private fun button(uid: Int, code: Int, flags: Int, amount: Int, map: String, name: String): ByteArray {
        val effectiveFlags = if (amount != 0) flags or ButtonFlags.USE_AMOUNT else flags
        val payload = ByteArrayOutputStream().apply {
            writeShort(code)
            writeShort(effectiveFlags)
            writeShort(amount)
            writeString(map)
            writeString(name)
        }.toByteArray()
        return encode(Type.BUTTON, uid, payload).single()
    }

    private fun ByteArrayOutputStream.writeShort(value: Int) {
        write((value ushr 8) and 0xFF)
        write(value and 0xFF)
    }

    /** Chaîne UTF-8 terminée par un octet nul. */
    private fun ByteArrayOutputStream.writeString(value: String) {
        write(value.replace("\u0000", "").toByteArray(Charsets.UTF_8))
        write(0)
    }

    private const val MAX_NAME_LENGTH = 128
}
