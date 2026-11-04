package io.github.jmorille.zapette.cast

import io.github.jmorille.zapette.model.MediaTypes

/** Fonctions pures (testables sans Android) pour préparer un média Cast. */
object CastMedia {
    /** Type MIME annoncé au récepteur ; `video/mp4` si l'extension est inconnue. */
    fun contentType(url: String): String = MediaTypes.forUrl(url) ?: "video/mp4"

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
