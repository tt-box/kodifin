package io.github.ttbox.kodifin.connection

import io.github.ttbox.kodifin.R
import io.github.ttbox.kodifin.text.UiText
import io.github.ttbox.kodifin.model.Capability
import io.github.ttbox.kodifin.model.CertificatePinMismatchException
import io.github.ttbox.kodifin.model.PinnedTrustManager
import io.github.ttbox.kodifin.model.DescribesServer
import io.github.ttbox.kodifin.model.Device
import io.github.ttbox.kodifin.model.DeviceKind
import io.github.ttbox.kodifin.model.HttpStatusException
import io.github.ttbox.kodifin.model.InsecureTls
import io.github.ttbox.kodifin.model.RedirectRefusedException
import io.github.ttbox.kodifin.model.RemoteController
import io.github.ttbox.kodifin.model.RemoteError
import io.github.ttbox.kodifin.model.Setup
import io.github.ttbox.kodifin.model.RemoteException
import io.github.ttbox.kodifin.model.RemoteKey
import io.github.ttbox.kodifin.model.kodifinHttpClient
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.SerializationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.cert.CertPathValidatorException
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException

private val JELLYFIN = Device("J", "thor", DeviceKind.JELLYFIN, "10.0.0.3", 8920)
private val PLEX = Device("P", "Plex", DeviceKind.PLEX, "10.0.0.4", 32400)

class ConnectionModeOptionsTest {
    @Test
    fun `connection modes are stored with scheme and tls_insecure`() {
        val https = DeviceOptions.apply(emptyMap(), DeviceKind.JELLYFIN, AdvancedOptions(mode = ConnectionMode.HTTPS))
        assertEquals(mapOf("scheme" to "https"), https)
        val insecure = DeviceOptions.apply(emptyMap(), DeviceKind.KODI, AdvancedOptions(mode = ConnectionMode.HTTPS_INSECURE))
        assertEquals(mapOf("scheme" to "https", "tls_insecure" to "true"), insecure)
        // Retour en HTTP : les deux clés disparaissent.
        assertEquals(emptyMap<String, String>(), DeviceOptions.apply(insecure, DeviceKind.KODI, AdvancedOptions()))
        // Roku n'a pas de mode HTTPS.
        assertEquals(emptyMap<String, String>(), DeviceOptions.apply(emptyMap(), DeviceKind.ROKU, AdvancedOptions(mode = ConnectionMode.HTTPS)))
    }

    @Test
    fun `legacy https extra reads as HTTPS mode and tls_insecure alone is ignored`() {
        val legacy = JELLYFIN.copy(extras = mapOf("scheme" to "https"))
        assertEquals(ConnectionMode.HTTPS, DeviceOptions.read(legacy).mode)
        assertTrue(DeviceOptions.read(legacy).https)
        assertFalse(DeviceOptions.insecureTls(legacy))
        assertEquals(ConnectionMode.HTTP, DeviceOptions.connectionMode(JELLYFIN.copy(extras = mapOf("tls_insecure" to "true"))))
        val insecure = PLEX.copy(extras = mapOf("scheme" to "https", "tls_insecure" to "true"))
        assertEquals(ConnectionMode.HTTPS_INSECURE, DeviceOptions.read(insecure).mode)
        assertTrue(DeviceOptions.insecureTls(insecure))
    }

    @Test
    fun `timeout is stored only when different from the default and validated`() {
        assertEquals(mapOf("timeout_s" to "12"), DeviceOptions.apply(emptyMap(), DeviceKind.ROKU, AdvancedOptions(timeoutS = "12")))
        assertEquals(emptyMap<String, String>(), DeviceOptions.apply(emptyMap(), DeviceKind.ROKU, AdvancedOptions(timeoutS = "5")))
        assertEquals(12, DeviceOptions.timeoutSeconds(PLEX.copy(extras = mapOf("timeout_s" to "12"))))
        assertEquals(5, DeviceOptions.timeoutSeconds(PLEX.copy(extras = mapOf("timeout_s" to "999"))))
        assertEquals(5, DeviceOptions.timeoutSeconds(null))
        val k = DeviceKind.KODI
        for (bad in listOf("0", "61", "", "x")) {
            assertEquals(bad, UiText.Res(R.string.sys_options_invalid_timeout, 1, 60), DeviceOptions.error(k, AdvancedOptions(timeoutS = bad)))
        }
        assertNull(DeviceOptions.error(k, AdvancedOptions(timeoutS = "60")))
    }

