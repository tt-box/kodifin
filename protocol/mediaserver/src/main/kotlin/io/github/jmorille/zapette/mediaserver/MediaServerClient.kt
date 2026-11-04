package io.github.jmorille.zapette.mediaserver

import io.github.jmorille.zapette.model.EMPTY_BODY
import io.github.jmorille.zapette.model.JSON_MEDIA_TYPE
import io.github.jmorille.zapette.model.RemoteException
import io.github.jmorille.zapette.model.fetchString
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** Variante du serveur : Jellyfin est un fork d'Emby, l'API de contrôle est commune. */
enum class MediaServerFlavor { JELLYFIN, EMBY }

@Serializable
data class AuthResult(
    @SerialName("AccessToken") val accessToken: String,
    @SerialName("User") val user: UserDto,
)

@Serializable
data class UserDto(@SerialName("Id") val id: String, @SerialName("Name") val name: String = "")

@Serializable
data class SessionDto(
    @SerialName("Id") val id: String,
    @SerialName("DeviceId") val deviceId: String? = null,
    @SerialName("DeviceName") val deviceName: String = "",
    @SerialName("Client") val client: String = "",
    @SerialName("SupportsRemoteControl") val supportsRemoteControl: Boolean = false,
    @SerialName("NowPlayingItem") val nowPlaying: NowPlayingDto? = null,
    @SerialName("PlayState") val playState: PlayStateDto? = null,
)

@Serializable
data class NowPlayingDto(
    @SerialName("Name") val name: String = "",
    @SerialName("RunTimeTicks") val runTimeTicks: Long = 0,
)

@Serializable
data class PlayStateDto(
    @SerialName("PositionTicks") val positionTicks: Long = 0,
    @SerialName("IsPaused") val isPaused: Boolean = false,
    @SerialName("IsMuted") val isMuted: Boolean = false,
    @SerialName("VolumeLevel") val volumeLevel: Int? = null,
)

/** 1 tick = 100 ns. */
const val TICKS_PER_MS = 10_000L

/** Client REST commun Jellyfin / Emby (authentification + contrôle de session). */
class MediaServerClient(
    private val http: OkHttpClient,
    baseUrl: String,
    private val flavor: MediaServerFlavor,
    private val deviceId: String,
    accessToken: String? = null,
    private val appVersion: String = "1.0",
) {
    /** Jeton courant ; lu et écrit depuis plusieurs coroutines (commandes et suivi de lecture). */
    @Volatile
    var accessToken: String? = accessToken

    private val base: HttpUrl? = baseUrl.trimEnd('/').toHttpUrlOrNull()

    /** `coerceInputValues` : un `null` reçu pour un champ non nullable prend sa valeur par défaut. */
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }

    /** Retire ce qui casserait la syntaxe `Clé="valeur"` de l'en-tête (guillemets, virgules, contrôles). */
    private fun headerParam(value: String): String = value.filterNot { it == '"' || it == ',' || it.isISOControl() }

    /** En-tête d'autorisation `MediaBrowser …` attendu par les deux serveurs. */
    internal fun authorizationValue(): String = buildString {
        append("MediaBrowser Client=\"Zapette\", Device=\"Android\", DeviceId=\"")
        append(headerParam(deviceId))
        append("\", Version=\"").append(headerParam(appVersion)).append('"')
        accessToken?.let { append(", Token=\"").append(headerParam(it)).append('"') }
    }

    private fun authHeaderName() = when (flavor) {
        MediaServerFlavor.JELLYFIN -> "Authorization"
        MediaServerFlavor.EMBY -> "X-Emby-Authorization"
    }

    /** Construit l'URL en encodant chaque segment : un identifiant ne peut pas sortir de son segment. */
    internal fun url(vararg segments: String, query: Map<String, String> = emptyMap()): HttpUrl {
        val root = base ?: throw RemoteException("Adresse du serveur invalide")
        return root.newBuilder().apply {
            segments.forEach { addPathSegment(it) }
            query.forEach { (k, v) -> addQueryParameter(k, v) }
        }.build()
    }

    private fun request(url: HttpUrl): Request.Builder = Request.Builder()
        .url(url)
        .header(authHeaderName(), authorizationValue())
        .header("Accept", "application/json")
        .apply {
            // Emby lit le jeton dans X-Emby-Token ; Jellyfin le prend dans l'en-tête Authorization.
            val token = accessToken
            if (flavor == MediaServerFlavor.EMBY && token != null) header("X-Emby-Token", headerParam(token))
        }

    private fun <T> decode(serializer: DeserializationStrategy<T>, text: String): T = try {
        json.decodeFromString(serializer, text)
    } catch (e: SerializationException) {
        throw RemoteException("Réponse inattendue du serveur", e)
    } catch (e: IllegalArgumentException) {
        throw RemoteException("Réponse inattendue du serveur", e)
    }

    /** Le mot de passe n'est ni conservé ni repris dans un message d'erreur. */
    suspend fun authenticate(username: String, password: String): AuthResult {
        val body = buildJsonObject { put("Username", username); put("Pw", password) }.toString()
        val text = http.fetchString(
            request(url("Users", "AuthenticateByName")).post(body.toRequestBody(JSON_MEDIA_TYPE)).build(),
        )
        val result = decode(AuthResult.serializer(), text)
        if (result.accessToken.isBlank()) throw RemoteException("Jeton absent de la réponse du serveur")
        accessToken = result.accessToken
        return result
    }

    suspend fun sessions(userId: String? = null): List<SessionDto> {
        val query = if (userId != null) mapOf("ControllableByUserId" to userId) else emptyMap()
        val text = http.fetchString(request(url("Sessions", query = query)).get().build())
        return decode(ListSerializer(SessionDto.serializer()), text)
    }

    /** Commande de lecture : `PlayPause`, `Stop`, `NextTrack`, `Seek`… */
    suspend fun playstate(sessionId: String, command: String, seekPositionTicks: Long? = null) {
        val query = seekPositionTicks?.let { mapOf("seekPositionTicks" to it.coerceAtLeast(0).toString()) } ?: emptyMap()
        http.fetchString(request(url("Sessions", sessionId, "Playing", command, query = query)).post(EMPTY_BODY).build())
    }

    /** Commande générale : `MoveUp`, `Select`, `GoHome`, `VolumeUp`… */
    suspend fun generalCommand(sessionId: String, name: String, arguments: Map<String, String> = emptyMap()) {
        val body = buildJsonObject {
            put("Name", name)
            putJsonObject("Arguments") { arguments.forEach { (k, v) -> put(k, v) } }
        }.toString()
        http.fetchString(request(url("Sessions", sessionId, "Command")).post(body.toRequestBody(JSON_MEDIA_TYPE)).build())
    }

    suspend fun requireToken(): String = accessToken ?: throw RemoteException("Non authentifié sur le serveur")
}
