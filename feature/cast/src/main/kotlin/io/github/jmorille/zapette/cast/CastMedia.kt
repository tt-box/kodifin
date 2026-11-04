package io.github.jmorille.zapette.cast

/** Fonctions pures (testables sans Android) pour préparer un média Cast. */
object CastMedia {
    fun contentType(url: String): String =
        when (url.substringBefore('?').substringBefore('#').substringAfterLast('.', "").lowercase()) {
            "mp3" -> "audio/mpeg"
            "aac", "m4a" -> "audio/mp4"
            "flac" -> "audio/flac"
            "ogg", "opus" -> "audio/ogg"
            "webm" -> "video/webm"
            "mkv" -> "video/x-matroska"
            "m3u8" -> "application/x-mpegURL"
            "mpd" -> "application/dash+xml"
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            else -> "video/mp4"
        }

    fun isAudio(contentType: String) = contentType.startsWith("audio/")

    /** Applique un pas de volume (0.0–1.0) en restant dans les bornes. */
    fun stepVolume(current: Double, step: Double): Double = (current + step).coerceIn(0.0, 1.0)

    /**
     * Compare deux adresses en ignorant crochets IPv6, zone (`%wlan0`) et casse : permet de
     * retrouver la route MediaRouter d'un Chromecast saisi à la main (identifiant non issu de la découverte).
     */
    fun sameHost(a: String?, b: String?): Boolean {
        fun norm(h: String?) = h?.trim()?.removePrefix("[")?.removeSuffix("]")?.substringBefore('%')?.lowercase()
        val x = norm(a)
        return !x.isNullOrEmpty() && x == norm(b)
    }

    fun clampSeek(positionMs: Long, durationMs: Long): Long =
        if (durationMs > 0) positionMs.coerceIn(0, durationMs) else positionMs.coerceAtLeast(0)
}