    @Test
    fun `wifi restriction, event server and alternative DIDL round-trip`() {
        val options = AdvancedOptions(
            wifiOnly = true, wifiSsids = " Maison \n\n\"Bureau\"\nMaison",
            kodiEventServer = true, kodiEventServerPort = "9778",
        )
        val extras = DeviceOptions.apply(emptyMap(), DeviceKind.KODI, options)
        assertEquals(
            mapOf(
                "wifi_only" to "true", "wifi_ssids" to "Maison\nBureau",
                "kodi_eventserver" to "true", "kodi_eventserver_port" to "9778",
            ),
            extras,
        )
        val device = JELLYFIN.copy(kind = DeviceKind.KODI, extras = extras)
        assertEquals(options.copy(wifiSsids = "Maison\nBureau"), DeviceOptions.read(device))
        assertEquals(NetworkPolicy(true, setOf("Maison", "Bureau")), DeviceOptions.networkPolicy(device))

        val upnp = DeviceOptions.apply(emptyMap(), DeviceKind.UPNP_RENDERER, AdvancedOptions(upnpAltDidl = true, kodiEventServer = true))
        assertEquals(mapOf("upnp_alt_didl" to "true"), upnp)
        assertEquals(emptyMap<String, String>(), DeviceOptions.apply(emptyMap(), DeviceKind.KODI, AdvancedOptions(upnpAltDidl = true)))
    }

    @Test
    fun `event server port and SSID length are validated`() {
        assertEquals(
            UiText.Res(R.string.sys_options_invalid_eventserver_port),
            DeviceOptions.error(DeviceKind.KODI, AdvancedOptions(kodiEventServer = true, kodiEventServerPort = "0")),
        )
        assertNull(DeviceOptions.error(DeviceKind.ROKU, AdvancedOptions(kodiEventServer = true, kodiEventServerPort = "0")))
        assertEquals(
            UiText.Res(R.string.sys_options_ssid_too_long, 32),
            DeviceOptions.error(DeviceKind.ROKU, AdvancedOptions(wifiSsids = "é".repeat(17))),
        )
        assertNull(DeviceOptions.error(DeviceKind.ROKU, AdvancedOptions(wifiSsids = "a".repeat(32))))
    }

    @Test
    fun `form base URL follows the mode, brackets IPv6 and appends the base path`() {
        assertEquals("http://10.0.0.3:8096", DeviceOptions.formBaseUrl(ConnectionMode.HTTP, " 10.0.0.3 ", 8096))
        assertEquals("https://[fd00::3]:8920/jellyfin", DeviceOptions.formBaseUrl(ConnectionMode.HTTPS_INSECURE, "fd00::3", 8920, "jellyfin/"))
        assertNull(DeviceOptions.formBaseUrl(ConnectionMode.HTTP, "", 8096))
        assertNull(DeviceOptions.formBaseUrl(ConnectionMode.HTTP, "a b", 8096))
        assertNull(DeviceOptions.formBaseUrl(ConnectionMode.HTTP, "nas", 0))
    }

    @Test
    fun `derived client applies timeout and insecure TLS for this device only`() {
        val base = kodifinHttpClient()
        val insecure = base.forDevice(PLEX.copy(extras = mapOf("scheme" to "https", "tls_insecure" to "true", "timeout_s" to "10")))
        assertEquals(10_000, insecure.connectTimeoutMillis)
        assertEquals(60_000, insecure.callTimeoutMillis)
        assertSame(InsecureTls.anyHostname, insecure.hostnameVerifier)
        val other = base.forDevice(JELLYFIN)
        assertEquals(5_000, other.connectTimeoutMillis)
        assertNotSame(InsecureTls.anyHostname, other.hostnameVerifier)
        assertNotSame(InsecureTls.anyHostname, base.hostnameVerifier)
    }
}

class CertificatePinOptionsTest {
    private val pin = List(32) { "AB" }.joinToString(":")

    @Test
    fun `the trusted fingerprint is stored normalised, only in insecure mode`() {
        val options = AdvancedOptions(mode = ConnectionMode.HTTPS_INSECURE, tlsPin = pin.lowercase().replace(":", ""))
        val extras = DeviceOptions.apply(emptyMap(), DeviceKind.JELLYFIN, options)
        assertEquals(mapOf("scheme" to "https", "tls_insecure" to "true", "tls_pin" to pin), extras)
        val device = JELLYFIN.copy(extras = extras)
        assertEquals(pin, DeviceOptions.read(device).tlsPin)
        assertEquals(pin, DeviceOptions.tlsPin(device))
        // Mode vérifié ou HTTP : l'empreinte n'a plus de sens et disparaît.
        assertEquals(mapOf("scheme" to "https"), DeviceOptions.apply(extras, DeviceKind.JELLYFIN, options.copy(mode = ConnectionMode.HTTPS)))
        assertNull(DeviceOptions.tlsPin(JELLYFIN.copy(extras = mapOf("scheme" to "https", "tls_pin" to pin))))
        // Ancien appareil sans empreinte : pas d'épinglage.
        assertNull(DeviceOptions.tlsPin(JELLYFIN.copy(extras = mapOf("scheme" to "https", "tls_insecure" to "true"))))
    }

