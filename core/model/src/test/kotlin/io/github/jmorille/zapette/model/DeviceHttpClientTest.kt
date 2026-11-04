package io.github.jmorille.zapette.model

import java.net.ServerSocket
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.junit.Test

class DeviceHttpClientTest {
    private val base = zapetteHttpClient()

    @Test
    fun `timeouts are proportional to the device delay and bounded`() {
        val c = base.forDevice(timeoutSeconds = 10)
        assertEquals(10_000, c.connectTimeoutMillis)
        assertEquals(30_000, c.readTimeoutMillis)
        assertEquals(60_000, c.callTimeoutMillis)
        assertEquals(5_000, base.forDevice().connectTimeoutMillis)
        assertEquals(60_000, base.forDevice(timeoutSeconds = 600).connectTimeoutMillis)
        assertEquals(1_000, base.forDevice(timeoutSeconds = 0).connectTimeoutMillis)
    }

    @Test
    fun `derived client keeps the redirect policy and shares the pool`() {
        val c = base.forDevice(timeoutSeconds = 3, insecureTls = true)
        assertSame(base.connectionPool, c.connectionPool)
        assertTrue(c.interceptors.any { it is SameHostRedirectInterceptor })
        assertEquals(false, c.followRedirects)
    }

    @Test
    fun `insecure mode only applies to the derived client`() {
        val insecure = base.forDevice(insecureTls = true)
        assertSame(InsecureTls.anyHostname, insecure.hostnameVerifier)
        assertSame(InsecureTls.trustAll, insecure.x509TrustManager)
        // Le client global garde la vérification standard.
        assertNotSame(InsecureTls.anyHostname, base.hostnameVerifier)
        assertNotSame(InsecureTls.trustAll, base.x509TrustManager)
        assertNotSame(InsecureTls.anyHostname, base.forDevice().hostnameVerifier)
    }

    @Test
    fun `fingerprint is formatted as colon separated upper-case hex`() {
        assertEquals("00:0A:FF:7F", InsecureTls.formatFingerprint(byteArrayOf(0, 10, -1, 127)))
    }

    @Test
    fun `fingerprint probe returns null when nothing listens`() {
        val port = ServerSocket(0).use { it.localPort }
        assertNull(InsecureTls.peerFingerprint("127.0.0.1", port, timeoutMs = 500))
    }
}
