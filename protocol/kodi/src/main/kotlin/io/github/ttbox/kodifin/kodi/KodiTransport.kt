package io.github.ttbox.kodifin.kodi

import io.github.ttbox.kodifin.model.DEFAULT_MAX_BODY_BYTES
import io.github.ttbox.kodifin.model.JSON_MEDIA_TYPE
import io.github.ttbox.kodifin.model.RemoteError
import io.github.ttbox.kodifin.model.RemoteException
import io.github.ttbox.kodifin.model.await
import io.github.ttbox.kodifin.model.fetchString
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.Credentials
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.io.IOException
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Notification poussée par Kodi (WebSocket uniquement), ex. `Player.OnPlay`. */
data class KodiNotification(val method: String, val params: JsonElement)

/** Erreur JSON-RPC renvoyée par Kodi : [code] et [detail] viennent de l'appareil. */
class KodiRpcException(val code: Int, val detail: String?) :
    RemoteException(RemoteError.DeviceRefused(detail = detail, code = code.toString()))

/** Transport JSON-RPC 2.0. */
interface KodiTransport {
    suspend fun call(method: String, params: JsonObject? = null): JsonElement
    val notifications: Flow<KodiNotification> get() = emptyFlow()

    /** `true` si [notifications] reflète réellement l'état de Kodi (WebSocket). */
    val pushesNotifications: Boolean get() = false
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
            // Un champ `error` mal formé (non objet) reste une erreur, sans faire planter l'analyse.
            val obj = error as? JsonObject
            throw KodiRpcException(
                (obj?.get("code") as? JsonPrimitive)?.intOrNull ?: -1,
                (obj?.get("message") as? JsonPrimitive)?.contentOrNull,
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

    /** Images servies par le même serveur web, avec le même client et les mêmes identifiants. */
    val images: KodiImageFetcher by lazy { KodiImageFetcher(client, baseUrl, username, password) }

    // Adresse validée une fois : un hôte saisi invalide donne une RemoteException, pas un crash.
    private val endpoint by lazy { (baseUrl.trimEnd('/') + "/jsonrpc").toHttpUrlOrNull() }

    override suspend fun call(method: String, params: JsonObject?): JsonElement {
        val url = endpoint ?: throw RemoteException(RemoteError.InvalidAddress(baseUrl))
        val payload = KodiJson.request(ids.getAndIncrement(), method, params)
        val request = Request.Builder()
            .url(url)
            .post(payload.toRequestBody(JSON_MEDIA_TYPE))
            .apply {
                if (!username.isNullOrEmpty()) {
                    // Kodi compare les octets UTF-8 : ISO-8859-1 (défaut d'OkHttp) casserait les
                    // mots de passe accentués.
                    header("Authorization", Credentials.basic(username, password.orEmpty(), StandardCharsets.UTF_8))
                }
            }
            .build()
        // Un refus d'identifiants remonte en HttpStatusException(401), que l'application diagnostique.
        val body = client.fetchString(request)
        val obj = runCatching { KodiJson.json.parseToJsonElement(body).jsonObject }
            .getOrElse { throw RemoteException(RemoteError.UnexpectedResponse("Kodi"), it) }
        return KodiJson.result(obj)
    }
}

/**
 * Téléchargement des images de Kodi par son serveur web : `GET {baseUrl}/image/{clé encodée}`,
 * où la clé est un chemin d'image Kodi (`image://…`, valeurs de `art`, `thumbnail`, `fanart`).
 *
 * - Hôte : toujours [baseUrl] (l'appareil) ; la clé n'est qu'un segment de chemin encodé, elle ne
 *   peut donc pas désigner un autre hôte. Les redirections suivent la politique du [client]
 *   (le client de l'application refuse celles qui changent d'hôte).
 * - Authentification Basic de l'appareil (octets UTF-8, comme pour `/jsonrpc`).
 * - Taille bornée à [maxBytes] (4 Mio par défaut) : au-delà, `null` sans lire le reste.
 * - Kodi ne redimensionne pas via `/image/` (pas de paramètre de largeur garanti) : l'image est
 *   renvoyée telle que mise en cache par Kodi ; le redimensionnement reste à l'affichage.
 * - Échec (clé non `image://`, HTTP non 2xx, réseau, type non image, trop gros) : `null`.
 */
class KodiImageFetcher(
    private val client: OkHttpClient,
    private val baseUrl: String,
    private val username: String? = null,
    private val password: String? = null,
    private val maxBytes: Long = DEFAULT_MAX_BODY_BYTES,
) {
    private val base by lazy { baseUrl.trimEnd('/').toHttpUrlOrNull() }

    /** Octets de l'image désignée par [imageKey], ou `null`. */
    suspend fun fetch(imageKey: String): ByteArray? {
        if (!imageKey.startsWith(IMAGE_SCHEME) || imageKey.length > MAX_KEY_LENGTH) return null
        val root = base ?: return null
        // URLEncoder code l'espace en « + » : « %20 » est décodé sans ambiguïté dans un chemin.
        val segment = URLEncoder.encode(imageKey, StandardCharsets.UTF_8.name()).replace("+", "%20")
        val url = root.newBuilder().addPathSegment("image").addEncodedPathSegment(segment).build()
        if (url.host != root.host || url.port != root.port) return null
        val request = Request.Builder()
            .url(url)
            .get()
            .apply {
                if (!username.isNullOrEmpty()) {
                    header("Authorization", Credentials.basic(username, password.orEmpty(), StandardCharsets.UTF_8))
                }
            }
            .build()
        return try {
            client.newCall(request).await().use { response ->
                if (!response.isSuccessful) return null
                val body = response.body
                val type = body.contentType()
                if (type != null && type.type != "image") return null
                if (body.contentLength() > maxBytes) return null
                val source = body.source()
                // Plus de maxBytes octets disponibles : refus sans lire le corps en entier.
                if (source.request(maxBytes + 1)) return null
                source.buffer.readByteArray().takeIf { it.isNotEmpty() }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: IOException) {
            null
        }
    }

    companion object {
        const val IMAGE_SCHEME = "image://"

        /** Garde-fou contre une clé démesurée (URL de requête). */
        private const val MAX_KEY_LENGTH = 4096
    }
}

/**
 * Transport WebSocket (port TCP 9090 par défaut) : requêtes corrélées par `id`
 * et notifications temps réel.
 *
 * - Délai de réponse : par défaut celui du client OkHttp fourni (dérivé par appareil par l'appelant).
 * - Maintien de connexion : ping WebSocket toutes les [PING_INTERVAL_MS] si le client n'en a pas.
 * - Connexion perdue : les appels en cours échouent aussitôt, puis la connexion est rétablie
 *   automatiquement avec un délai croissant ([reconnectDelaysMs]) ; une notification
 *   [RECONNECTED] est alors émise pour que l'état soit relu. [close] arrête définitivement.
 */
class KodiWebSocketTransport(
    client: OkHttpClient,
    private val url: String,
    private val timeoutMs: Long = defaultTimeoutMs(client),
    private val reconnectDelaysMs: List<Long> = DEFAULT_RECONNECT_DELAYS_MS,
) : KodiTransport {
    private val http: OkHttpClient =
        if (client.pingIntervalMillis > 0) {
            client
        } else {
            client.newBuilder().pingInterval(PING_INTERVAL_MS, TimeUnit.MILLISECONDS).build()
        }

    /** Hôte de [url], pour les erreurs (`null` si l'adresse est illisible). */
    private val host: String? = runCatching { java.net.URI(url).host }.getOrNull()

    /** Intervalle de ping WebSocket effectif (maintien de connexion). */
    internal val pingIntervalMs: Long get() = http.pingIntervalMillis.toLong()

    /** Erreur définitive après [close] ; `null` tant que le transport est utilisable. */
    @Volatile
    private var closedError: RemoteException? = null

    private val ids = AtomicInteger(1)
    private val pending = ConcurrentHashMap<Int, CompletableDeferred<JsonObject>>()
    private val _notifications = MutableSharedFlow<KodiNotification>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val notifications: Flow<KodiNotification> = _notifications.asSharedFlow()
    override val pushesNotifications: Boolean get() = true

    /** Portée des tentatives de reconnexion, annulée par [close]. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Tentatives de reconnexion consécutives sans succès (remis à zéro à l'ouverture). */
    private val attempts = AtomicInteger(0)

    /** Une connexion WebSocket ; remplacée à chaque reconnexion. */
    private inner class Connection(val reconnect: Boolean) {
        val opened = CompletableDeferred<Unit>()

        @Volatile
        var error: RemoteException? = null

        val socket: WebSocket = http.newWebSocket(
            Request.Builder().url(url).build(),
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    attempts.set(0)
                    opened.complete(Unit)
                    if (reconnect) _notifications.tryEmit(KodiNotification(RECONNECTED, JsonNull))
                }

                override fun onMessage(webSocket: WebSocket, text: String) = handle(text)

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) =
                    lost(RemoteException(RemoteError.Interrupted(host), t))

                // Fermeture demandée par Kodi : on répond pour terminer la poignée de main
                // et on libère tout de suite les appels en attente.
                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    lost(RemoteException(RemoteError.Interrupted(host)))
                    webSocket.close(1000, null)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) =
                    lost(RemoteException(RemoteError.Interrupted(host)))
            },
        )

        /** Connexion perdue : échec des appels en attente puis reconnexion programmée (une seule fois). */
        fun lost(e: RemoteException) {
            val first = synchronized(this) {
                if (error != null) {
                    false
                } else {
                    error = e
                    true
                }
            }
            opened.completeExceptionally(e)
            failPending(e)
            if (first) scheduleReconnect(this)
        }
    }

    @Volatile
    private var connection: Connection = Connection(reconnect = false)

    private fun scheduleReconnect(failed: Connection) {
        if (closedError != null || reconnectDelaysMs.isEmpty()) return
        val attempt = attempts.getAndIncrement()
        val wait = reconnectDelaysMs[attempt.coerceAtMost(reconnectDelaysMs.lastIndex)]
        scope.launch {
            delay(wait)
            synchronized(this@KodiWebSocketTransport) {
                if (closedError == null && connection === failed) connection = Connection(reconnect = true)
            }
        }
    }

    internal fun handle(text: String) {
        val obj = runCatching { KodiJson.json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return
        val id = (obj["id"] as? JsonPrimitive)?.intOrNull
        if (id != null) {
            pending.remove(id)?.complete(obj)
            return
        }
        val method = (obj["method"] as? JsonPrimitive)?.contentOrNull ?: return
        _notifications.tryEmit(KodiNotification(method, obj["params"] ?: JsonNull))
    }

    private fun failPending(error: RemoteException) {
        // Retrait un par un : une entrée ajoutée pendant le parcours n'est pas perdue sans réponse.
        pending.keys.forEach { id -> pending.remove(id)?.completeExceptionally(error) }
    }

    override suspend fun call(method: String, params: JsonObject?): JsonElement {
        val id = ids.getAndIncrement()
        val deferred = CompletableDeferred<JsonObject>()
        pending[id] = deferred
        try {
            val conn = connection
            // Revérifié après l'enregistrement : couvre une fermeture ou une perte concurrente.
            closedError?.let { throw it }
            conn.error?.let { throw it }
            // withTimeoutOrNull plutôt que withTimeout : une TimeoutCancellationException
            // serait prise pour une annulation et silencieusement ignorée par l'appelant.
            val response = withTimeoutOrNull(timeoutMs) {
                conn.opened.await()
                if (!conn.socket.send(KodiJson.request(id, method, params))) {
                    throw conn.error ?: closedError ?: RemoteException(RemoteError.Interrupted(host))
                }
                deferred.await()
            } ?: throw RemoteException(RemoteError.Timeout)
            return KodiJson.result(response)
        } finally {
            pending.remove(id)
        }
    }

    override fun close() {
        val error = RemoteException(RemoteError.Interrupted(host))
        synchronized(this) { if (closedError == null) closedError = error }
        scope.cancel()
        val conn = connection
        conn.opened.completeExceptionally(error)
        failPending(error)
        // Fermeture propre ; OkHttp coupe de lui-même si Kodi ne répond pas à la trame de fermeture.
        conn.socket.close(1000, null)
    }

    companion object {
        /** Pseudo-notification émise après une reconnexion réussie (l'état a pu changer entre-temps). */
        const val RECONNECTED = "Kodifin.OnReconnected"

        const val PING_INTERVAL_MS = 30_000L
        val DEFAULT_RECONNECT_DELAYS_MS = listOf(1_000L, 2_000L, 5_000L, 10_000L, 30_000L)

        /** Délai de lecture du client (réglage par appareil), sinon délai global, sinon 10 s. */
        fun defaultTimeoutMs(client: OkHttpClient): Long =
            client.readTimeoutMillis.takeIf { it > 0 }?.toLong()
                ?: client.callTimeoutMillis.takeIf { it > 0 }?.toLong()
                ?: 10_000L
    }
}