    @Test
    fun `an invalid fingerprint is refused by the form`() {
        assertEquals(
            UiText.Res(R.string.sys_options_invalid_fingerprint),
            DeviceOptions.error(DeviceKind.KODI, AdvancedOptions(mode = ConnectionMode.HTTPS_INSECURE, tlsPin = "AB:CD")),
        )
        assertNull(DeviceOptions.error(DeviceKind.KODI, AdvancedOptions(mode = ConnectionMode.HTTPS_INSECURE, tlsPin = pin)))
    }

    @Test
    fun `the derived client pins the trusted certificate`() {
        val device = JELLYFIN.copy(extras = mapOf("scheme" to "https", "tls_insecure" to "true", "tls_pin" to pin))
        val client = kodifinHttpClient().forDevice(device)
        assertEquals(pin, (client.x509TrustManager as PinnedTrustManager).pin)
        assertSame(InsecureTls.anyHostname, client.hostnameVerifier)
    }
}

class PlexOptionsTest {
    @Test
    fun `https is no longer offered for plex but a legacy setting is kept`() {
        assertFalse(DeviceOptions.supportsHttps(DeviceKind.PLEX))
        assertTrue(DeviceOptions.supportsHttps(DeviceKind.KODI))
        val legacy = PLEX.copy(extras = mapOf("scheme" to "https", "tls_insecure" to "true"))
        assertEquals(ConnectionMode.HTTPS_INSECURE, DeviceOptions.connectionMode(legacy))
        assertTrue(DeviceOptions.legacyHttps(legacy))
        assertFalse(DeviceOptions.legacyHttps(PLEX))
        assertFalse(DeviceOptions.legacyHttps(JELLYFIN.copy(extras = mapOf("scheme" to "https"))))
        // Réenregistré sans changement : reconduit ; « Passer en HTTP » : retiré.
        val read = DeviceOptions.read(legacy)
        assertEquals(legacy.extras, DeviceOptions.apply(legacy.extras, DeviceKind.PLEX, read))
        assertEquals(emptyMap<String, String>(), DeviceOptions.apply(legacy.extras, DeviceKind.PLEX, read.copy(mode = ConnectionMode.HTTP)))
    }

    @Test
    fun `plex client identifier is an advanced option`() {
        val extras = DeviceOptions.apply(mapOf("token_enc" to "enc:x"), DeviceKind.PLEX, AdvancedOptions(plexClientId = " abc123 "))
        assertEquals(mapOf("token_enc" to "enc:x", "clientIdentifier" to "abc123"), extras)
        assertEquals("abc123", DeviceOptions.read(PLEX.copy(extras = extras)).plexClientId)
        // Vide : détecté à la connexion, la clé disparaît.
        assertEquals(mapOf("token_enc" to "enc:x"), DeviceOptions.apply(extras, DeviceKind.PLEX, AdvancedOptions()))
        assertEquals(
            UiText.Res(R.string.sys_options_invalid_plex_client_id),
            DeviceOptions.error(DeviceKind.PLEX, AdvancedOptions(plexClientId = "a b")),
        )
        assertNull(DeviceOptions.error(DeviceKind.KODI, AdvancedOptions(plexClientId = "a b")))
    }
}

class DiagnosisTest {
    @Test
    fun `a changed pinned certificate is reported with its fingerprint, even as a suppressed error`() {
        val old = List(32) { "AB" }.joinToString(":")
        val new = List(32) { "CD" }.joinToString(":")
        val handshake = SSLHandshakeException("Échec").apply { initCause(CertificatePinMismatchException(old, new)) }
        for (error in listOf(
            handshake,
            RemoteException(RemoteError.Unreachable(), handshake),
            // OkHttp : dernière adresse refusée, l'échec TLS de la première est supprimé.
            ConnectException("Failed to connect to localhost/[::1]").apply { addSuppressed(handshake) },
        )) {
            val d = diagnose(error)
            assertEquals(Problem.CERTIFICATE_CHANGED, d.problem)
            assertEquals(UiText.Res(R.string.diag_certificate_changed_title, new), d.title)
            assertEquals(UiText.Res(R.string.diag_certificate_changed_advice, old), d.advice)
        }
    }

