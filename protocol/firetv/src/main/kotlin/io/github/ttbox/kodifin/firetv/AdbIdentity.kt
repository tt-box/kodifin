package io.github.ttbox.kodifin.firetv

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.math.BigInteger
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPrivateCrtKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.RSAPublicKeySpec
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Base64

/**
 * Identité ADB de l'application : la même paire RSA que l'ADB classique (fichiers `adbkey` et
 * `adbkey.pub` générés par dadb dans le répertoire privé), plus un certificat X.509 auto-signé
 * construit à partir de cette clé pour les connexions TLS (appairage et `adb-tls-connect`).
 *
 * Comme l'outil `adb` d'AOSP, le téléviseur n'examine pas le certificat lui-même : il compare
 * sa clé publique à celle enregistrée lors de l'appairage.
 */
class AdbIdentity internal constructor(
    val privateKey: PrivateKey,
    val publicKey: PublicKey,
    val certificate: X509Certificate,
    /** Clé publique au format texte d'ADB (`<base64> nom`), envoyée au téléviseur à l'appairage. */
    val adbPublicKey: String,
) {
    companion object {
        /** Charge (ou génère) la paire de clés de [keyDir] et en dérive le certificat. */
        fun load(keyDir: File): AdbIdentity {
            AdbKeys.load(keyDir)
            val privateKey = readPrivateKey(File(keyDir, "adbkey").readText())
            val adbPublicKey = File(keyDir, "adbkey.pub").readText().trim()
            return fromPrivateKey(privateKey, adbPublicKey)
        }

        internal fun fromPrivateKey(privateKey: PrivateKey, adbPublicKey: String): AdbIdentity {
            val crt = privateKey as? RSAPrivateCrtKey ?: throw IOException("Clé ADB non RSA")
            val publicKey = KeyFactory.getInstance("RSA").generatePublic(RSAPublicKeySpec(crt.modulus, crt.publicExponent))
            val certificate = SelfSignedCertificate.create(privateKey, publicKey)
            return AdbIdentity(privateKey, publicKey, certificate, adbPublicKey)
        }

        /** Lit une clé RSA PEM, PKCS#8 (`PRIVATE KEY`) ou PKCS#1 (`RSA PRIVATE KEY`). */
        internal fun readPrivateKey(pem: String): PrivateKey {
            val pkcs1 = "BEGIN RSA PRIVATE KEY" in pem
            val body = pem.lineSequence().filterNot { it.startsWith("-----") }.joinToString("").trim()
            val der = Base64.getMimeDecoder().decode(body)
            val pkcs8 = if (pkcs1) {
                Der.sequence(Der.integer(BigInteger.ZERO), RSA_ALGORITHM, Der.octetString(der))
            } else {
                der
            }
            return KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(pkcs8))
        }

        private val RSA_ALGORITHM = Der.sequence(Der.oid("1.2.840.113549.1.1.1"), Der.NULL)
    }
}

/** Certificat X.509 v3 auto-signé minimal (SHA256withRSA), sans dépendance externe. */
internal object SelfSignedCertificate {
    private const val VALIDITY_YEARS = 10L
    private val SHA256_WITH_RSA = Der.sequence(Der.oid("1.2.840.113549.1.1.11"), Der.NULL)
    private val UTC_TIME = DateTimeFormatter.ofPattern("yyMMddHHmmss'Z'")

    fun create(privateKey: PrivateKey, publicKey: PublicKey, now: ZonedDateTime = ZonedDateTime.now(ZoneOffset.UTC)): X509Certificate {
        val name = Der.sequence(
            Der.set(Der.sequence(Der.oid("2.5.4.10"), Der.utf8("Android"))),
            Der.set(Der.sequence(Der.oid("2.5.4.3"), Der.utf8("Kodifin"))),
        )
        val start = now.withZoneSameInstant(ZoneOffset.UTC).minusDays(1)
        val validity = Der.sequence(
            Der.utcTime(start.format(UTC_TIME)),
            Der.utcTime(start.plusYears(VALIDITY_YEARS).format(UTC_TIME)),
        )
        val tbs = Der.sequence(
            Der.explicit(0, Der.integer(BigInteger.valueOf(2))),
            Der.integer(BigInteger.ONE),
            SHA256_WITH_RSA,
            name,
            validity,
            name,
            publicKey.encoded,
        )
        val signature = Signature.getInstance("SHA256withRSA").run {
            initSign(privateKey)
            update(tbs)
            sign()
        }
        val der = Der.sequence(tbs, SHA256_WITH_RSA, Der.bitString(signature))
        return CertificateFactory.getInstance("X.509").generateCertificate(der.inputStream()) as X509Certificate
    }
}

/** Encodeur DER réduit au strict nécessaire (certificat, clé PKCS#8). */
internal object Der {
    val NULL = byteArrayOf(0x05, 0x00)

    fun sequence(vararg items: ByteArray) = tlv(0x30, concat(items))
    fun set(vararg items: ByteArray) = tlv(0x31, concat(items))
    fun integer(value: BigInteger) = tlv(0x02, value.toByteArray())
    fun octetString(value: ByteArray) = tlv(0x04, value)
    fun bitString(value: ByteArray) = tlv(0x03, byteArrayOf(0) + value)
    fun utf8(value: String) = tlv(0x0C, value.toByteArray(Charsets.UTF_8))
    fun utcTime(value: String) = tlv(0x17, value.toByteArray(Charsets.US_ASCII))
    fun explicit(tag: Int, content: ByteArray) = tlv(0xA0 or tag, content)

    fun oid(dotted: String): ByteArray {
        val parts = dotted.split('.').map { it.toLong() }
        val out = ByteArrayOutputStream()
        out.write((parts[0] * 40 + parts[1]).toInt())
        for (part in parts.drop(2)) {
            val groups = mutableListOf<Int>()
            var v = part
            do {
                groups += (v and 0x7F).toInt()
                v = v shr 7
            } while (v > 0)
            groups.reversed().forEachIndexed { i, g -> out.write(if (i < groups.size - 1) g or 0x80 else g) }
        }
        return tlv(0x06, out.toByteArray())
    }

    fun tlv(tag: Int, content: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(tag)
        val length = content.size
        when {
            length < 0x80 -> out.write(length)
            else -> {
                val bytes = BigInteger.valueOf(length.toLong()).toByteArray().dropWhile { it == 0.toByte() }
                out.write(0x80 or bytes.size)
                bytes.forEach { out.write(it.toInt()) }
            }
        }
        out.write(content)
        return out.toByteArray()
    }

    private fun concat(items: Array<out ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        items.forEach { out.write(it) }
        return out.toByteArray()
    }
}
