package io.github.ttbox.kodifin.text

import android.content.Context
import android.content.res.Resources

/** Adaptateur Android de [TextResources] (mince : exclu de la couverture). */
class AndroidTextResources(private val resources: Resources) : TextResources {
    override fun string(id: Int, args: Array<Any>): String =
        if (args.isEmpty()) resources.getString(id) else resources.getString(id, *args)

    override fun plural(id: Int, count: Int, args: Array<Any>): String =
        resources.getQuantityString(id, count, *args)
}

/**
 * Résout le texte avec des [Resources] ; en Compose, lues par `LocalResources.current` (à
 * capturer avant un rappel `onClick` ou une coroutine).
 */
fun UiText.resolve(resources: Resources): String = resolve(AndroidTextResources(resources))

/** Résout le texte hors Compose (notification, partage…). */
fun UiText.resolve(context: Context): String = resolve(context.resources)