    @Test
    fun `failures are classified with advice`() {
        val table: List<Pair<Throwable, Problem>> = listOf(
            UnknownHostException("kodi.lan") to Problem.UNKNOWN_HOST,
            RemoteException(RemoteError.Unreachable("kodi.lan"), UnknownHostException("kodi.lan")) to Problem.UNKNOWN_HOST,
            ConnectException("Connection refused") to Problem.REFUSED,
            RemoteException(RemoteError.Unreachable(), ConnectException("failed to connect: ECONNREFUSED")) to Problem.REFUSED,
            NoRouteToHostException("No route to host") to Problem.UNREACHABLE,
            ConnectException("failed to connect to /10.0.0.2 (port 8080): connect failed: EHOSTUNREACH (No route to host)") to
                Problem.UNREACHABLE,
            SocketTimeoutException("connect timed out") to Problem.TIMEOUT,
            RemoteException(RemoteError.Unreachable(), InterruptedIOException("timeout")) to Problem.TIMEOUT,
            HttpStatusException(401) to Problem.CREDENTIALS,
            HttpStatusException(403) to Problem.CREDENTIALS,
            HttpStatusException(404) to Problem.UNEXPECTED_RESPONSE,
            HttpStatusException(500) to Problem.HTTP,
            SSLHandshakeException("PKIX path building failed").apply { initCause(CertPathValidatorException("trust anchor")) } to
                Problem.CERTIFICATE,
            SSLHandshakeException("java.security.cert.CertPathValidatorException: Trust anchor for certification path not found.") to
                Problem.CERTIFICATE,
            SSLPeerUnverifiedException("Hostname 10.0.0.3 not verified") to Problem.CERTIFICATE,
            SSLException("Unrecognized SSL message, plaintext connection?") to Problem.TLS,
            RemoteException(RemoteError.RedirectRefused, RedirectRefusedException("Redirection refusée de a:1 vers b:2")) to Problem.REDIRECT,
            RemoteException(RemoteError.UnexpectedResponse(), SerializationException("x")) to Problem.UNEXPECTED_RESPONSE,
            RemoteException(RemoteError.UnexpectedResponse("Kodi")) to Problem.UNEXPECTED_RESPONSE,
        )
        for ((error, expected) in table) {
            val d = diagnose(error)
            assertEquals(error.toString(), expected, d.problem)
            assertTrue(error.toString(), d.advice is UiText.Res)
        }
    }

    @Test
    fun `certificate advice proposes the self-signed mode and unknown errors keep their message`() {
        assertEquals(UiText.Res(R.string.diag_certificate_advice), diagnose(SSLPeerUnverifiedException("x")).advice)
        // Raison typée sans diagnostic réseau : son propre texte, traduit.
        assertEquals(
            Diagnosis(UiText.Res(R.string.error_no_controllable_client), null, Problem.OTHER),
            diagnose(RemoteException(RemoteError.NoControllableClient)),
        )
        // Conseil précis porté par la raison (réglage à faire sur l'appareil).
        assertEquals(
            UiText.Res(R.string.error_setup_roku_network_access),
            diagnose(RemoteException(RemoteError.SetupRequired(Setup.ROKU_NETWORK_ACCESS))).title,
        )
        assertEquals(UiText.Res(R.string.error_unknown, "IOException"), diagnose(IOException()).title)
    }

    @Test
    fun `cause cycles do not loop`() {
        val a = IOException("a")
        val b = IOException("b", a)
        a.initCause(b)
        assertEquals(Problem.OTHER, diagnose(a).problem)
    }
}

class NetworkGuardTest {
    private class Net(val wifi: Boolean, val ssid: String?, val readable: Boolean = true) : NetworkInfo {
        override fun isWifiConnected() = wifi
        override fun currentSsid() = ssid
        override fun canReadSsid() = readable
    }

    @Test
    fun `guard verdicts`() {
        val any = NetworkPolicy()
        val wifi = NetworkPolicy(wifiOnly = true)
        val home = NetworkPolicy(allowedSsids = setOf("Maison"))
        assertEquals(NetworkCheck.Allowed(), NetworkGuard.check(any, Net(false, null)))
        assertEquals(NetworkCheck.Allowed(), NetworkGuard.check(wifi, null))
        assertEquals(NetworkCheck.Blocked(UiText.Res(R.string.sys_network_wifi_required)), NetworkGuard.check(wifi, Net(false, null)))
        assertEquals(NetworkCheck.Allowed(), NetworkGuard.check(wifi, Net(true, null, readable = false)))
        // Une liste de SSID implique le Wi-Fi.
        assertEquals(NetworkCheck.Blocked(UiText.Res(R.string.sys_network_wifi_required)), NetworkGuard.check(home, Net(false, null)))
        assertEquals(NetworkCheck.Allowed(), NetworkGuard.check(home, Net(true, "Maison")))
        assertEquals(
            NetworkCheck.Blocked(UiText.Res(R.string.sys_network_ssid_not_allowed, "Voisin")),
            NetworkGuard.check(home, Net(true, "Voisin")),
        )
    }

