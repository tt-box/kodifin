package io.github.jmorille.zapette.model

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

/** Exécute la requête et renvoie le corps ; lève [RemoteException] si le statut n'est pas 2xx. */
suspend fun OkHttpClient.fetchString(request: Request): String {
    val response = try {
        newCall(request).await()
    } catch (e: IOException) {
        throw RemoteException("Appareil injoignable (${request.url.host})", e)
    }
    response.use {
        if (!it.isSuccessful) {
            throw HttpStatusException(it.code, "HTTP ${it.code} sur ${request.url.encodedPath}")
        }
        // La lecture du corps peut elle aussi échouer (connexion coupée, délai dépassé).
        return try {
            it.body.string()
        } catch (e: IOException) {
            throw RemoteException("Réponse interrompue (${request.url.host})", e)
        }
    }
}

class HttpStatusException(val code: Int, message: String) : RemoteException(message)

val EMPTY_BODY: RequestBody = ByteArray(0).toRequestBody(null)
