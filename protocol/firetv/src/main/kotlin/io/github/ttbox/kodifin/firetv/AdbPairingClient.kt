package io.github.ttbox.kodifin.firetv

import io.github.ttbox.kodifin.model.RemoteError
import io.github.ttbox.kodifin.model.RemoteException
import io.github.ttbox.kodifin.model.Setup
import java.io.DataInputStream
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Appairage « Débogage sans fil › Associer avec un code » (Android 11+), d'après le protocole
 * public d'AOSP (`packages/modules/adb/pairing_connection`, `pairing_auth`) :
 *
 * 1. TLS 1.3 vers le port d'appairage, chacun présentant un certificat auto-signé ;
 * 2. mot de passe = code à 6 chiffres ‖ 64 octets exportés du tunnel (libellé « adb-label\0 ») ;
 * 3. échange SPAKE2 (client = Alice « adb pair client\0 », téléviseur = Bob « adb pair server\0 ») ;
 * 4. clé AES-128-GCM = HKDF-SHA256(clé SPAKE2, info « adb pairing_auth aes-128-gcm key ») ;
 * 5. échange chiffré des PeerInfo (8192 octets) : notre clé publique ADB contre le GUID du téléviseur.
 *
 * Trames : en-tête de 6 octets (version = 1, type 0 = SPAKE2 / 1 = PeerInfo, taille big-endian).
 * Un code faux n'est détecté qu'à l'étape 5 : le téléviseur ne peut pas déchiffrer notre
 * PeerInfo et coupe la connexion (ou renvoie un bloc indéchiffrable).
 */
