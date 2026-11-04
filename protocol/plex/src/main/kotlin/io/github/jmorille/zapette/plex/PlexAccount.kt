package io.github.jmorille.zapette.plex

import io.github.jmorille.zapette.model.EMPTY_BODY
import io.github.jmorille.zapette.model.RemoteException
import io.github.jmorille.zapette.model.fetchString
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

@Serializable
data class PlexPin(val id: Long, val code: String, val authToken: String? = null)

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
}

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

    suspend fun resources(token: String): List<PlexResource> {
        val target = url("api", "v2", "resources").addQueryParameter("includeHttps", "1").build()
        val request = identity.apply(Request.Builder().url(target)).header("X-Plex-Token", token).get().build()
        return decode(ListSerializer(PlexResource.serializer()), http.fetchString(request))
    }
}
