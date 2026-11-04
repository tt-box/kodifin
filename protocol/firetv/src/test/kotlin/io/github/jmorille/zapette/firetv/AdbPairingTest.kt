package io.github.jmorille.zapette.firetv


import io.github.jmorille.zapette.model.RemoteException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.File
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import java.security.KeyStore
import java.security.cert.X509Certificate
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.net.ssl.ExtendedSSLSession
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager

/** Téléviseur simulé : rôle serveur TLS 1.3 du protocole AOSP, côté JDK. */
private class FakeTv(private val identity: AdbIdentity) {
    val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
    val port get() = server.localPort

    private val context: SSLContext = SSLContext.getInstance("TLS").apply {
        val store = KeyStore.getInstance("PKCS12").apply {
            load(null, null)
            setKeyEntry("tv", identity.privateKey, CharArray(0), arrayOf(identity.certificate))
        }
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(store, CharArray(0)) }
        val acceptAll = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String) {
                require(chain.isNotEmpty())
            }
            override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String) = Unit
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        init(kmf.keyManagers, arrayOf(acceptAll), null)
    }

    fun tls(socket: Socket): SSLSocket =
        (context.socketFactory.createSocket(socket, null, socket.port, true) as SSLSocket).apply {
            useClientMode = false
            needClientAuth = true
            enabledProtocols = arrayOf("TLSv1.3")
            startHandshake()
        }
}

private fun jdkExporterAvailable() =
    runCatching { ExtendedSSLSession::class.java.getMethod("exportKeyingMaterialData", String::class.java, ByteArray::class.java, Int::class.javaPrimitiveType) }.isSuccess

private fun tempKeys(): File = Files.createTempDirectory("adbkeys").toFile()

class AdbPairingTest {
    private val executor = Executors.newSingleThreadExecutor()

    @Test
    fun `session exchanges peer infos when both sides share the code`() {
        val (clientToTv, tvFromClient) = pipe()
        val (tvToClient, clientFromTv) = pipe()
        val tv = executor.submit(Callable {
            AdbPairingClient.PairingSession("tv-guid", Spake2.Role.BOB).run("123456".toByteArray(), tvFromClient, tvToClient)
        })
        val client = AdbPairingClient.PairingSession("QUFBQQ== zapette@android").run("123456".toByteArray(), clientFromTv, clientToTv)
        assertEquals("tv-guid", client.deviceGuid)
        assertEquals("QUFBQQ== zapette@android", tv.get(5, TimeUnit.SECONDS).deviceGuid)
    }

    @Test
    fun `wrong code is reported as such`() {
        val (clientToTv, tvFromClient) = pipe()
        val (tvToClient, clientFromTv) = pipe()
        executor.submit {
            runCatching {
                AdbPairingClient.PairingSession("tv-guid", Spake2.Role.BOB).run("000000".toByteArray(), tvFromClient, tvToClient)
            }
            // Comme adbd : échec de déchiffrement → connexion fermée.
            tvToClient.close()
        }
        try {
            AdbPairingClient.PairingSession("key").run("123456".toByteArray(), clientFromTv, clientToTv)
            fail("code faux accepté")
        } catch (e: AdbPairingClient.WrongCodeException) {
            // attendu
        }
    }

    @Test
    fun `packet framing follows the AOSP header`() {
        val out = ByteArrayOutputStream()
        AdbPairingClient.writePacket(out, AdbPairingClient.TYPE_PEER_INFO, byteArrayOf(7, 8))
        assertArrayEquals(byteArrayOf(1, 1, 0, 0, 0, 2, 7, 8), out.toByteArray())
        val back = AdbPairingClient.readPacket(DataInputStream(ByteArrayInputStream(out.toByteArray())), AdbPairingClient.TYPE_PEER_INFO)
        assertArrayEquals(byteArrayOf(7, 8), back)
    }

    @Test
    fun `peer info is 8192 bytes with type and zero padded data`() {
        val info = AdbPairingClient.peerInfo(0, "abc".toByteArray())
        assertEquals(8192, info.size)
        assertEquals(0, info[0].toInt())
        assertEquals('a'.code, info[1].toInt())
        assertEquals(0, info[4].toInt())
    }

    @Test
    fun `cipher round trips and rejects a different key`() {
        val a = AdbPairingClient.PairingCipher(ByteArray(64) { 1 })
        val b = AdbPairingClient.PairingCipher(ByteArray(64) { 1 })
        val first = a.encrypt("un".toByteArray())
        val second = a.encrypt("deux".toByteArray())
        assertEquals("un", String(b.decrypt(first)))
        assertEquals("deux", String(b.decrypt(second)))
        val other = AdbPairingClient.PairingCipher(ByteArray(64) { 2 })
        assertTrue(runCatching { other.decrypt(a.encrypt("x".toByteArray())) }.isFailure)
    }

    @Test
    fun `hkdf matches RFC 5869 test case 3 (empty salt and info)`() {
        val ikm = ByteArray(22) { 0x0b }
        val okm = AdbPairingClient.hkdfSha256(ikm, ByteArray(0), 32)
        assertArrayEquals(
            "8da4e775a563c18f715f802a063c5a31b8a11f5c5ee1879ec3454e5f3c738d2d".chunked(2).map { it.toInt(16).toByte() }.toByteArray(),
            okm,
        )
    }

