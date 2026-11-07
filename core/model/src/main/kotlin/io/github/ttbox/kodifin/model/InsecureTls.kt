package io.github.ttbox.kodifin.model

import okhttp3.OkHttpClient
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.Certificate
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * Mode « HTTPS – certificat non vérifié », demandé explicitement par l'utilisateur pour UN
 * appareil (certificat auto-signé d'un serveur domestique). Le chiffrement reste actif mais
 * l'identité du serveur n'est plus vérifiée : une interception (homme du milieu) devient
 * possible sur le réseau. Ce mode n'est jamais appliqué au client HTTP global, seulement au
 * client dérivé de l'appareil concerné ([forDevice]).
 */
object InsecureTls {
    /** Accepte toute chaîne de certificats (choix explicite de l'utilisateur, voir l'objet). */
    @Suppress("TrustAllX509TrustManager", "CustomX509TrustManager")
    val trustAll: X509TrustManager = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    /** Nom d'hôte non vérifié : un certificat auto-signé porte rarement l'IP ou le nom local. */
    @Suppress("BadHostnameVerifier")
    val anyHostname = HostnameVerifier { _, _ -> true }

    private fun sslContext(): SSLContext = sslContext(trustAll)

    private fun sslContext(trustManager: X509TrustManager): SSLContext =
        SSLContext.getInstance("TLS").apply { init(null, arrayOf<TrustManager>(trustManager), SecureRandom()) }

    /**
     * Applique le mode non vérifié à [builder]. Avec [pin] (empreinte enregistrée par
     * « Faire confiance à ce certificat »), seul ce certificat est accepté ([PinnedTrustManager]) ;
     * sans empreinte (appareil configuré avant l'épinglage), tout certificat l'est.
     * Le nom d'hôte n'est jamais vérifié : un certificat auto-signé porte rarement l'IP locale.
     */
    fun applyTo(builder: OkHttpClient.Builder, pin: String? = null): OkHttpClient.Builder {
        // Une empreinte illisible (donnée abîmée) ne retombe pas sur « tout accepter » : elle ne
        // correspond à aucun certificat et la connexion échoue.
        val trustManager = pin?.let { PinnedTrustManager(normalizeFingerprint(it) ?: it) } ?: trustAll
        return builder.sslSocketFactory(sslContext(trustManager).socketFactory, trustManager).hostnameVerifier(anyHostname)
    }

    /**
     * Empreinte normalisée (`AB:CD:…`, 32 octets) à partir d'une saisie tolérante (minuscules,
     * espaces, séparateurs `:` ou absents), ou `null` si ce n'est pas une empreinte SHA-256.
     */
    fun normalizeFingerprint(text: String): String? {
        val hex = text.filter { it.isLetterOrDigit() }.uppercase()
        if (hex.length != 64 || hex.any { it !in '0'..'9' && it !in 'A'..'F' }) return null
        return hex.chunked(2).joinToString(":")
    }

    /**
     * Empreinte SHA-256 du certificat présenté par `host:port`, lue par une simple poignée de
     * main TLS (aucune requête HTTP, donc aucun identifiant envoyé), ou `null` si elle échoue.
     * Sert à afficher l'empreinte à comparer avec celle du serveur.
     */
    fun peerFingerprint(host: String, port: Int, timeoutMs: Int = 5_000): String? = try {
        val socket = sslContext().socketFactory.createSocket() as SSLSocket
        socket.use {
            it.connect(java.net.InetSocketAddress(host, port), timeoutMs)
            it.soTimeout = timeoutMs
            it.startHandshake()
            it.session.peerCertificates.firstOrNull()?.let(::sha256Fingerprint)
        }
    } catch (_: Exception) {
        null
    }

    /** Empreinte au format usuel `AB:CD:…` (32 octets en hexadécimal majuscule). */
    fun sha256Fingerprint(certificate: Certificate): String = formatFingerprint(
        MessageDigest.getInstance("SHA-256").digest(certificate.encoded),
    )

    fun formatFingerprint(digest: ByteArray): String = digest.joinToString(":") { "%02X".format(it.toInt() and 0xFF) }
}

/**
 * Certificat présenté différent de celui auquel l'utilisateur a fait confiance (épinglage
 * TOFU du mode « HTTPS – certificat non vérifié ») : serveur réinstallé, certificat renouvelé…
 * ou interception. Sous-classe de [CertificateException] : OkHttp la remonte comme cause de
 * l'échec de la poignée de main TLS.
 */
class CertificatePinMismatchException(val expected: String, val actual: String) :
    CertificateException("Le certificat de l'appareil a changé (empreinte $actual, attendue $expected)")

/**
 * N'accepte que le certificat feuille dont l'empreinte SHA-256 vaut [pin] (format
 * [InsecureTls.formatFingerprint]). L'empreinte porte sur le certificat entier (DER), comme celle
 * qu'affichent `openssl x509 -fingerprint -sha256` ou un navigateur, ce qui permet de la comparer
 * avec celle du serveur ; un certificat régénéré (même avec la même clé) impose de refaire
 * confiance. Ni la chaîne, ni la date, ni le nom d'hôte ne sont vérifiés : seule compte l'identité
 * du certificat accepté.
 */
@Suppress("CustomX509TrustManager")
class PinnedTrustManager(val pin: String) : X509TrustManager {
    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        throw CertificateException("Authentification client non prise en charge")
    }

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        val leaf = chain?.firstOrNull() ?: throw CertificateException("Aucun certificat présenté par l'appareil")
        val actual = InsecureTls.sha256Fingerprint(leaf)
        if (!actual.equals(pin, ignoreCase = true)) throw CertificatePinMismatchException(pin, actual)
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}
