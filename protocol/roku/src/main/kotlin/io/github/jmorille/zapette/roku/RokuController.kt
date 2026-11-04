package io.github.jmorille.zapette.roku

import io.github.jmorille.zapette.model.Capability
import io.github.jmorille.zapette.model.Device
import io.github.jmorille.zapette.model.DeviceKind
import io.github.jmorille.zapette.model.EMPTY_BODY
import io.github.jmorille.zapette.model.MediaTypes
import io.github.jmorille.zapette.model.RemoteController
import io.github.jmorille.zapette.model.RemoteKey
import io.github.jmorille.zapette.model.fetchString
import io.github.jmorille.zapette.model.requireKey
import io.github.jmorille.zapette.upnp.Ssdp
import io.github.jmorille.zapette.upnp.SsdpResponse
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
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

    /** [segments] sont déjà encodés (voir [encodeSegment]) : aucun « / », « . » ou « .. » brut. */
    private suspend fun post(segments: List<String>, query: Map<String, String> = emptyMap()) {
        val url = base.newBuilder().apply {
            segments.forEach { addEncodedPathSegment(it) }
            query.forEach { (k, v) -> addQueryParameter(k, v) }
        }.build()
        http.fetchString(Request.Builder().url(url).post(EMPTY_BODY).build())
    }

    override suspend fun connect() {
        val url = base.newBuilder().addPathSegment("query").addPathSegment("device-info").build()
        http.fetchString(Request.Builder().url(url).build())
    }

    override suspend fun send(key: RemoteKey) {
        requireKey(key)
        post(listOf("keypress", KEYS.getValue(key)))
    }

    /**
     * Chaque caractère (point de code Unicode, pas unité UTF-16) est envoyé via
     * `keypress/Lit_<car>`, encodé en UTF-8 puis en pourcentage.
     */
    override suspend fun sendText(text: String) {
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            i += Character.charCount(cp)
            post(listOf("keypress", "Lit_" + encodeSegment(String(Character.toChars(cp)))))
        }
    }

    /** Lecture via la chaîne système « Play on Roku » (id 15985). */
    override suspend fun playUrl(url: String, title: String?) {
        val ext = extension(url)
        val query = buildMap {
            put("t", if (MediaTypes.isAudio(MediaTypes.forExtension(ext))) "a" else "v")
            put("u", url)
            title?.let { put("videoName", it) }
            VIDEO_FORMATS[ext]?.let { put("videoFormat", it) }
        }
        post(listOf("input", PLAY_ON_ROKU), query)
    }

    /** Lance une chaîne par son identifiant (numérique, ou « dev » pour la chaîne en développement). */
    suspend fun launch(appId: String) {
        require(APP_ID.matches(appId)) { "Identifiant de chaîne Roku invalide : $appId" }
        post(listOf("launch", appId))
    }

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

        private val APP_ID = Regex("[A-Za-z0-9_]{1,64}")

        /** Extension du chemin → valeur `videoFormat` attendue par « Play on Roku ». */
        private val VIDEO_FORMATS = mapOf(
            "mp4" to "mp4", "m4v" to "mp4", "mov" to "mp4", "mkv" to "mkv",
            "m3u8" to "hls", "mpd" to "dash", "ism" to "ism",
        )

        /** Extension du dernier segment de chemin de l'URL (hors requête et fragment), en minuscules. */
        internal fun extension(url: String): String = MediaTypes.extension(url).orEmpty()

        /** Encodage en pourcentage (UTF-8) de tout sauf les caractères non réservés RFC 3986. */
        internal fun encodeSegment(value: String): String = buildString {
            value.toByteArray(Charsets.UTF_8).forEach { b ->
                val c = (b.toInt() and 0xFF)
                if (c < 0x80 && (c.toChar().isLetterOrDigit() || c.toChar() in "-_.~")) {
                    append(c.toChar())
                } else {
                    append('%').append("0123456789ABCDEF"[c shr 4]).append("0123456789ABCDEF"[c and 0x0F])
                }
            }
        }

        /** Construit un appareil Roku à partir d'une réponse SSDP `roku:ecp`. */
        fun deviceFrom(response: SsdpResponse): Device? {
            if (response.searchTarget != Ssdp.ST_ROKU) return null
            // Une réponse mal formée ne doit pas faire échouer toute la découverte.
            val url = response.location?.toHttpUrlOrNull() ?: return null
            val serial = response.usn?.substringAfterLast(':')?.ifBlank { null } ?: url.host
            return Device("ROKU:$serial", "Roku ${url.host}", DeviceKind.ROKU, url.host, url.port)
        }
    }
}
