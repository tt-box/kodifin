package io.github.jmorille.zapette.mediaserver

import io.github.jmorille.zapette.model.EMPTY_BODY
import io.github.jmorille.zapette.model.JSON_MEDIA_TYPE
import io.github.jmorille.zapette.model.RemoteException
import io.github.jmorille.zapette.model.fetchString
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl
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
    var accessToken: String? = null,
    private val appVersion: String = "1.0",
) {
    private val base = baseUrl.trimEnd('/')
    private val json = Json { ignoreUnknownKeys = true }

    /** En-tête d'autorisation `MediaBrowser …` attendu par les deux serveurs. */
    internal fun authorizationValue(): String = buildString {
        append("MediaBrowser Client=\"Zapette\", Device=\"Android\", DeviceId=\"")
        append(deviceId.replace("\"", ""))
        append("\", Version=\"").append(appVersion).append('"')
        accessToken?.let { append(", Token=\"").append(it).append('"') }
    }

    private fun authHeaderName() = when (flavor) {
        MediaServerFlavor.JELLYFIN -> "Authorization"
        MediaServerFlavor.EMBY -> "X-Emby-Authorization"
    }

    private fun request(path: String) = Request.Builder()
        .url("$base$path")
        .header(authHeaderName(), authorizationValue())
        .header("Accept", "application/json")

    suspend fun authenticate(username: String, password: String): AuthResult {
        val body = buildJsonObject { put("Username", username); put("Pw", password) }.toString()
        val text = http.fetchString(request("/Users/AuthenticateByName").post(body.toRequestBody(JSON_MEDIA_TYPE)).build())
        val result = json.decodeFromString(AuthResult.serializer(), text)
        accessToken = result.accessToken
        return result
    }

    suspend fun sessions(userId: String? = null): List<SessionDto> {
        val url = "$base/Sessions".toHttpUrl().newBuilder().apply {
            if (userId != null) addQueryParameter("ControllableByUserId", userId)
        }.build()
        val text = http.fetchString(request("").url(url).get().build())
        return json.decodeFromString(kotlinx.serialization.builtins.ListSerializer(SessionDto.serializer()), text)
    }

    /** Commande de lecture : `PlayPause`, `Stop`, `NextTrack`, `Seek`… */
    suspend fun playstate(sessionId: String, command: String, seekPositionTicks: Long? = null) {
        val path = "/Sessions/$sessionId/Playing/$command" +
            (seekPositionTicks?.let { "?seekPositionTicks=$it" } ?: "")
        http.fetchString(request(path).post(EMPTY_BODY).build())
    }

    /** Commande générale : `MoveUp`, `Select`, `GoHome`, `VolumeUp`… */
    suspend fun generalCommand(sessionId: String, name: String, arguments: Map<String, String> = emptyMap()) {
        val body = buildJsonObject {
            put("Name", name)
            putJsonObject("Arguments") { arguments.forEach { (k, v) -> put(k, v) } }
        }.toString()
        http.fetchString(request("/Sessions/$sessionId/Command").post(body.toRequestBody(JSON_MEDIA_TYPE)).build())
    }

    suspend fun requireToken(): String = accessToken ?: throw RemoteException("Non authentifié sur le serveur")
}