class AdbPairingClient(
    private val keyDir: File,
    private val connectTimeoutMs: Int = 5_000,
    private val socketTimeoutMs: Int = 15_000,
    private val exporter: TlsKeyExporter = TlsKeyExporter.DEFAULT,
) {
    /** Résultat : identifiant (GUID) que le téléviseur a annoncé. */
    data class Result(val deviceGuid: String)

    /** Bloquant : à appeler hors du fil principal. */
    fun pair(host: String, port: Int, code: String): Result {
        val trimmed = code.trim()
        require(PAIRING_CODE.matches(trimmed)) { "Le code d'appairage comporte 6 chiffres" }
        val identity = AdbIdentity.load(keyDir)
        val socket = try {
            AdbTls.connect(host, port, connectTimeoutMs, socketTimeoutMs)
        } catch (e: IOException) {
            // Port fermé : la fenêtre « Associer avec un code » n'est pas (ou plus) ouverte.
            throw RemoteException(RemoteError.SetupRequired(Setup.ADB_PAIRING_WINDOW), e)
        }
        socket.use {
            val ssl = try {
                AdbTls.handshake(socket, host, port, identity, socketTimeoutMs)
            } catch (e: UnsupportedOperationException) {
                // TLS 1.3 absent du téléphone (Android 9 ou moins).
                throw RemoteException(RemoteError.TlsFailed(host), e)
            } catch (e: IOException) {
                throw RemoteException(RemoteError.TlsFailed(host), e)
            }
            ssl.use {
                val exported = try {
                    exporter.export(ssl, EXPORTED_KEY_LABEL, EXPORTED_KEY_SIZE)
                } catch (e: UnsupportedOperationException) {
                    // Export de matériel de clé TLS absent du téléphone.
                    throw RemoteException(RemoteError.TlsFailed(host), e)
                }
                return try {
                    PairingSession(identity.adbPublicKey).run(
                        trimmed.toByteArray(Charsets.US_ASCII) + exported,
                        ssl.inputStream,
                        ssl.outputStream,
                    )
                } catch (e: WrongCodeException) {
                    throw RemoteException(RemoteError.CodeRejected, e)
                } catch (e: SocketTimeoutException) {
                    throw RemoteException(RemoteError.Timeout, e)
                } catch (e: IOException) {
                    // Trame ou message SPAKE2 invalide, connexion coupée : le détail reste dans la cause.
                    throw RemoteException(RemoteError.Interrupted(host), e)
                }
            }
        }
    }

    internal class WrongCodeException(message: String, cause: Throwable? = null) : IOException(message, cause)

    /**
     * Partie applicative de l'appairage (au-dessus de TLS), isolée pour les tests :
     * [role] Alice côté client ; Bob permet de simuler le téléviseur.
     */
    internal class PairingSession(
        private val adbPublicKey: String,
        private val role: Spake2.Role = Spake2.Role.ALICE,
        private val peerInfoType: Int = if (role == Spake2.Role.ALICE) PEER_INFO_RSA_PUB_KEY else PEER_INFO_DEVICE_GUID,
    ) {
        fun run(password: ByteArray, input: InputStream, output: OutputStream): Result {
            val (myName, theirName) = if (role == Spake2.Role.ALICE) CLIENT_NAME to SERVER_NAME else SERVER_NAME to CLIENT_NAME
            val spake = Spake2(role, myName, theirName)
            val myMsg = spake.generateMessage(password)
            val data = DataInputStream(input)
            val key: ByteArray
            if (role == Spake2.Role.ALICE) {
                writePacket(output, TYPE_SPAKE2, myMsg)
                key = processPeer(spake, readPacket(data, TYPE_SPAKE2))
            } else {
                val theirs = readPacket(data, TYPE_SPAKE2)
                writePacket(output, TYPE_SPAKE2, myMsg)
                key = processPeer(spake, theirs)
            }
            val cipher = PairingCipher(key)
            val myInfo = peerInfo(peerInfoType, adbPublicKey.toByteArray(Charsets.UTF_8))
            if (role == Spake2.Role.ALICE) {
                writePacket(output, TYPE_PEER_INFO, cipher.encrypt(myInfo))
                return Result(decodePeer(cipher, readPeerInfo(data)))
            }
            val theirs = decodePeer(cipher, readPeerInfo(data))
            writePacket(output, TYPE_PEER_INFO, cipher.encrypt(myInfo))
            return Result(theirs)
        }

        private fun processPeer(spake: Spake2, msg: ByteArray): ByteArray = try {
            spake.processMessage(msg)
        } catch (e: IllegalArgumentException) {
            throw IOException("Message SPAKE2 invalide", e)
        }

        private fun readPeerInfo(data: DataInputStream): ByteArray = try {
            readPacket(data, TYPE_PEER_INFO)
        } catch (e: EOFException) {
            // Le téléviseur n'a pas pu déchiffrer notre PeerInfo : clés SPAKE2 différentes.
            throw WrongCodeException("Connexion fermée par l'appareil", e)
        }

        private fun decodePeer(cipher: PairingCipher, encrypted: ByteArray): String {
            val info = try {
                cipher.decrypt(encrypted)
            } catch (e: AEADBadTagException) {
                throw WrongCodeException("PeerInfo indéchiffrable", e)
            }
            if (info.size != PEER_INFO_SIZE) throw IOException("PeerInfo de taille inattendue (${info.size})")
            val end = (1 until info.size).firstOrNull { info[it] == 0.toByte() } ?: info.size
            return String(info, 1, end - 1, Charsets.UTF_8)
        }
    }

    /** AES-128-GCM, nonce = numéro de séquence 64 bits petit-boutiste complété de zéros (AOSP `aes_128_gcm`). */
    internal class PairingCipher(keyMaterial: ByteArray) {
        private val key = SecretKeySpec(hkdfSha256(keyMaterial, HKDF_INFO, 16), "AES")
        private var encSequence = 0L
        private var decSequence = 0L

        fun encrypt(plain: ByteArray): ByteArray = cipher(Cipher.ENCRYPT_MODE, encSequence++).doFinal(plain)
        fun decrypt(encrypted: ByteArray): ByteArray = cipher(Cipher.DECRYPT_MODE, decSequence++).doFinal(encrypted)

        private fun cipher(mode: Int, sequence: Long): Cipher {
            val nonce = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN).putLong(sequence).array()
            return Cipher.getInstance("AES/GCM/NoPadding").apply { init(mode, key, GCMParameterSpec(128, nonce)) }
        }
    }

    internal companion object {
        val PAIRING_CODE = Regex("^\\d{6}$")
        const val EXPORTED_KEY_LABEL = "adb-label\u0000"
        const val EXPORTED_KEY_SIZE = 64
        val CLIENT_NAME = "adb pair client\u0000".toByteArray(Charsets.US_ASCII)
        val SERVER_NAME = "adb pair server\u0000".toByteArray(Charsets.US_ASCII)
        val HKDF_INFO = "adb pairing_auth aes-128-gcm key".toByteArray(Charsets.US_ASCII)

        const val PACKET_VERSION = 1
        const val TYPE_SPAKE2 = 0
        const val TYPE_PEER_INFO = 1
        const val MAX_PAYLOAD = 8192 * 2
        const val PEER_INFO_SIZE = 8192
        const val PEER_INFO_RSA_PUB_KEY = 0
        const val PEER_INFO_DEVICE_GUID = 1

        fun peerInfo(type: Int, data: ByteArray): ByteArray {
            require(data.size < PEER_INFO_SIZE - 1) { "PeerInfo trop grand" }
            return ByteArray(PEER_INFO_SIZE).also {
                it[0] = type.toByte()
                data.copyInto(it, 1)
            }
        }

        fun writePacket(output: OutputStream, type: Int, payload: ByteArray) {
            val header = ByteBuffer.allocate(6).order(ByteOrder.BIG_ENDIAN)
                .put(PACKET_VERSION.toByte()).put(type.toByte()).putInt(payload.size).array()
            output.write(header + payload)
            output.flush()
        }

        fun readPacket(input: DataInputStream, expectedType: Int): ByteArray {
            val version = input.readUnsignedByte()
            val type = input.readUnsignedByte()
            val size = input.readInt()
            if (version != PACKET_VERSION) throw IOException("Version d'appairage non prise en charge ($version)")
            if (type != expectedType) throw IOException("Trame d'appairage inattendue (type $type)")
            if (size <= 0 || size > MAX_PAYLOAD) throw IOException("Taille de trame invalide ($size)")
            return ByteArray(size).also(input::readFully)
        }

        /** HKDF (RFC 5869) sans sel : un sel absent équivaut à des zéros de la taille du condensé. */
        fun hkdfSha256(ikm: ByteArray, info: ByteArray, length: Int): ByteArray {
            require(length <= 32)
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(ByteArray(32), "HmacSHA256"))
            val prk = mac.doFinal(ikm)
            mac.init(SecretKeySpec(prk, "HmacSHA256"))
            mac.update(info)
            mac.update(1)
            return mac.doFinal().copyOf(length)
        }
    }
}
