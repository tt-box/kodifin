package io.github.jmorille.zapette.model

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Table unique extension → type MIME, partagée par tous les protocoles (Cast, UPnP,
 * Fire TV, Roku) pour qu'un même fichier soit annoncé partout de la même façon.
 */
object MediaTypes {
    private val BY_EXTENSION: Map<String, String> = mapOf(
        // Audio
        "mp3" to "audio/mpeg",
        "aac" to "audio/aac",
        "m4a" to "audio/mp4",
        "flac" to "audio/flac",
        "ogg" to "audio/ogg",
        "oga" to "audio/ogg",
        "opus" to "audio/ogg",
        "wav" to "audio/wav",
        "wma" to "audio/x-ms-wma",
        // Vidéo
        "mp4" to "video/mp4",
        "m4v" to "video/mp4",
        "mkv" to "video/x-matroska",
        "webm" to "video/webm",
        "avi" to "video/x-msvideo",
        "mov" to "video/quicktime",
        "ts" to "video/mp2t",
        "ogv" to "video/ogg",
        "wmv" to "video/x-ms-wmv",
        // Flux adaptatifs (RFC 8216 pour HLS)
        "m3u8" to "application/vnd.apple.mpegurl",
        "mpd" to "application/dash+xml",
        "ism" to "application/vnd.ms-sstr+xml",
        // Images
        "jpg" to "image/jpeg",
        "jpeg" to "image/jpeg",
        "png" to "image/png",
        "gif" to "image/gif",
        "webp" to "image/webp",
    )

    /** Type MIME de l'extension (sans point, casse indifférente) ; `null` si inconnue. */
    fun forExtension(extension: String): String? = BY_EXTENSION[extension.lowercase()]

    /** Type MIME déduit de l'URL ; `null` si elle n'a pas d'extension connue. */
    fun forUrl(url: String): String? = extension(url)?.let(::forExtension)

    /**
     * Extension (en minuscules) du dernier segment du chemin, requête et fragment ignorés ;
     * `null` sans extension. L'hôte n'est jamais pris en compte : « http://nas.local/stream »
     * n'a pas d'extension.
     */
    fun extension(url: String): String? {
        val path = url.trim().toHttpUrlOrNull()?.encodedPath ?: pathOf(url.trim())
        val last = path.substringAfterLast('/')
        val ext = last.substringAfterLast('.', "")
        return ext.takeIf { it.isNotEmpty() }?.lowercase()
    }

    fun isAudio(mime: String?): Boolean = mime?.startsWith("audio/") == true
    fun isVideo(mime: String?): Boolean = mime?.startsWith("video/") == true
    fun isImage(mime: String?): Boolean = mime?.startsWith("image/") == true

    /** Chemin d'une URL qu'OkHttp ne sait pas analyser (schéma non HTTP, chemin relatif…). */
    private fun pathOf(url: String): String {
        val noSuffix = url.substringBefore('#').substringBefore('?')
        val schemeEnd = noSuffix.indexOf("://")
        if (schemeEnd < 0) return noSuffix
        val afterAuthority = noSuffix.substring(schemeEnd + 3)
        val slash = afterAuthority.indexOf('/')
        return if (slash < 0) "" else afterAuthority.substring(slash)
    }
}
