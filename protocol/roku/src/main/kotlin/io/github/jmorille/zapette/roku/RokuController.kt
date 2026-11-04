package io.github.jmorille.zapette.roku

import io.github.jmorille.zapette.model.Capability
import io.github.jmorille.zapette.model.Device
import io.github.jmorille.zapette.model.DeviceKind
import io.github.jmorille.zapette.model.EMPTY_BODY
import io.github.jmorille.zapette.model.RemoteController
import io.github.jmorille.zapette.model.RemoteKey
import io.github.jmorille.zapette.model.fetchString
import io.github.jmorille.zapette.model.requireKey
import io.github.jmorille.zapette.upnp.Ssdp
import io.github.jmorille.zapette.upnp.SsdpResponse
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Roku External Control Protocol (ECP), HTTP sur le port 8060.
 * Référence : https://developer.roku.com/docs/developer-program/dev-tools/external-control-api.md
 */
class RokuController(
    override val device: Device,
    private val http: OkHttpClient,
) : RemoteController {

    override val capabilities = setOf(
        Capability.NAVIGATION, Capability.PLAYBACK, Capability.VOLUME, Capability.PLAY_URL, Capability.TEXT_INPUT,
    )
    override val supportedKeys = KEYS.keys

    private val base: HttpUrl = device.baseUrl.toHttpUrl()

    private suspend fun post(segments: List<String>, query: Map<String, String> = emptyMap()) {
        val url = base.newBuilder().apply {
            segments.forEach { addPathSegment(it) }
            query.forEach { (k, v) -> addQueryParameter(k, v) }
        }.build()
        http.fetchString(Request.Builder().url(url).post(EMPTY_BODY).build())
    }

    override suspend fun connect() {
        http.fetchString(Request.Builder().url(base.resolve("/query/device-info")!!).build())
    }

    override suspend fun send(key: RemoteKey) {
        requireKey(key)
        post(listOf("keypress", KEYS.getValue(key)))
    }

    /** Chaque caractère est envoyé via `keypress/Lit_<car>` (encodé en URL). */
    override suspend fun sendText(text: String) {
        text.forEach { post(listOf("keypress", "Lit_$it")) }
    }

    /** Lecture via la chaîne système « Play on Roku » (id 15985). */
    override suspend fun playUrl(url: String, title: String?) {
        val query = buildMap {
            put("t", if (isAudio(url)) "a" else "v")
            put("u", url)
            title?.let { put("videoName", it) }
            put("videoFormat", url.substringAfterLast('.', "mp4").substringBefore('?').lowercase())
        }
        post(listOf("input", PLAY_ON_ROKU), query)
    }

    suspend fun launch(appId: String) = post(listOf("launch", appId))

    companion object {
        const val PLAY_ON_ROKU = "15985"

        val KEYS: Map<RemoteKey, String> = mapOf(
            RemoteKey.UP to "Up",
            RemoteKey.DOWN to "Down",
            RemoteKey.LEFT to "Left",
            RemoteKey.RIGHT to "Right",
            RemoteKey.SELECT to "Select",
            RemoteKey.BACK to "Back",
            RemoteKey.HOME to "Home",
            RemoteKey.INFO to "Info",
            RemoteKey.MENU to "Info",
            RemoteKey.PLAY_PAUSE to "Play",
            RemoteKey.REWIND to "Rev",
            RemoteKey.FAST_FORWARD to "Fwd",
            RemoteKey.PREVIOUS to "InstantReplay",
            RemoteKey.VOLUME_UP to "VolumeUp",
            RemoteKey.VOLUME_DOWN to "VolumeDown",
            RemoteKey.MUTE to "VolumeMute",
        )

        private fun isAudio(url: String) =
            url.substringBefore('?').substringAfterLast('.').lowercase() in setOf("mp3", "aac", "m4a", "flac", "wma")

        /** Construit un appareil Roku à partir d'une réponse SSDP `roku:ecp`. */
        fun deviceFrom(response: SsdpResponse): Device? {
            if (response.searchTarget != Ssdp.ST_ROKU) return null
            val url = response.location?.toHttpUrl() ?: return null
            val serial = response.usn?.substringAfterLast(':')?.ifBlank { null } ?: url.host
            return Device("ROKU:$serial", "Roku ${url.host}", DeviceKind.ROKU, url.host, url.port)
        }
    }
}
