package io.github.ttbox.kodifin.ui.text

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.platform.LocalResources
import io.github.ttbox.kodifin.text.UiText
import io.github.ttbox.kodifin.text.resolve

/** Résout le texte dans un composable ; se recompose au changement de langue ou de configuration. */
@Composable
@ReadOnlyComposable
fun UiText.asString(): String = resolve(LocalResources.current)
