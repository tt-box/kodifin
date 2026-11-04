package io.github.jmorille.zapette.firetv

import java.math.BigInteger
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * SPAKE2 sur edwards25519, compatible avec l'implémentation de BoringSSL
 * (`crypto/curve25519/spake25519`) utilisée par `adb pair` (AOSP, `pairing_auth`).
 *
 * Reprend fidèlement le comportement public documenté dans ces sources :
 * - points M et N : premier encodage décodable de SHA-256 itéré à partir des graines
 *   « edwards25519 point generation seed (M) / (N) » ;
 * - scalaire privé x : 64 octets aléatoires réduits modulo l puis multipliés par 8 (cofacteur) ;
 * - scalaire du mot de passe w : SHA-512(mot de passe) réduit modulo l, auquel on ajoute
 *   l, 2l et 4l selon ses trois bits de poids faible pour le rendre multiple de 8
 *   (« password scalar hack » de BoringSSL, actif par défaut) ;
 * - message : encode(x·B + w·M) pour Alice (client), encode(x·B + w·N) pour Bob ;
 * - clé : SHA-512 des champs préfixés par leur longueur sur 8 octets petit-boutiste
 *   (nom d'Alice, nom de Bob, message d'Alice, message de Bob, K, SHA-512(mot de passe)).
 *
 * Arithmétique en [BigInteger] (non constante en temps) : acceptable pour un appairage
 * ponctuel sur le réseau local, protégé de plus par le tunnel TLS.
 */
internal class Spake2(
    private val role: Role,
    private val myName: ByteArray,
    private val theirName: ByteArray,
    private val random: SecureRandom = SecureRandom(),
) {
    enum class Role { ALICE, BOB }

    private var privateScalar: BigInteger? = null
    private var passwordScalar: BigInteger = BigInteger.ZERO
    private var passwordHash = ByteArray(0)
    private var myMessage = ByteArray(0)

    /** Premier message (32 octets) à envoyer au pair. */
    fun generateMessage(password: ByteArray): ByteArray {
        check(privateScalar == null) { "Message SPAKE2 déjà généré" }
        val seed = ByteArray(64).also(random::nextBytes)
        val x = Ed25519.fromLittleEndian(seed).mod(Ed25519.L).shiftLeft(3)
        privateScalar = x
        passwordHash = sha512(password)
        passwordScalar = Ed25519.passwordScalar(passwordHash)
        val mask = Ed25519.multiply(if (role == Role.ALICE) Ed25519.M else Ed25519.N, passwordScalar)
        myMessage = Ed25519.encode(Ed25519.add(Ed25519.multiply(Ed25519.B, x), mask))
        return myMessage.copyOf()
    }

    /** Traite le message du pair ; renvoie la clé partagée de 64 octets. */
    fun processMessage(theirMessage: ByteArray): ByteArray {
        val x = checkNotNull(privateScalar) { "generateMessage d'abord" }
        require(theirMessage.size == 32) { "Message SPAKE2 de taille invalide" }
        val qStar = Ed25519.decode(theirMessage) ?: throw IllegalArgumentException("Point SPAKE2 invalide")
        val peerMask = Ed25519.multiply(if (role == Role.ALICE) Ed25519.N else Ed25519.M, passwordScalar)
        val q = Ed25519.add(qStar, Ed25519.negate(peerMask))
        val shared = Ed25519.encode(Ed25519.multiply(q, x))
        val digest = MessageDigest.getInstance("SHA-512")
        fun update(data: ByteArray) {
            val length = ByteArray(8)
            var l = data.size.toLong()
            for (i in 0 until 8) {
                length[i] = (l and 0xFF).toByte()
                l = l shr 8
            }
            digest.update(length)
            digest.update(data)
        }
        if (role == Role.ALICE) {
            update(myName); update(theirName); update(myMessage); update(theirMessage)
        } else {
            update(theirName); update(myName); update(theirMessage); update(myMessage)
        }
        update(shared)
        update(passwordHash)
        return digest.digest()
    }

    private fun sha512(data: ByteArray) = MessageDigest.getInstance("SHA-512").digest(data)
}

/** Courbe edwards25519 (−x² + y² = 1 + d·x²·y²) en coordonnées affines, loi d'addition complète. */
internal object Ed25519 {
    val P: BigInteger = BigInteger.ONE.shiftLeft(255).subtract(BigInteger.valueOf(19))
    val L: BigInteger = BigInteger.ONE.shiftLeft(252).add(BigInteger("27742317777372353535851937790883648493"))
    private val D: BigInteger = BigInteger.valueOf(-121665).multiply(BigInteger.valueOf(121666).modInverse(P)).mod(P)
    private val SQRT_M1: BigInteger = BigInteger.valueOf(2).modPow(P.subtract(BigInteger.ONE).shiftRight(2), P)

