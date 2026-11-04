package io.github.jmorille.zapette.kodi

import io.github.jmorille.zapette.model.JSON_MEDIA_TYPE
import io.github.jmorille.zapette.model.RemoteException
import io.github.jmorille.zapette.model.fetchString
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** Notification poussée par Kodi (WebSocket uniquement), ex. `Player.OnPlay`. */
data class KodiNotification(val method: String, val params: JsonElement)

class KodiRpcException(val code: Int, message: String) : RemoteException("Kodi $code : $message")

/** Transport JSON-RPC 2.0. */
interface KodiTransport {
    suspend fun call(method: String, params: JsonObject? = null): JsonElement
    val notifications: Flow<KodiNotification> get() = emptyFlow()
    fun close() {}
}

internal object KodiJson {
    val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    fun request(id: Int, method: String, params: JsonObject?): String = buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", id)
        put("method", method)
        if (params != null) put("params", params)
    }.toString()

    /** Extrait `result` ou lève l'erreur JSON-RPC. */
    fun result(body: JsonObject): JsonElement {
        val error = body["error"]
        if (error != null && error != JsonNull) {
            val obj = error.jsonObject
            throw KodiRpcException(
                obj["code"]?.jsonPrimitive?.intOrNull ?: -1,
                obj["message"]?.jsonPrimitive?.contentOrNull ?: "erreur inconnue",
            )
        }
        return body["result"] ?: JsonNull
    }
}

/** Transport HTTP : POST sur `/jsonrpc`, authentification Basic optionnelle. */
class KodiHttpTransport(
    private val client: OkHttpClient,
    private val baseUrl: String,
    private val username: String? = null,
    private val password: String? = null,
) : KodiTransport {
    private val ids = AtomicInteger(1)

    override suspend fun call(method: String, params: JsonObject?): JsonElement {
        val payload = KodiJson.request(ids.getAndIncrement(), method, params)
        val request = Request.Builder()
            .url(baseUrl.trimEnd('/') + "/jsonrpc")
            .post(payload.toRequestBody(JSON_MEDIA_TYPE))
            .apply {
                if (!username.isNullOrEmpty()) header("Authorization", Credentials.basic(username, password.orEmpty()))
            }
            .build()
        val body = client.fetchString(request)
        val obj = runCatching { KodiJson.json.parseToJsonElement(body).jsonObject }
            .getOrElse { throw RemoteException("Réponse Kodi invalide", it) }
        return KodiJson.result(obj)
    }
}

/**
 * Transport WebSocket (port TCP 9090 par défaut) : requêtes corrélées par `id`
 * et notifications temps réel.
 */
class KodiWebSocketTransport(
    client: OkHttpClient,
    url: String,
    private val timeoutMs: Long = 10_000,
) : KodiTransport {
    private val ids = AtomicInteger(1)
    private val pending = ConcurrentHashMap<Int, CompletableDeferred<JsonObject>>()
    private val _notifications = MutableSharedFlow<KodiNotification>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val notifications: Flow<KodiNotification> = _notifications.asSharedFlow()

    private val opened = CompletableDeferred<Unit>()

    private val socket: WebSocket = client.newWebSocket(
        Request.Builder().url(url).build(),
        object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                opened.complete(Unit)
            }

            override fun onMessage(webSocket: WebSocket, text: String) = handle(text)

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) =
                failAll(RemoteException("WebSocket Kodi fermée", t))

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) =
                failAll(RemoteException("WebSocket Kodi fermée ($code)"))
        },
    )

    internal fun handle(text: String) {
        val obj = runCatching { KodiJson.json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return
        val id = obj["id"]?.jsonPrimitive?.intOrNull
        if (id != null) {
            pending.remove(id)?.complete(obj)
            return
        }
        val method = obj["method"]?.jsonPrimitive?.contentOrNull ?: return
        _notifications.tryEmit(KodiNotification(method, obj["params"] ?: JsonNull))
    }

    private fun failAll(error: Throwable) {
        opened.completeExceptionally(error)
        pending.values.forEach { it.completeExceptionally(error) }
        pending.clear()
    }

    override suspend fun call(method: String, params: JsonObject?): JsonElement {
        val id = ids.getAndIncrement()
        val deferred = CompletableDeferred<JsonObject>()
        pending[id] = deferred
        try {
            return withTimeout(timeoutMs) {
                opened.await()
                if (!socket.send(KodiJson.request(id, method, params))) {
                    throw RemoteException("Envoi WebSocket impossible")
                }
                KodiJson.result(deferred.await())
            }
        } finally {
            pending.remove(id)
        }
    }

    override fun close() {
        socket.close(1000, null)
    }
}
