package io.github.ttbox.kodifin.text

import androidx.annotation.PluralsRes
import androidx.annotation.StringRes

/**
 * Texte destiné à l'interface, produit hors de Compose (ViewModel, diagnostic, logique pure).
 *
 * La logique ne connaît que des identifiants de ressources : la langue n'est choisie qu'à
 * l'affichage ([resolve], ou `asString()` en Compose). Les tests unitaires comparent donc des
 * valeurs (`UiText.Res(R.string.x, …)`) sans contexte Android.
 *
 * Les arguments peuvent eux-mêmes être des [UiText] : ils sont résolus avant d'être insérés.
 */
sealed interface UiText {
    /** Chaîne `strings.xml`, avec ses arguments de format (`%1$s`, `%1$d`…). */
    data class Res(@param:StringRes val id: Int, val args: List<Any> = emptyList()) : UiText {
        constructor(@StringRes id: Int, vararg args: Any) : this(id, args.toList())
    }

    /** Pluriel `<plurals>` : [count] choisit la forme, [args] remplit le format (par défaut le nombre). */
    data class Plural(@param:PluralsRes val id: Int, val count: Int, val args: List<Any> = listOf(count)) : UiText

    /**
     * Texte déjà rédigé ailleurs, affiché tel quel : nom d'appareil, titre de média, message
     * renvoyé par le serveur. Jamais pour un libellé de l'application.
     */
    data class Raw(val value: String) : UiText
}

/** Ressources nécessaires à la résolution ; interface pour rester testable sans Android. */
interface TextResources {
    fun string(@StringRes id: Int, args: Array<Any>): String
    fun plural(@PluralsRes id: Int, count: Int, args: Array<Any>): String
}

/** Résout le texte dans la langue courante. */
fun UiText.resolve(res: TextResources): String = when (this) {
    is UiText.Raw -> value
    is UiText.Res -> res.string(id, resolveArgs(args, res))
    is UiText.Plural -> res.plural(id, count, resolveArgs(args, res))
}

private fun resolveArgs(args: List<Any>, res: TextResources): Array<Any> =
    args.map { if (it is UiText) it.resolve(res) else it }.toTypedArray()
