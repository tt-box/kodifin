package io.github.jmorille.zapette.firetv

import java.net.InetSocketAddress
import java.net.Socket
import java.security.Principal
import java.security.PrivateKey
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509ExtendedKeyManager
import javax.net.ssl.X509ExtendedTrustManager

/**
 * Tunnel TLS 1.3 tel qu'utilisé par ADB sans fil (Android 11+) : appairage
 * (`_adb-tls-pairing._tcp`) et connexion (`_adb-tls-connect._tcp`, après le message STLS).
 *
 * - Le client présente toujours le certificat dérivé de sa clé ADB. Le téléviseur envoie une
 *   liste d'autorités fictive (empreintes des clés connues) : un gestionnaire de clés standard
 *   filtrerait sur cette liste et n'enverrait rien, d'où [FixedKeyManager].
 * - Le certificat du téléviseur est auto-signé et n'est pas vérifiable (l'outil `adb` d'AOSP
 *   l'accepte aussi sans contrôle) : à l'appairage, l'authentification repose sur SPAKE2, lié
 *   au tunnel par l'export de matériel de clé ; à la connexion, c'est le téléviseur qui
 *   authentifie notre clé.
 */
internal object AdbTls {
    const val PROTOCOL = "TLSv1.3"

    /** Ouvre une connexion TCP bornée par les délais. */
    fun connect(host: String, port: Int, connectTimeoutMs: Int, socketTimeoutMs: Int): Socket =
        Socket().apply {
            try {
                connect(InetSocketAddress(host, port), connectTimeoutMs)
                soTimeout = socketTimeoutMs
                tcpNoDelay = true
            } catch (e: Exception) {
                runCatching { close() }
                throw e
            }
        }

    /** Superpose TLS 1.3 (rôle client) au socket et effectue la poignée de main. */
    fun handshake(socket: Socket, host: String, port: Int, identity: AdbIdentity, socketTimeoutMs: Int): SSLSocket {
        val context = SSLContext.getInstance("TLS")
        context.init(arrayOf(FixedKeyManager(identity)), arrayOf(PeerCertificateTrustManager), null)
        val ssl = context.socketFactory.createSocket(socket, host, port, true) as SSLSocket
        try {
            if (PROTOCOL !in ssl.supportedProtocols) {
                throw UnsupportedOperationException("TLS 1.3 indisponible (Android 10 ou plus requis sur le téléphone)")
            }
            ssl.enabledProtocols = arrayOf(PROTOCOL)
            ssl.useClientMode = true
            ssl.soTimeout = socketTimeoutMs
            ssl.startHandshake()
            return ssl
        } catch (e: Exception) {
            runCatching { ssl.close() }
            throw e
        }
    }
}

/** Présente toujours le certificat de l'application, quelle que soit la liste d'autorités demandée. */
internal class FixedKeyManager(private val identity: AdbIdentity) : X509ExtendedKeyManager() {
    override fun chooseClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, socket: Socket?) = ALIAS
    override fun chooseEngineClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, engine: SSLEngine?) = ALIAS
    override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?) = arrayOf(ALIAS)
    override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? = null
    override fun chooseServerAlias(keyType: String?, issuers: Array<out Principal>?, socket: Socket?): String? = null
    override fun getCertificateChain(alias: String?): Array<X509Certificate>? =
        if (alias == ALIAS) arrayOf(identity.certificate) else null
    override fun getPrivateKey(alias: String?): PrivateKey? = if (alias == ALIAS) identity.privateKey else null

    private companion object {
        const val ALIAS = "adb"
    }
}

/**
 * Accepte le certificat auto-signé présenté par le téléviseur (voir [AdbTls]) ; exige
 * seulement qu'un certificat soit présenté. Utilisé uniquement pour ADB, jamais pour HTTPS.
 */
internal object PeerCertificateTrustManager : X509ExtendedTrustManager() {
    private fun requireCertificate(chain: Array<out X509Certificate>?) {
        if (chain.isNullOrEmpty()) throw CertificateException("Aucun certificat présenté par l'appareil ADB")
    }

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = requireCertificate(chain)
    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?) =
        requireCertificate(chain)
    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) =
        requireCertificate(chain)
    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) =
        throw CertificateException("Rôle serveur non pris en charge")
    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?) =
        throw CertificateException("Rôle serveur non pris en charge")
    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) =
        throw CertificateException("Rôle serveur non pris en charge")
    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}

/**
 * Export de matériel de clé TLS (RFC 8446 §7.5), nécessaire pour lier SPAKE2 au tunnel.
 * Pas d'API standard commune : Android 10+ fournit `android.net.ssl.SSLSockets`, le JDK 25+
 * `ExtendedSSLSession.exportKeyingMaterialData`. Appel par réflexion pour garder ce module
 * en Kotlin/JVM pur.
 */
fun interface TlsKeyExporter {
    fun export(socket: SSLSocket, label: String, length: Int): ByteArray

    companion object {
        val DEFAULT = TlsKeyExporter { socket, label, length ->
            androidExport(socket, label, length) ?: jdkExport(socket, label, length)
                ?: throw UnsupportedOperationException("Export de matériel de clé TLS indisponible (Android 10 ou plus requis)")
        }

        private fun androidExport(socket: SSLSocket, label: String, length: Int): ByteArray? {
            val sockets = runCatching { Class.forName("android.net.ssl.SSLSockets") }.getOrNull() ?: return null
            val method = sockets.getMethod(
                "exportKeyingMaterial", SSLSocket::class.java, String::class.java, ByteArray::class.java, Int::class.javaPrimitiveType,
            )
            return unwrap { method.invoke(null, socket, label, null, length) as ByteArray }
        }

        private fun jdkExport(socket: SSLSocket, label: String, length: Int): ByteArray? {
            val session = socket.session as? javax.net.ssl.ExtendedSSLSession ?: return null
            val method = runCatching {
                javax.net.ssl.ExtendedSSLSession::class.java.getMethod("exportKeyingMaterialData", String::class.java, ByteArray::class.java, Int::class.javaPrimitiveType)
            }.getOrNull() ?: return null
            return unwrap { method.invoke(session, label, null, length) as ByteArray }
        }

        private inline fun unwrap(block: () -> ByteArray): ByteArray = try {
            block()
        } catch (e: java.lang.reflect.InvocationTargetException) {
            throw e.targetException
        }
    }
}
