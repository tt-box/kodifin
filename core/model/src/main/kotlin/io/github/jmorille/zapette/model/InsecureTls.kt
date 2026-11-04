package io.github.jmorille.zapette.model

import okhttp3.OkHttpClient
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.Certificate
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

    private fun sslContext(): SSLContext =
        SSLContext.getInstance("TLS").apply { init(null, arrayOf<TrustManager>(trustAll), SecureRandom()) }

    fun applyTo(builder: OkHttpClient.Builder): OkHttpClient.Builder =
        builder.sslSocketFactory(sslContext().socketFactory, trustAll).hostnameVerifier(anyHostname)

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
