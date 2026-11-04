package io.github.jmorille.zapette.model

import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Nombre maximal de redirections suivies par défaut (même hôte uniquement). */
const val DEFAULT_MAX_REDIRECTS = 5

/**
 * Client HTTP de l'application : délais courts adaptés au réseau local et politique de
 * redirection stricte (voir [SameHostRedirectInterceptor]).
 *
 * Les redirections automatiques d'OkHttp sont désactivées : en changeant d'hôte, OkHttp ne
 * retire que `Authorization`, si bien que les jetons propres aux protocoles (`X-Plex-Token`,
 * `X-Emby-Token`, `X-Emby-Authorization`) et le corps d'une 307/308 (identifiants
 * d'`AuthenticateByName`) partiraient vers l'hôte cible.
 */
fun zapetteHttpClient(maxRedirects: Int = DEFAULT_MAX_REDIRECTS): OkHttpClient =
    OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .sameHostRedirectsOnly(maxRedirects)
        .build()

/** Applique la politique de redirection de Zapette à un constructeur OkHttp existant. */
fun OkHttpClient.Builder.sameHostRedirectsOnly(maxRedirects: Int = DEFAULT_MAX_REDIRECTS): OkHttpClient.Builder =
    followRedirects(false)
        .followSslRedirects(false)
        .addInterceptor(SameHostRedirectInterceptor(maxRedirects))

/**
 * Redirection refusée par la politique de Zapette. Sous-classe d'[IOException] : une exception
 * d'un autre type levée dans un intercepteur ferait planter le répartiteur d'OkHttp en appel
 * asynchrone. [fetchString] la traduit en [RemoteException] avec le même message.
 */
class RedirectRefusedException(message: String) : IOException(message)

/**
 * Suit au plus [maxRedirects] redirections, et seulement vers la même origine : même hôte et
 * même port, ou passage de `http` à `https` sur le même hôte (port libre). Toute autre cible
 * lève [RedirectRefusedException] sans qu'aucune requête (ni en-tête, ni corps) n'y soit émise.
 */
class SameHostRedirectInterceptor(private val maxRedirects: Int = DEFAULT_MAX_REDIRECTS) : Interceptor {
    init {
        require(maxRedirects >= 0) { "maxRedirects doit être positif ou nul" }
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        var request = chain.request()
        var response = chain.proceed(request)
        var followed = 0
        while (response.isRedirect) {
            val location = response.header("Location") ?: return response
            val from = response.request.url
            val target = from.resolve(location)
            if (target == null) {
                response.close()
                throw RedirectRefusedException("Redirection invalide depuis ${from.host}")
            }
            if (!isSameOrigin(from, target)) {
                response.close()
                throw RedirectRefusedException(
                    "Redirection refusée de ${from.host}:${from.port} vers ${target.host}:${target.port} (hôte ou port différent)",
                )
            }
            if (followed >= maxRedirects) {
                response.close()
                throw RedirectRefusedException("Trop de redirections (plus de $maxRedirects) depuis ${from.host}")
            }
            val next = redirectRequest(request, response.code, target) ?: return response
            followed++
            response.close()
            request = next
            response = chain.proceed(request)
        }
        return response
    }

    /** Requête à rejouer vers [target] ; `null` si le corps ne peut pas être renvoyé. */
    private fun redirectRequest(previous: Request, code: Int, target: HttpUrl): Request? {
        val builder = previous.newBuilder().url(target)
        val method = previous.method
        // 307/308 conservent méthode et corps ; 301/302/303 repassent en GET sans corps
        // (comportement des navigateurs et d'OkHttp), sauf pour GET/HEAD inchangés.
        val keepsMethod = code == 307 || code == 308 || method == "GET" || method == "HEAD"
        if (keepsMethod) {
            val body = previous.body
            if (body != null && body.isOneShot()) return null
            return builder.build()
        }
        return builder.get()
            .removeHeader("Content-Type")
            .removeHeader("Content-Length")
            .removeHeader("Transfer-Encoding")
            .build()
    }

    companion object {
        /** Même hôte ; port identique (et même schéma) ou montée de `http` vers `https`. */
        fun isSameOrigin(from: HttpUrl, to: HttpUrl): Boolean {
            if (!from.host.equals(to.host, ignoreCase = true)) return false
            if (from.scheme == to.scheme) return from.port == to.port
            return from.scheme == "http" && to.scheme == "https"
        }
    }
}

/** Délai de connexion par défaut d'un appareil, en secondes (bornes : 1 à 60). */
const val DEFAULT_CONNECT_TIMEOUT_S = 5
const val MIN_CONNECT_TIMEOUT_S = 1
const val MAX_CONNECT_TIMEOUT_S = 60

/**
 * Client dérivé pour un appareil : partage le pool de connexions et la politique de
 * redirection de `this`, avec des délais proportionnels à [timeoutSeconds] (connexion ×1,
 * lecture ×3, appel complet ×6, soit 5 / 15 / 30 s par défaut) et, si [insecureTls] est vrai,
 * une validation TLS désactivée (voir [InsecureTls]). Le client d'origine n'est jamais modifié.
 */
fun OkHttpClient.forDevice(timeoutSeconds: Int = DEFAULT_CONNECT_TIMEOUT_S, insecureTls: Boolean = false): OkHttpClient {
    val t = timeoutSeconds.toLong().coerceIn(MIN_CONNECT_TIMEOUT_S.toLong(), MAX_CONNECT_TIMEOUT_S.toLong())
    val builder = newBuilder()
        .connectTimeout(t, TimeUnit.SECONDS)
        .readTimeout(t * 3, TimeUnit.SECONDS)
        .writeTimeout(t * 3, TimeUnit.SECONDS)
        .callTimeout(t * 6, TimeUnit.SECONDS)
    if (insecureTls) InsecureTls.applyTo(builder)
    return builder.build()
}
