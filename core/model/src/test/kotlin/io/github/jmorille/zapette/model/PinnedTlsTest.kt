package io.github.jmorille.zapette.model

import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Request
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.security.KeyStore
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.Base64
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Épinglage du certificat auto-signé (mode « HTTPS – certificat non vérifié »). */
class PinnedTlsTest {
    private val keyStore = KeyStore.getInstance("PKCS12").apply {
        load(Base64.getDecoder().decode(KEYSTORE).inputStream(), PASSWORD)
    }
    private val certificate = keyStore.getCertificate("zap") as X509Certificate
    private val fingerprint = InsecureTls.sha256Fingerprint(certificate)
    private val server = MockWebServer()

    @Before
    fun start() {
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(keyStore, PASSWORD) }
        val context = SSLContext.getInstance("TLS").apply { init(kmf.keyManagers, null, null) }
        server.useHttps(context.socketFactory)
        server.start()
    }

    @After
    fun stop() = server.close()

    private fun get(pin: String?): String {
        val client = zapetteHttpClient().forDevice(timeoutSeconds = 5, insecureTls = true, tlsPin = pin)
        server.enqueue(MockResponse.Builder().body("ok").build())
        client.newCall(Request.Builder().url(server.url("/")).build()).execute().use { return it.body.string() }
    }

    @Test
    fun `the trusted certificate is accepted, whatever the host name`() {
        // L'URL vise une adresse IP alors que le certificat ne nomme que « zapette-test ».
        assertEquals("ok", get(fingerprint))
        // Saisie tolérante : minuscules, sans séparateurs.
        assertEquals("ok", get(fingerprint.replace(":", "").lowercase()))
    }

    @Test
    fun `a different certificate is refused with both fingerprints`() {
        val pin = otherFingerprint()
        val error = assertFailsWith<IOException> { get(pin) }
        // OkHttp essaie chaque adresse de « localhost » : l'échec TLS peut n'être qu'une
        // exception supprimée de la dernière tentative (connexion refusée sur ::1).
        val mismatch = allCauses(error).filterIsInstance<CertificatePinMismatchException>().firstOrNull()
            ?: throw AssertionError("Échec non attribué à l'empreinte", error)
        assertEquals(fingerprint, mismatch.actual)
        assertEquals(pin, mismatch.expected)
        assertTrue(mismatch.message!!.contains("a changé"))
    }

    @Test
    fun `without a pin every certificate is still accepted (legacy devices)`() {
        assertEquals("ok", get(null))
    }

    @Test
    fun `an unreadable pin never falls back to trusting everything`() {
        assertFailsWith<IOException> { get("pas une empreinte") }
    }

    @Test
    fun `pinned client uses the pinned trust manager only when insecure mode is on`() {
        val pinned = zapetteHttpClient().forDevice(insecureTls = true, tlsPin = fingerprint)
        assertIs<PinnedTrustManager>(pinned.x509TrustManager)
        assertSame(InsecureTls.anyHostname, pinned.hostnameVerifier)
        val standard = zapetteHttpClient().forDevice(insecureTls = false, tlsPin = fingerprint)
        assertTrue(standard.x509TrustManager !is PinnedTrustManager)
    }

    @Test
    fun `trust manager compares the leaf fingerprint`() {
        val tm = PinnedTrustManager(fingerprint)
        tm.checkServerTrusted(arrayOf(certificate), "ECDHE_ECDSA")
        assertFailsWith<CertificateException> { tm.checkServerTrusted(emptyArray(), "ECDHE_ECDSA") }
        assertFailsWith<CertificatePinMismatchException> {
            PinnedTrustManager(otherFingerprint()).checkServerTrusted(arrayOf(certificate), "ECDHE_ECDSA")
        }
    }

    @Test
    fun `fingerprints are normalised`() {
        val hex = "ab".repeat(32)
        assertEquals(List(32) { "AB" }.joinToString(":"), InsecureTls.normalizeFingerprint(hex))
        assertEquals(fingerprint, InsecureTls.normalizeFingerprint(fingerprint.lowercase().replace(":", " ")))
        assertNull(InsecureTls.normalizeFingerprint("AB:CD"))
        assertNull(InsecureTls.normalizeFingerprint("zz".repeat(32)))
    }

    private fun allCauses(e: Throwable): List<Throwable> =
        listOf(e) + (listOfNotNull(e.cause) + e.suppressed).flatMap(::allCauses)

    /** Empreinte qui diffère de celle du certificat de test par son premier octet. */
    private fun otherFingerprint() = (if (fingerprint.startsWith("00")) "11" else "00") + fingerprint.substring(2)

    private companion object {
        val PASSWORD = "zapette".toCharArray()

        /** Magasin PKCS12 de test : clé EC et certificat auto-signé « CN=zapette-test » (alias `zap`). */
        const val KEYSTORE =
            "MIIECgIBAzCCA7QGCSqGSIb3DQEHAaCCA6UEggOhMIIDnTCCASQGCSqGSIb3DQEHAaCCARUEggERMIIBDTCCAQkGCyqGSIb3DQEM" +
            "CgECoIG9MIG6MGYGCSqGSIb3DQEFDTBZMDgGCSqGSIb3DQEFDDArBBSRVK/bSdpNtouNgeyMfi4pN7y5OQICJxACASAwDAYIKoZI" +
            "hvcNAgkFADAdBglghkgBZQMEASoEENtH0N2s62MwsXitoZmT3CoEULX3yKA1sqr2ZnNsqppsSVPUPRxtVN7URPiLh06kuXY10AJg" +
            "h+kScvoRcDPSU881UfzaTyO/j+iiKYcopyAa5JSPrt8w+MoMtTMBm1lq02hiMTowFQYJKoZIhvcNAQkUMQgeBgB6AGEAcDAhBgkq" +
            "hkiG9w0BCRUxFAQSVGltZSAxNzkzODI4NDI4OTIwMIICcQYJKoZIhvcNAQcGoIICYjCCAl4CAQAwggJXBgkqhkiG9w0BBwEwZgYJ" +
            "KoZIhvcNAQUNMFkwOAYJKoZIhvcNAQUMMCsEFLIvqlBVuQOG5jcjjIcyZewyAMy6AgInEAIBIDAMBggqhkiG9w0CCQUAMB0GCWCG" +
            "SAFlAwQBKgQQG1+mYnSNIPrssinxuWHUdYCCAeBsQbVjJ3b115+o+d5HNbCpC7FskFPZQ796BD+fvS4qvFElK6uJ2Mbre6khfubs" +
            "7S47YSrCRXB0oOLz/ofHoWUB7WQUP2+HtN0w1a0UiTJAs1LeKOFb7rWdIHamQ0HPJjA6JE2eEoiW/JWJb6vFBsRw2NUciVYR3r6c" +
            "99+WW5/8IdBBNsD+2cqMs57E/hAIyZr5s/uYMEB21fynb6NGQIrm62CQS7SdFiculd3wEbeiWAsHqKKvTBwiUuSOUtfy5NWwzmvF" +
            "Tpl7tKRg/yCTXP4cDj3YRNLOOQZEiq57RxBTjQBiNR0CVG3XvevKrvdUL6bgT2AhPISDBoPrFZQ1azMKeqZhaE5Lu2BTVGkuCklK" +
            "FV5hgd7QNns8u6MfpkbbwU27XrFHcAwisNlNMSIVEj/bWxCzywkK1TkpOIoSEYYA9nY+RYmHYjoh1jrvaGj9ZNXyi0tiZBs5NbSK" +
            "vVk7dLc34RNtPH9kgbNxrehwsZucyOd1kZcKWKRfNRBV8tDUG7SWkLLRBUdUZTyT37cNYgkmfxhJNhpN3Ab8WEw1ws5j7tNniXYT" +
            "rSp38de/Q2aLF5izZs2xE53ZyY7Zpd7l93MsJ7g17A+ZiPDMtTKKma8cnDn0Hv9moK0sD8cI9Zovmj4wTTAxMA0GCWCGSAFlAwQC" +
            "AQUABCCaQ0sw29d4G/qfbduqQbNlEzXugQqLr1kRrI5gJF+OjwQUQY+f6GWsutKipcpdpj5D3l3apgkCAicQ"
    }
}