    @Test
    fun `unreadable SSID does not block but warns`() {
        val home = NetworkPolicy(allowedSsids = setOf("Maison"))
        val noPermission = NetworkGuard.check(home, Net(true, null, readable = false))
        assertTrue(noPermission == NetworkCheck.Allowed(UiText.Res(R.string.sys_network_ssid_needs_location)))
        val unknown = NetworkGuard.check(home, Net(true, null, readable = true))
        assertTrue(unknown == NetworkCheck.Allowed(UiText.Res(R.string.sys_network_ssid_unknown)))
    }

    @Test
    fun `SSID normalisation and list editing`() {
        assertEquals("Maison", NetworkGuard.normalizeSsid("\"Maison\""))
        assertNull(NetworkGuard.normalizeSsid("<unknown ssid>"))
        assertNull(NetworkGuard.normalizeSsid(""))
        assertNull(NetworkGuard.normalizeSsid(null))
        assertEquals("Maison", NetworkGuard.addSsid("", "Maison"))
        assertEquals("Maison\nBureau", NetworkGuard.addSsid("Maison\n", "Bureau"))
        assertEquals("Maison", NetworkGuard.addSsid("Maison", "Maison"))
    }
}

class ConnectionTestDescriptionTest {
    private open class Fake(override val device: Device, val onConnect: suspend () -> Unit = {}) : RemoteController {
        override val capabilities = setOf(Capability.NAVIGATION)
        override val supportedKeys = setOf(RemoteKey.UP)
        override suspend fun connect() = onConnect()
        override suspend fun send(key: RemoteKey) = Unit
    }

    private class Described(device: Device, val description: suspend () -> String?) : Fake(device), DescribesServer {
        override suspend fun serverDescription() = description()
    }

    @Test
    fun `success shows the server version when available`() = runTest {
        val result = testConnection(JELLYFIN) { Described(it) { "Jellyfin Server 10.10.3 – thor" } }
        assertEquals(ConnectionTestResult.Success(UiText.Res(R.string.conn_test_success_described, "Jellyfin Server 10.10.3 – thor")), result)
    }

    @Test
    fun `unreadable version keeps the plain success message`() = runTest {
        val result = testConnection(JELLYFIN) { Described(it) { throw RemoteException(RemoteError.Http(404)) } }
        assertEquals(ConnectionTestResult.Success(UiText.Res(R.string.conn_test_success, "thor", "Jellyfin")), result)
    }

    @Test
    fun `insecure mode success carries the certificate fingerprint`() = runTest {
        val device = PLEX.copy(extras = mapOf("scheme" to "https", "tls_insecure" to "true"))
        val result = testConnection(device, fingerprint = { "AB:CD" }) { Fake(it) }
        assertEquals("AB:CD", result.certificateSha256)
        // Mode vérifié : empreinte non lue en cas de succès.
        assertNull(testConnection(PLEX, fingerprint = { "AB:CD" }) { Fake(it) }.certificateSha256)
    }

    @Test
    fun `failures are diagnosed and certificate failures carry the fingerprint`() = runTest {
        val refused = testConnection(JELLYFIN) { Fake(it) { throw RemoteException(RemoteError.CredentialsRejected, HttpStatusException(401)) } }
        refused as ConnectionTestResult.Failure
        assertEquals(Problem.CREDENTIALS, refused.problem)
        assertEquals(UiText.Res(R.string.diag_credentials_title, 401), refused.message)
        assertEquals(UiText.Res(R.string.diag_credentials_advice), refused.advice)

        val cert = testConnection(JELLYFIN, fingerprint = { "11:22" }) {
            Fake(it) { throw RemoteException(RemoteError.Unreachable(), SSLPeerUnverifiedException("Hostname not verified")) }
        }
        cert as ConnectionTestResult.Failure
        assertEquals(Problem.CERTIFICATE, cert.problem)
        assertEquals("11:22", cert.certificateSha256)
    }

    @Test
    fun `test timeout scales with the device delay`() {
        assertEquals(10_000, testTimeoutMs(JELLYFIN))
        assertEquals(40_000, testTimeoutMs(JELLYFIN.copy(extras = mapOf("timeout_s" to "20"))))
    }
}
