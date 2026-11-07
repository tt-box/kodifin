package io.github.ttbox.kodifin.airplay

import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Authentification HTTP Digest (RFC 2617, MD5) des récepteurs AirPlay 1 protégés par mot de
 * passe : utilisateur « AirPlay », realm annoncé par le récepteur (« AirPlay »).
 *
 * Le dernier défi reçu est mémorisé pour signer d'emblée les requêtes suivantes (évite un
 * aller-retour 401 par commande) ; un nouveau 401 avec un autre nonce (ou `stale=true`) déclenche
 * un unique nouvel essai. Le mot de passe n'est jamais journalisé ni inclus dans un message
 * (seul le condensé MD5 transite) ; [toString] le masque.
 */
internal class AirPlayDigestAuth(private val password: String) : Interceptor {

    internal data class Challenge(val realm: String, val nonce: String, val qop: String?, val opaque: String?, val stale: Boolean)

    @Volatile
    private var challenge: Challenge? = null
    private var nonceCount = 0L
    private val random = SecureRandom()

    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        val known = challenge
        val response = chain.proceed(known?.let { sign(original, it) } ?: original)
        if (response.code != 401) return response
        val fresh = response.headers("WWW-Authenticate").firstNotNullOfOrNull(::parseChallenge) ?: return response
        // Même nonce déjà utilisé et non périmé : le mot de passe est faux, inutile de réessayer.
        if (known != null && known.nonce == fresh.nonce && !fresh.stale) return response
        challenge = fresh
        response.close()
        return chain.proceed(sign(original, fresh))
    }

    private fun sign(request: Request, c: Challenge): Request {
        val uri = request.url.encodedPath + (request.url.encodedQuery?.let { "?$it" } ?: "")
        val ha1 = md5("$USER:${c.realm}:$password")
        val ha2 = md5("${request.method}:$uri")
        val qopAuth = c.qop?.split(',')?.map { it.trim() }?.any { it.equals("auth", ignoreCase = true) } == true
        val header = buildString {
            append("Digest username=\"").append(USER)
                .append("\", realm=\"").append(c.realm)
                .append("\", nonce=\"").append(c.nonce)
                .append("\", uri=\"").append(uri)
            if (qopAuth) {
                val nc = "%08x".format(synchronized(this@AirPlayDigestAuth) { ++nonceCount })
                val cnonce = ByteArray(8).also { random.nextBytes(it) }.joinToString("") { "%02x".format(it.toInt() and 0xff) }
                append("\", response=\"").append(md5("$ha1:${c.nonce}:$nc:$cnonce:auth:$ha2"))
                append("\", qop=auth, nc=").append(nc).append(", cnonce=\"").append(cnonce)
            } else {
                append("\", response=\"").append(md5("$ha1:${c.nonce}:$ha2"))
            }
            append('"')
            c.opaque?.let { append(", opaque=\"").append(it).append('"') }
        }
        return request.newBuilder().header("Authorization", header).build()
    }

    override fun toString() = "AirPlayDigestAuth(password=***)"

    companion object {
        /** Nom d'utilisateur fixe des récepteurs AirPlay 1. */
        const val USER = "AirPlay"

        private val PARAM = Regex("""([A-Za-z]+)\s*=\s*(?:"([^"]*)"|([^\s,]+))""")

        /** Analyse un en-tête `WWW-Authenticate: Digest …` ; `null` s'il ne s'agit pas d'un défi Digest exploitable. */
        internal fun parseChallenge(header: String): Challenge? {
            val trimmed = header.trim()
            if (!trimmed.startsWith("Digest", ignoreCase = true)) return null
            val params = PARAM.findAll(trimmed.substring(6)).associate {
                it.groupValues[1].lowercase() to (it.groups[2]?.value ?: it.groupValues[3])
            }
            val algorithm = params["algorithm"]
            if (algorithm != null && !algorithm.equals("MD5", ignoreCase = true)) return null
            val nonce = params["nonce"]?.ifEmpty { null } ?: return null
            // Valeurs recopiées entre guillemets dans l'en-tête Authorization : refus des caractères qui le casseraient.
            if (listOf(nonce, params["realm"], params["opaque"]).any { v -> v != null && v.any { it == '"' || it == '\\' || it.isISOControl() } }) {
                return null
            }
            return Challenge(
                realm = params["realm"] ?: USER,
                nonce = nonce,
                qop = params["qop"],
                opaque = params["opaque"],
                stale = params["stale"].equals("true", ignoreCase = true),
            )
        }

        internal fun md5(value: String): String =
            MessageDigest.getInstance("MD5").digest(value.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}
