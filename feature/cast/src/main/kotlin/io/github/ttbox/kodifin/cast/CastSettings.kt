package io.github.ttbox.kodifin.cast

/**
 * Débit vidéo maximal souhaité. Le récepteur multimédia par défaut de Google Cast n'offre
 * aucune commande côté émetteur pour plafonner le débit (il choisit lui-même la variante HLS/DASH
 * selon sa bande passante) : la valeur est transmise dans les données personnalisées du
 * chargement (`customData.maxBitrate`, en bit/s), exploitables par un récepteur personnalisé et
 * ignorées sans erreur par le récepteur par défaut. Elle sert aussi de préférence de média
 * (voir [CastLoadOptions]).
 */
enum class CastMaxBitrate(val label: String, val bitsPerSecond: Long?) {
    AUTO("Automatique", null),
    P1080("1080p (≈ 8 Mb/s)", 8_000_000),
    P720("720p (≈ 4 Mb/s)", 4_000_000),
    P480("480p (≈ 1,5 Mb/s)", 1_500_000),
}

/** Taille des sous-titres (facteur d'échelle du TextTrackStyle Cast). */
enum class SubtitleSize(val label: String, val fontScale: Float) {
    SMALL("Petite", 0.75f),
    NORMAL("Normale", 1.0f),
    LARGE("Grande", 1.25f),
    HUGE("Très grande", 1.5f),
}

/** Couleur du texte des sous-titres (ARGB). */
enum class SubtitleColor(val label: String, val argb: Int) {
    WHITE("Blanc", 0xFFFFFFFF.toInt()),
    YELLOW("Jaune", 0xFFFFFF00.toInt()),
    CYAN("Cyan", 0xFF00FFFF.toInt()),
    GREEN("Vert", 0xFF00FF00.toInt()),
}

/** Fond des sous-titres (ARGB) ; sans fond, un contour garde le texte lisible. */
enum class SubtitleBackground(val label: String, val argb: Int) {
    NONE("Aucun", 0x00000000),
    TRANSLUCENT("Semi-transparent", 0x80000000.toInt()),
    OPAQUE("Noir opaque", 0xFF000000.toInt()),
}

/** Style des sous-titres appliqué à la session Cast. */
data class CastSubtitleStyle(
    val size: SubtitleSize = SubtitleSize.NORMAL,
    val textColor: SubtitleColor = SubtitleColor.WHITE,
    val background: SubtitleBackground = SubtitleBackground.TRANSLUCENT,
) {
    /** Contour du texte quand il n'y a pas de fond. */
    val outlined: Boolean get() = background == SubtitleBackground.NONE
}

/**
 * Réglages Chromecast globaux. [autoDisconnect] : fermer la session Cast en quittant la
 * télécommande (comportement historique, d'où la valeur par défaut `true`) ; sinon la lecture
 * continue et la session reste ouverte pour d'autres applications.
 */
data class CastSettings(
    val maxBitrate: CastMaxBitrate = CastMaxBitrate.AUTO,
    val subtitles: CastSubtitleStyle = CastSubtitleStyle(),
    val autoDisconnect: Boolean = true,
)

/** Options d'un chargement de média, issues de [CastSettings]. */
data class CastLoadOptions(
    /** Plafond de débit en bit/s, `null` = automatique. */
    val maxBitrate: Long? = null,
    val subtitles: CastSubtitleStyle = CastSubtitleStyle(),
) {
    companion object {
        fun from(settings: CastSettings) = CastLoadOptions(settings.maxBitrate.bitsPerSecond, settings.subtitles)
    }
}
