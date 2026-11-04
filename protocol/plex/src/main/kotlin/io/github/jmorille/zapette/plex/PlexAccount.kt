package io.github.jmorille.zapette.plex

import io.github.jmorille.zapette.model.EMPTY_BODY
import io.github.jmorille.zapette.model.HttpStatusException
import io.github.jmorille.zapette.model.RemoteException
import io.github.jmorille.zapette.model.fetchString
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

@Serializable
data class PlexPin(val id: Long, val code: String, val authToken: String? = null) {
    override fun toString() = "PlexPin(id=$id, code=$code, authToken=${if (authToken == null) "null" else "***"})"
}

@Serializable
data class PlexConnection(val uri: String, val local: Boolean = false, val relay: Boolean = false)

@Serializable
data class PlexResource(
    val name: String,
    val clientIdentifier: String,
    val provides: String = "",
    val product: String = "",
    val accessToken: String? = null,
    val connections: List<PlexConnection> = emptyList(),
) {
    val isPlayer: Boolean get() = provides.split(',').any { it.trim() == "player" }

    override fun toString() =
        "PlexResource(name=$name, clientIdentifier=$clientIdentifier, provides=$provides, product=$product, " +
            "accessToken=${if (accessToken == null) "null" else "***"}, connections=$connections)"
}

/** Membre d'un foyer Plex (« Plex Home »), tel que listé par `GET /api/v2/home/users`. */
@Serializable
data class PlexHomeUser(
    val id: Long = 0,
    val uuid: String = "",
    val title: String = "",
    val username: String = "",
    /** Profil protégé par un code PIN à quatre chiffres. */
    @SerialName("protected") val isProtected: Boolean = false,
    val admin: Boolean = false,
    val restricted: Boolean = false,
) {
    val displayName: String get() = title.ifBlank { username }.ifBlank { "Utilisateur $id" }
}

@Serializable
private data class PlexHomeUsersDto(val users: List<PlexHomeUser> = emptyList())

@Serializable
private data class PlexSwitchDto(val authToken: String? = null, val authenticationToken: String? = null)

/** Code PIN du profil Plex Home refusé par plex.tv. */
class PlexPinRefusedException : RemoteException("Code PIN refusé")

/** En-têtes d'identification exigés par toutes les API Plex. */
data class PlexIdentity(val clientIdentifier: String, val product: String = "Zapette", val version: String = "1.0") {
    fun apply(builder: Request.Builder): Request.Builder = builder
        .header("X-Plex-Client-Identifier", clientIdentifier)
        .header("X-Plex-Product", product)
        .header("X-Plex-Version", version)
        .header("X-Plex-Platform", "Android")
        .header("Accept", "application/json")
}

/**
 * Compte plex.tv par code de liaison : l'utilisateur saisit le code sur
 * https://plex.tv/link — l'application ne manipule jamais le mot de passe.
 *
 * Le jeton du compte n'est envoyé qu'à [apiBase], qui doit être en HTTPS
 * (seule la boucle locale est tolérée en HTTP, pour les tests).
 */
class PlexAccount(
    private val http: OkHttpClient,
    private val identity: PlexIdentity,
    apiBase: String = "https://plex.tv",
) {
    private val base: HttpUrl = apiBase.trimEnd('/').toHttpUrl()

    init {
        require(base.isHttps || base.host == "localhost" || base.host.startsWith("127.") || base.host == "::1") {
            "L'API plex.tv doit être jointe en HTTPS"
        }
    }

    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }

    private fun url(vararg segments: String): HttpUrl.Builder =
        base.newBuilder().apply { segments.forEach { addPathSegment(it) } }

    private fun <T> decode(serializer: DeserializationStrategy<T>, text: String): T = try {
        json.decodeFromString(serializer, text)
    } catch (e: SerializationException) {
        throw RemoteException("Réponse inattendue de plex.tv", e)
    } catch (e: IllegalArgumentException) {
        throw RemoteException("Réponse inattendue de plex.tv", e)
    }

    suspend fun createPin(): PlexPin {
        val request = identity.apply(Request.Builder().url(url("api", "v2", "pins").build())).post(EMPTY_BODY).build()
        return decode(PlexPin.serializer(), http.fetchString(request))
    }

    /** Renvoie le jeton une fois le code validé par l'utilisateur, sinon `null`. */
    suspend fun checkPin(pin: PlexPin): String? {
        val request = identity.apply(Request.Builder().url(url("api", "v2", "pins", pin.id.toString()).build())).get().build()
        return decode(PlexPin.serializer(), http.fetchString(request)).authToken?.ifBlank { null }
    }

    /**
     * Membres du foyer Plex du compte [token] (`GET /api/v2/home/users`). Liste vide si le compte
     * n'appartient à aucun foyer. Les entrées sans identifiant sont ignorées.
     */
    suspend fun homeUsers(token: String): List<PlexHomeUser> {
        val request = identity.apply(Request.Builder().url(url("api", "v2", "home", "users").build()))
            .header("X-Plex-Token", token).get().build()
        val text = http.fetchString(request)
        val element = decode(JsonElement.serializer(), text)
        val users = if (element is JsonArray) {
            decode(ListSerializer(PlexHomeUser.serializer()), text)
        } else {
            decode(PlexHomeUsersDto.serializer(), text).users
        }
        return users.filter { it.uuid.isNotBlank() }
    }

    /**
     * Bascule sur le profil [uuid] (`POST /api/v2/home/users/{uuid}/switch`) et renvoie le jeton
     * propre à ce profil. [pin] n'est transmis que pour un profil protégé ; un PIN refusé lève
     * [PlexPinRefusedException]. Le PIN n'est ni conservé ni repris dans un message.
     */
    suspend fun switchHomeUser(token: String, uuid: String, pin: String? = null): String {
        val target = url("api", "v2", "home", "users", uuid, "switch")
            .apply { pin?.takeIf { it.isNotEmpty() }?.let { addQueryParameter("pin", it) } }
            .build()
        val request = identity.apply(Request.Builder().url(target)).header("X-Plex-Token", token).post(EMPTY_BODY).build()
        val text = try {
            http.fetchString(request)
        } catch (e: HttpStatusException) {
            if (e.code == 401 || e.code == 403) throw PlexPinRefusedException() else throw e
        }
        val result = decode(PlexSwitchDto.serializer(), text)
        return (result.authToken ?: result.authenticationToken)?.ifBlank { null }
            ?: throw RemoteException("Jeton du profil absent de la réponse de plex.tv")
    }

    suspend fun resources(token: String): List<PlexResource> {
        val target = url("api", "v2", "resources").addQueryParameter("includeHttps", "1").build()
        val request = identity.apply(Request.Builder().url(target)).header("X-Plex-Token", token).get().build()
        return decode(ListSerializer(PlexResource.serializer()), http.fetchString(request))
    }
}
