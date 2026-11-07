package io.github.ttbox.kodifin.firetv

import dadb.AdbStream
import dadb.Dadb
import okio.sink
import okio.source
import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Connexion ADB chiffrée vers le port `_adb-tls-connect._tcp` d'un appareil appairé
 * (protocole AOSP : `protocol.txt`, `adb.cpp`) :
 *
 * 1. le client envoie CNXN en clair ;
 * 2. l'appareil répond STLS (`0x534C5453`, version `0x01000000`) ; le client renvoie STLS ;
 * 3. poignée de main TLS 1.3, le client présentant le certificat de sa clé ADB ;
 * 4. l'appareil envoie CNXN dans le tunnel ; la suite est l'ADB habituel, confié à dadb.
 *
 * dadb 2.0.0 ne gère ni STLS ni TLS ; ses lecteur/écrivain de messages et sa connexion
 * (internes en Kotlin) sont atteints par [DadbBridge] pour lui remettre un flux déjà chiffré.
 */
internal object AdbTlsConnection {
    const val CMD_CNXN = 0x4E584E43
    const val CMD_AUTH = 0x48545541
    const val CMD_STLS = 0x534C5453
    const val STLS_VERSION = 0x01000000
    private const val MAX_PAYLOAD = 1024 * 1024

    fun open(host: String, port: Int, identity: AdbIdentity, connectTimeoutMs: Int, socketTimeoutMs: Int): Dadb {
        val socket = AdbTls.connect(host, port, connectTimeoutMs, socketTimeoutMs)
        try {
            startTls(socket.getInputStream(), socket.getOutputStream())
            val ssl = AdbTls.handshake(socket, host, port, identity, socketTimeoutMs)
            try {
                return TlsDadb(DadbBridge.open(ssl.source(), ssl.sink(), ssl))
            } catch (e: Exception) {
                runCatching { ssl.close() }
                throw e
            }
        } catch (e: Exception) {
            runCatching { socket.close() }
            throw e
        }
    }

    /** Phase en clair : CNXN puis STLS ; échoue si l'appareil ne demande pas TLS. */
    internal fun startTls(input: InputStream, output: OutputStream) {
        writeMessage(output, CMD_CNXN, DadbBridge.connectVersion(), DadbBridge.connectMaxData(), DadbBridge.connectPayload())
        val reply = readHeader(DataInputStream(input))
        when (reply.command) {
            CMD_STLS -> writeMessage(output, CMD_STLS, STLS_VERSION, 0, ByteArray(0))
            CMD_AUTH, CMD_CNXN -> throw IOException(
                "Ce port n'utilise pas le débogage sans fil (TLS) : utilisez l'ADB classique ou refaites l'appairage",
            )
            else -> throw IOException("Réponse ADB inattendue (0x${reply.command.toString(16)})")
        }
    }

    internal data class Header(val command: Int, val arg0: Int, val arg1: Int, val length: Int)

    /** Lit un en-tête (et ignore sa charge utile) ; STLS n'en a pas. */
    internal fun readHeader(input: DataInputStream): Header {
        val buffer = ByteArray(24).also(input::readFully)
        val b = ByteBuffer.wrap(buffer).order(ByteOrder.LITTLE_ENDIAN)
        val header = Header(b.int, b.int, b.int, b.int)
        b.int // somme de contrôle
        val magic = b.int
        if (magic != header.command.inv()) throw IOException("En-tête ADB invalide")
        if (header.length < 0 || header.length > MAX_PAYLOAD) throw IOException("Charge ADB invalide")
        input.skipNBytesCompat(header.length)
        return header
    }

    internal fun writeMessage(output: OutputStream, command: Int, arg0: Int, arg1: Int, payload: ByteArray) {
        val header = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(command).putInt(arg0).putInt(arg1).putInt(payload.size)
            .putInt(payload.sumOf { it.toInt() and 0xFF }).putInt(command.inv())
            .array()
        output.write(header + payload)
        output.flush()
    }

    /** Fonctionnalités annoncées : `device::cle=valeur;features=a,b,c`. */
    @JvmStatic
    fun parseFeatures(banner: String): Set<String> =
        banner.substringAfter("::", "").trimEnd('\u0000').split(';')
            .firstOrNull { it.startsWith("features=") }
            ?.removePrefix("features=")?.split(',')?.filter { it.isNotBlank() }?.toSet()
            ?: emptySet()

    private fun DataInputStream.skipNBytesCompat(count: Int) {
        var remaining = count
        while (remaining > 0) {
            val skipped = skipBytes(remaining)
            if (skipped <= 0) {
                readByte()
                remaining--
            } else {
                remaining -= skipped
            }
        }
    }

    /** Façade dadb sur une connexion déjà établie (les commandes de haut niveau sont celles de l'interface). */
    private class TlsDadb(private val connection: DadbBridge.Connection) : Dadb {
        override fun open(destination: String): AdbStream = connection.open(destination)
        override fun supportsFeature(feature: String): Boolean = connection.supportsFeature(feature)
        override fun close() = connection.close()
        override fun toString() = "TlsDadb"
    }
}