    data class Point(val x: BigInteger, val y: BigInteger)

    private val IDENTITY = Point(BigInteger.ZERO, BigInteger.ONE)

    /** Point de base standard (y = 4/5, x pair). */
    val B: Point = run {
        val y = BigInteger.valueOf(4).multiply(BigInteger.valueOf(5).modInverse(P)).mod(P)
        Point(checkNotNull(recoverX(y, 0)), y)
    }

    val M: Point = generatePoint("edwards25519 point generation seed (M)")
    val N: Point = generatePoint("edwards25519 point generation seed (N)")

    fun add(a: Point, b: Point): Point {
        val x1y2 = a.x.multiply(b.y)
        val y1x2 = a.y.multiply(b.x)
        val dxy = D.multiply(a.x).mod(P).multiply(b.x).mod(P).multiply(a.y).mod(P).multiply(b.y).mod(P)
        val x = x1y2.add(y1x2).multiply(BigInteger.ONE.add(dxy).modInverse(P)).mod(P)
        val y = a.y.multiply(b.y).add(a.x.multiply(b.x)).multiply(BigInteger.ONE.subtract(dxy).mod(P).modInverse(P)).mod(P)
        return Point(x, y)
    }

    fun negate(a: Point) = Point(P.subtract(a.x).mod(P), a.y)

    /** Multiplication exacte par l'entier [k] ≥ 0 (non réduit : les points peuvent avoir une composante de petit ordre). */
    fun multiply(point: Point, k: BigInteger): Point {
        var result = IDENTITY
        for (i in k.bitLength() - 1 downTo 0) {
            result = add(result, result)
            if (k.testBit(i)) result = add(result, point)
        }
        return result
    }

    fun encode(point: Point): ByteArray {
        val out = toLittleEndian(point.y, 32)
        if (point.x.testBit(0)) out[31] = (out[31].toInt() or 0x80).toByte()
        return out
    }

    /** Décodage d'un point (y sur 255 bits, bit de signe de x) ; `null` s'il n'est pas sur la courbe. */
    fun decode(bytes: ByteArray): Point? {
        require(bytes.size == 32)
        val sign = (bytes[31].toInt() shr 7) and 1
        val copy = bytes.copyOf().also { it[31] = (it[31].toInt() and 0x7F).toByte() }
        val y = fromLittleEndian(copy).mod(P)
        val x = recoverX(y, sign) ?: return null
        return Point(x, y)
    }

    private fun recoverX(y: BigInteger, sign: Int): BigInteger? {
        val y2 = y.multiply(y).mod(P)
        val xx = y2.subtract(BigInteger.ONE).multiply(D.multiply(y2).add(BigInteger.ONE).modInverse(P)).mod(P)
        var x = xx.modPow(P.add(BigInteger.valueOf(3)).shiftRight(3), P)
        if (x.multiply(x).subtract(xx).mod(P).signum() != 0) x = x.multiply(SQRT_M1).mod(P)
        if (x.multiply(x).subtract(xx).mod(P).signum() != 0) return null
        if (x.testBit(0) != (sign == 1)) x = P.subtract(x).mod(P)
        if (x.signum() == 0 && sign == 1) return null
        return x
    }

    /** Procédure publique de BoringSSL : SHA-256 itéré jusqu'à obtenir un point décodable. */
    private fun generatePoint(seed: String): Point {
        val sha = MessageDigest.getInstance("SHA-256")
        var v = sha.digest(seed.toByteArray(Charsets.US_ASCII))
        while (true) {
            decode(v)?.let { return it }
            v = sha.digest(v)
        }
    }

    /** Scalaire du mot de passe, rendu multiple de 8 par ajout de l, 2l, 4l (comme BoringSSL). */
    fun passwordScalar(passwordHash: ByteArray): BigInteger {
        var w = fromLittleEndian(passwordHash).mod(L)
        if (w.testBit(0)) w = w.add(L)
        if (w.testBit(1)) w = w.add(L.shiftLeft(1))
        if (w.testBit(2)) w = w.add(L.shiftLeft(2))
        return w
    }

    fun fromLittleEndian(bytes: ByteArray): BigInteger = BigInteger(1, bytes.reversedArray())

    fun toLittleEndian(value: BigInteger, size: Int): ByteArray {
        val be = value.toByteArray()
        val out = ByteArray(size)
        for (i in be.indices) {
            val target = be.size - 1 - i
            if (target < size) out[target] = be[i]
        }
        return out
    }
}