    @Test
    fun `code must be six digits`() {
        try {
            AdbPairingClient(tempKeys()).pair("127.0.0.1", 1, "12345")
            fail()
        } catch (e: IllegalArgumentException) {
            // attendu
        }
    }

    @Test
    fun `full pairing over TLS against a simulated television`() {
        assumeTrue("JDK sans export de matériel de clé TLS", jdkExporterAvailable())
        val tvIdentity = AdbIdentity.load(tempKeys())
        val tv = FakeTv(tvIdentity)
        val received = executor.submit(Callable {
            tv.server.accept().use { raw ->
                val ssl = tv.tls(raw)
                val ekm = (ssl.session as ExtendedSSLSession).exportKeyingMaterialData(AdbPairingClient.EXPORTED_KEY_LABEL, null, 64)
                AdbPairingClient.PairingSession("adb-TV1234-guid", Spake2.Role.BOB)
                    .run("482913".toByteArray() + ekm, ssl.inputStream, ssl.outputStream).deviceGuid
            }
        })
        val keys = tempKeys()
        val result = AdbPairingClient(keys).pair("127.0.0.1", tv.port, " 482913 ")
        assertEquals("adb-TV1234-guid", result.deviceGuid)
        assertEquals(AdbIdentity.load(keys).adbPublicKey, received.get(10, TimeUnit.SECONDS))
        tv.server.close()
    }

    @Test
    fun `full pairing with a wrong code fails with a clear message`() {
        assumeTrue("JDK sans export de matériel de clé TLS", jdkExporterAvailable())
        val tv = FakeTv(AdbIdentity.load(tempKeys()))
        executor.submit {
            tv.server.accept().use { raw ->
                val ssl = tv.tls(raw)
                val ekm = (ssl.session as ExtendedSSLSession).exportKeyingMaterialData(AdbPairingClient.EXPORTED_KEY_LABEL, null, 64)
                runCatching {
                    AdbPairingClient.PairingSession("guid", Spake2.Role.BOB).run("111111".toByteArray() + ekm, ssl.inputStream, ssl.outputStream)
                }
                ssl.close()
            }
        }
        try {
            AdbPairingClient(tempKeys()).pair("127.0.0.1", tv.port, "222222")
            fail("code faux accepté")
        } catch (e: RemoteException) {
            assertTrue(e.message!!, e.message!!.contains("Code d'appairage refusé") || e.message!!.contains("interrompu"))
        } finally {
            tv.server.close()
        }
    }

    @Test
    fun `tls connection performs STLS then hands the encrypted stream to dadb`() {
        val tv = FakeTv(AdbIdentity.load(tempKeys()))
        val clientKeys = tempKeys()
        val clientCert = executor.submit(Callable {
            tv.server.accept().use { raw ->
                val input = DataInputStream(raw.getInputStream())
                val cnxn = AdbTlsConnection.readHeader(input)
                assertEquals(AdbTlsConnection.CMD_CNXN, cnxn.command)
                AdbTlsConnection.writeMessage(raw.getOutputStream(), AdbTlsConnection.CMD_STLS, AdbTlsConnection.STLS_VERSION, 0, ByteArray(0))
                assertEquals(AdbTlsConnection.CMD_STLS, AdbTlsConnection.readHeader(input).command)
                val ssl = tv.tls(raw)
                val banner = "device::ro.product.name=tv;features=shell_v2,cmd\u0000".toByteArray()
                AdbTlsConnection.writeMessage(ssl.outputStream, AdbTlsConnection.CMD_CNXN, 0x01000001, 256 * 1024, banner)
                val peer = ssl.session.peerCertificates.single() as X509Certificate
                Thread.sleep(200)
                peer.publicKey
            }
        })
        val dadb = AdbTlsConnection.open("127.0.0.1", tv.port, AdbIdentity.load(clientKeys), 2_000, 5_000)
        assertTrue(dadb.supportsFeature("shell_v2"))
        assertEquals(AdbIdentity.load(clientKeys).publicKey, clientCert.get(10, TimeUnit.SECONDS))
        dadb.close()
        tv.server.close()
    }

    @Test
    fun `classic adb port is detected and refused by the tls connector`() {
        val out = ByteArrayOutputStream()
        val reply = ByteArrayOutputStream()
        AdbTlsConnection.writeMessage(reply, AdbTlsConnection.CMD_AUTH, 1, 0, ByteArray(20))
        try {
            AdbTlsConnection.startTls(ByteArrayInputStream(reply.toByteArray()), out)
            fail()
        } catch (e: java.io.IOException) {
            assertTrue(e.message!!.contains("débogage sans fil"))
        }
    }

    @Test
    fun `features are parsed from the device banner`() {
        assertEquals(setOf("shell_v2", "cmd"), AdbTlsConnection.parseFeatures("device::ro.product.name=x;features=shell_v2,cmd\u0000"))
        assertEquals(emptySet<String>(), AdbTlsConnection.parseFeatures("device::"))
    }

    @Test
    fun `identity certificate is self signed by the adb key`() {
        val identity = AdbIdentity.load(tempKeys())
        identity.certificate.verify(identity.publicKey)
        identity.certificate.checkValidity()
        assertTrue(identity.adbPublicKey.isNotBlank())
        assertEquals(3, identity.certificate.version)
    }

    private fun pipe(): Pair<PipedOutputStream, PipedInputStream> {
        val input = PipedInputStream(64 * 1024)
        return PipedOutputStream(input) to input
    }
}
