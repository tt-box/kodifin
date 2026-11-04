package io.github.jmorille.zapette.mediaserver

import io.github.jmorille.zapette.model.EMPTY_BODY
import io.github.jmorille.zapette.model.HttpStatusException
import io.github.jmorille.zapette.model.JSON_MEDIA_TYPE
import io.github.jmorille.zapette.model.RemoteException
import io.github.jmorille.zapette.model.fetchString
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
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
) {
    /** Le jeton n'apparaît jamais dans un journal ou un message d'erreur. */
    override fun toString() = "AuthResult(accessToken=***, user=$user)"
}

/**
 * Utilisateur listé par `GET /Users/Public` (Jellyfin et Emby) : seuls les comptes que
 * l'administrateur laisse visibles sur l'écran de connexion y figurent.
 */
@Serializable
data class PublicUserDto(
    @SerialName("Id") val id: String = "",
    @SerialName("Name") val name: String = "",
    @SerialName("HasPassword") val hasPassword: Boolean = false,
    @SerialName("HasConfiguredPassword") val hasConfiguredPassword: Boolean = false,
) {
    /** Un mot de passe est à saisir : l'un ou l'autre indicateur suffit (selon serveur et version). */
    val requiresPassword: Boolean get() = hasPassword || hasConfiguredPassword
}

/**
 * Demande Quick Connect (Jellyfin) : [code] est saisi par l'utilisateur dans l'interface
 * Jellyfin d'un appareil déjà connecté ; [secret] permet ensuite d'obtenir le jeton.
 */
@Serializable
data class QuickConnectDto(
    @SerialName("Secret") val secret: String = "",
    @SerialName("Code") val code: String = "",
    @SerialName("Authenticated") val authenticated: Boolean = false,
) {
    /** Le secret vaut un jeton tant que la demande est valide : jamais affiché. */
    override fun toString() = "QuickConnectDto(code=$code, authenticated=$authenticated, secret=***)"
}

@Serializable
data class PublicInfoDto(@SerialName("Id") val id: String = "")

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

    @Volatile
    private var base: HttpUrl? = baseUrl.trimEnd('/').toHttpUrlOrNull()

    /** URL racine courante de l'API (chemin de base compris). */
    val baseUrl: String? get() = base?.toString()

    /** Change l'URL racine (chemin de base détecté, ex. `http://hôte:8096/jellyfin`). */
    internal fun rebase(baseUrl: String) {
        base = baseUrl.trimEnd('/').toHttpUrlOrNull() ?: throw RemoteException("Adresse du serveur invalide")
    }

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

    /**
     * Comptes visibles sur l'écran de connexion (`GET /Users/Public`), sans authentification.
     * Les entrées sans nom sont ignorées.
     */
    suspend fun publicUsers(): List<PublicUserDto> {
        val text = http.fetchString(request(url("Users", "Public")).get().build())
        return decode(ListSerializer(PublicUserDto.serializer()), text).filter { it.name.isNotBlank() }
    }

    /** `true` si Quick Connect est activé sur le serveur Jellyfin (`GET /QuickConnect/Enabled`). */
    suspend fun quickConnectEnabled(): Boolean {
        val text = http.fetchString(request(url("QuickConnect", "Enabled")).get().build())
        return decode(Boolean.serializer(), text)
    }

    /**
     * Ouvre une demande Quick Connect (`POST /QuickConnect/Initiate`) ; les serveurs
     * Jellyfin 10.8 n'acceptent que `GET` (405 sur `POST`), d'où le second essai.
     */
    suspend fun quickConnectInitiate(): QuickConnectDto {
        val target = url("QuickConnect", "Initiate")
        val text = try {
            http.fetchString(request(target).post(EMPTY_BODY).build())
        } catch (e: HttpStatusException) {
            if (e.code != 405) throw e
            http.fetchString(request(target).get().build())
        }
        val result = decode(QuickConnectDto.serializer(), text)
        if (result.secret.isBlank() || result.code.isBlank()) throw RemoteException("Réponse Quick Connect incomplète")
        return result
    }

    /** État de la demande (`GET /QuickConnect/Connect?secret=…`) ; 404 si elle a expiré. */
    suspend fun quickConnectState(secret: String): QuickConnectDto {
        val text = http.fetchString(request(url("QuickConnect", "Connect", query = mapOf("secret" to secret))).get().build())
        return decode(QuickConnectDto.serializer(), text)
    }

    /** Échange une demande approuvée contre un jeton (`POST /Users/AuthenticateWithQuickConnect`). */
    suspend fun authenticateWithQuickConnect(secret: String): AuthResult {
        val body = buildJsonObject { put("Secret", secret) }.toString()
        val text = http.fetchString(
            request(url("Users", "AuthenticateWithQuickConnect")).post(body.toRequestBody(JSON_MEDIA_TYPE)).build(),
        )
        val result = decode(AuthResult.serializer(), text)
        if (result.accessToken.isBlank()) throw RemoteException("Jeton absent de la réponse du serveur")
        accessToken = result.accessToken
        return result
    }

    /**
     * Recale le client sur l'URL qui expose l'API : `""` si l'URL actuelle convient, sinon le
     * premier chemin de [candidates] qui répond (client recalé dessus), ou `null` si aucun.
     */
    suspend fun locateBasePath(candidates: List<String> = DEFAULT_BASE_PATHS): String? {
        val root = baseUrl ?: return null
        if (isServer()) return ""
        for (path in candidates) {
            val candidate = root.trimEnd('/') + path
            if (isServer(candidate)) {
                rebase(candidate)
                return path
            }
        }
        return null
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

    /**
     * `true` si [rootUrl] (par défaut l'URL courante) expose l'API publique du serveur :
     * `GET …/System/Info/Public` renvoie un objet JSON portant un `Id`. Une redirection vers
     * l'interface web (chemin de base mal réglé) ou une page HTML donne `false`.
     */
    suspend fun isServer(rootUrl: String? = null): Boolean {
        val root = (rootUrl?.trimEnd('/')?.toHttpUrlOrNull() ?: base) ?: return false
        val url = root.newBuilder().addPathSegments("System/Info/Public").build()
        return try {
            val text = http.fetchString(Request.Builder().url(url).header("Accept", "application/json").get().build())
            json.decodeFromString(PublicInfoDto.serializer(), text).id.isNotBlank()
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }
    }

    suspend fun requireToken(): String = accessToken ?: throw RemoteException("Non authentifié sur le serveur")

    override fun toString() = "MediaServerClient(baseUrl=$baseUrl, flavor=$flavor, token=${if (accessToken == null) "null" else "***"})"

    companion object {
        /** Chemins de base usuels quand le serveur est publié derrière un proxy (« URL de base »). */
        val DEFAULT_BASE_PATHS = listOf("/jellyfin", "/emby")
    }
}
