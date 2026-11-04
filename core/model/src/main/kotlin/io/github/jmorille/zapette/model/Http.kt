package io.github.jmorille.zapette.model

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
val XML_MEDIA_TYPE = "text/xml; charset=\"utf-8\"".toMediaType()

/** Exécute l'appel de façon suspendue et annulable. */
suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    cont.invokeOnCancellation { runCatching { cancel() } }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (cont.isActive) cont.resumeWithException(e)
        }

        override fun onResponse(call: Call, response: Response) {
            // Si la coroutine est annulée entre-temps (y compris pendant la reprise),
            // la réponse n'est jamais livrée : on la ferme pour libérer la connexion.
            cont.resume(response) { _, value, _ -> value.close() }
        }
    })
}

/** Taille maximale par défaut d'un corps de réponse lu en mémoire (4 Mio). */
const val DEFAULT_MAX_BODY_BYTES: Long = 4L * 1024 * 1024

/**
 * Exécute la requête et renvoie le corps ; lève [RemoteException] si le statut n'est pas 2xx,
 * si la connexion échoue ou si le corps dépasse [maxBodyBytes] (lu au plus `maxBodyBytes + 1`
 * octets, jamais en entier).
 */
suspend fun OkHttpClient.fetchString(request: Request, maxBodyBytes: Long = DEFAULT_MAX_BODY_BYTES): String {
    require(maxBodyBytes >= 0) { "maxBodyBytes doit être positif ou nul" }
    val response = try {
        newCall(request).await()
    } catch (e: RedirectRefusedException) {
        throw RemoteException(e.message ?: "Redirection refusée", e)
    } catch (e: IOException) {
        throw RemoteException("Appareil injoignable (${request.url.host})", e)
    }
    response.use {
        if (!it.isSuccessful) {
            throw HttpStatusException(it.code, "HTTP ${it.code} sur ${request.url.encodedPath}")
        }
        val tooLarge = { RemoteException("Réponse trop volumineuse (plus de $maxBodyBytes octets) de ${request.url.host}") }
        val body = it.body
        // Taille annoncée : refus immédiat, sans rien lire.
        if (body.contentLength() > maxBodyBytes) throw tooLarge()
        // La lecture du corps peut elle aussi échouer (connexion coupée, délai dépassé).
        return try {
            val source = body.source()
            // request() renvoie vrai si au moins maxBodyBytes + 1 octets sont disponibles :
            // le corps est alors trop gros (cas d'un corps sans Content-Length ou mensonger).
            if (source.request(maxBodyBytes + 1)) throw tooLarge()
            val buffer = source.buffer
            val charset = body.contentType()?.charset() ?: Charsets.UTF_8
            // Marque d'ordre d'octets UTF-8 ignorée, comme le fait ResponseBody.string().
            if (charset == Charsets.UTF_8 && buffer.size >= 3 && buffer.rangeEquals(0, UTF8_BOM)) buffer.skip(3)
            buffer.readString(charset)
        } catch (e: IOException) {
            throw RemoteException("Réponse interrompue (${request.url.host})", e)
        }
    }
}

/**
 * Télécharge une image (pochette, affiche) à l'adresse [url], sans cookie ni authentification :
 * `null` si l'adresse n'est pas http(s), si le statut n'est pas 2xx, si le type n'est pas une
 * image, si le corps dépasse [maxBytes] (jamais lu en entier) ou si le réseau échoue.
 */
suspend fun OkHttpClient.fetchImage(url: String, maxBytes: Long = DEFAULT_MAX_BODY_BYTES): ByteArray? {
    val parsed = url.toHttpUrlOrNull() ?: return null
    val request = Request.Builder().url(parsed).header("Accept", "image/*").build()
    val response = try {
        newCall(request).await()
    } catch (_: IOException) {
        return null
    }
    response.use {
        if (!it.isSuccessful) return null
        val body = it.body
        val type = body.contentType()
        if (type != null && type.type != "image") return null
        if (body.contentLength() > maxBytes) return null
        return try {
            val source = body.source()
            if (source.request(maxBytes + 1)) return null
            source.buffer.readByteArray()
        } catch (_: IOException) {
            null
        }
    }
}

private val UTF8_BOM = okio.ByteString.of(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())

class HttpStatusException(val code: Int, message: String) : RemoteException(message)

val EMPTY_BODY: RequestBody = ByteArray(0).toRequestBody(null)
