package io.github.jmorille.zapette

/** Extraction du lien reçu via « Partager » (logique pure, testée unitairement). */
object SharedLink {
    private val URL_REGEX = Regex("""https?://\S+""", RegexOption.IGNORE_CASE)

    /** Ponctuation souvent collée à un lien dans un texte partagé (« voir https://x.y/z. »). */
    private const val TRAILING = ".,;:!?)]}>\"'»…"

    /**
     * Renvoie l'URL contenue dans un partage `ACTION_SEND`, ou `null`.
     * [launchedFromHistory] : l'activité est relancée depuis l'écran « Récents » avec
     * l'intent d'origine ; le lien a déjà été proposé, il ne faut pas le reproposer.
     */
    fun extract(action: String?, text: CharSequence?, launchedFromHistory: Boolean = false): String? {
        if (action != ACTION_SEND || launchedFromHistory || text == null) return null
        var url = URL_REGEX.find(text)?.value ?: return null
        while (url.isNotEmpty() && url.last() in TRAILING) {
            // Parenthèse fermante conservée si elle équilibre une ouvrante (liens Wikipédia).
            if (url.last() == ')' && url.count { it == '(' } >= url.count { it == ')' }) break
            url = url.dropLast(1)
        }
        return url.takeIf { it.substringAfter("://").isNotEmpty() }
    }

    /** Valeur de `Intent.ACTION_SEND` (dupliquée pour rester testable sans Android). */
    const val ACTION_SEND = "android.intent.action.SEND"
}
