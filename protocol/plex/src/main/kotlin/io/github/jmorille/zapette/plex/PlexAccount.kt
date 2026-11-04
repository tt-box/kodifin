package io.github.jmorille.zapette.plex

import io.github.jmorille.zapette.model.EMPTY_BODY
import io.github.jmorille.zapette.model.fetchString
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
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
 */
class PlexAccount(
    private val http: OkHttpClient,
    private val identity: PlexIdentity,
    private val apiBase: String = "https://plex.tv",
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun createPin(): PlexPin {
        val text = http.fetchString(identity.apply(Request.Builder().url("$apiBase/api/v2/pins")).post(EMPTY_BODY).build())
        return json.decodeFromString(PlexPin.serializer(), text)
    }

    /** Renvoie le jeton une fois le code validé par l'utilisateur, sinon `null`. */
    suspend fun checkPin(pin: PlexPin): String? {
        val text = http.fetchString(identity.apply(Request.Builder().url("$apiBase/api/v2/pins/${pin.id}")).get().build())
        return json.decodeFromString(PlexPin.serializer(), text).authToken?.ifBlank { null }
    }

    suspend fun resources(token: String): List<PlexResource> {
        val url = "$apiBase/api/v2/resources".toHttpUrl().newBuilder()
            .addQueryParameter("includeHttps", "1")
            .build()
        val text = http.fetchString(
            identity.apply(Request.Builder().url(url)).header("X-Plex-Token", token).get().build(),
        )
        return json.decodeFromString(ListSerializer(PlexResource.serializer()), text)
    }
}
