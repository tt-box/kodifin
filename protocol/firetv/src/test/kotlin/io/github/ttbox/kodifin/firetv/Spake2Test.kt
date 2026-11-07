package io.github.ttbox.kodifin.firetv

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.interfaces.EdECPublicKey

class Spake2Test {
    private fun hex(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    /** Entrée de table « encodeint(x) ‖ encodeint(y) » de BoringSSL (spake25519, source publique). */
    private fun tableEntry(p: Ed25519.Point) = Ed25519.toLittleEndian(p.x, 32) + Ed25519.toLittleEndian(p.y, 32)

    @Test
    fun `points M and N match BoringSSL precomputed tables`() {
        // Deux premières entrées de kSpakeNSmallPrecomp / kSpakeMSmallPrecomp : P et 2^64·P.
        val n1 = "201bc5b343177110441e73b3ae3fbf9ff544c8138fd101c28a1a6dea4d005d6e10e3df0ae37d8e7a99b5fe74b44672103dbddcbd06af680d71329a11693bc778"
        val n2 = "93f157976ef06e45374af40b1851f54f673cdcec84edd0ebcafbdbff7feba823688713646a10f745e00f3221597c0e50ad56d712697b58f8b93ba5bb4d1b871c"
        val m1 = "c8a663c597f1ee40ab6242ee256f326c752ca7d3bd323b1e119cbd04a9786f455ada7e4bf6ddd9adb6626d32131c6b5c51a1e347a3478f53cfcf441b88eed12e"
        val m2 = "0389afc0612d9e35eb0e03e0b7fba5bc44be0c890a0fd659479ee63d369dff445eacabe53ad5b0359f6d7fbac0850ef4703f13904c501aeec5eb69fe9842871d"
        val two64 = BigInteger.ONE.shiftLeft(64)
        assertArrayEquals(hex(n1), tableEntry(Ed25519.N))
        assertArrayEquals(hex(n2), tableEntry(Ed25519.multiply(Ed25519.N, two64)))
        assertArrayEquals(hex(m1), tableEntry(Ed25519.M))
        assertArrayEquals(hex(m2), tableEntry(Ed25519.multiply(Ed25519.M, two64)))
    }

    @Test
    fun `base point multiplication matches the JDK Ed25519 implementation`() {
        val seed = ByteArray(32) { it.toByte() }
        val fixed = object : SecureRandom() {
            override fun nextBytes(bytes: ByteArray) {
                seed.copyInto(bytes)
            }
        }
        val generator = KeyPairGenerator.getInstance("Ed25519").apply { initialize(255, fixed) }
        val public = generator.generateKeyPair().public as EdECPublicKey
        // RFC 8032 : scalaire = SHA-512(graine)[0..32] « clampé ».
        val h = MessageDigest.getInstance("SHA-512").digest(seed).copyOf(32)
        h[0] = (h[0].toInt() and 248).toByte()
        h[31] = ((h[31].toInt() and 127) or 64).toByte()
        val point = Ed25519.multiply(Ed25519.B, Ed25519.fromLittleEndian(h))
        assertEquals(public.point.y, point.y)
        assertEquals(public.point.isXOdd, point.x.testBit(0))
        assertEquals(point, Ed25519.decode(Ed25519.encode(point)))
    }

    @Test
    fun `password scalar is a multiple of eight congruent to the hash`() {
        repeat(20) { i ->
            val hash = MessageDigest.getInstance("SHA-512").digest(byteArrayOf(i.toByte()))
            val w = Ed25519.passwordScalar(hash)
            assertEquals(0, w.mod(BigInteger.valueOf(8)).toInt())
            assertEquals(Ed25519.fromLittleEndian(hash).mod(Ed25519.L), w.mod(Ed25519.L))
        }
    }

    private fun exchange(alicePassword: String, bobPassword: String): Pair<ByteArray, ByteArray> {
        val alice = Spake2(Spake2.Role.ALICE, AdbPairingClient.CLIENT_NAME, AdbPairingClient.SERVER_NAME)
        val bob = Spake2(Spake2.Role.BOB, AdbPairingClient.SERVER_NAME, AdbPairingClient.CLIENT_NAME)
        val a = alice.generateMessage(alicePassword.toByteArray())
        val b = bob.generateMessage(bobPassword.toByteArray())
        return alice.processMessage(b) to bob.processMessage(a)
    }

    @Test
    fun `both roles derive the same key with the same password`() {
        val (a, b) = exchange("123456", "123456")
        assertEquals(64, a.size)
        assertArrayEquals(a, b)
    }

    @Test
    fun `keys differ when the codes differ`() {
        val (a, b) = exchange("123456", "654321")
        assertFalse(a.contentEquals(b))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `invalid points are rejected`() {
        // Premier y hors de la courbe (x² non carré).
        val bytes = (2..100).map { y -> ByteArray(32).also { it[0] = y.toByte() } }.first { Ed25519.decode(it) == null }
        assertNull(Ed25519.decode(bytes))
        val alice = Spake2(Spake2.Role.ALICE, AdbPairingClient.CLIENT_NAME, AdbPairingClient.SERVER_NAME)
        alice.generateMessage("123456".toByteArray())
        alice.processMessage(bytes)
    }
}
