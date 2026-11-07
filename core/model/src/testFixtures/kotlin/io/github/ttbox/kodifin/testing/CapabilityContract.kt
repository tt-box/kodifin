package io.github.ttbox.kodifin.testing

import io.github.ttbox.kodifin.model.Capability
import io.github.ttbox.kodifin.model.RemoteController
import io.github.ttbox.kodifin.model.UnsupportedActionException
import kotlinx.coroutines.CancellationException
import kotlin.test.fail

/**
 * Contrat des capacités : ce qu'un contrôleur annonce ([RemoteController.capabilities],
 * [RemoteController.supportedKeys]) ne doit jamais lever [UnsupportedActionException].
 * L'interface se fie à ces annonces pour afficher les commandes : une capacité annoncée mais
 * absente donnerait un bouton qui répond « non pris en charge ».
 *
 * Chaque action annoncée est appelée une fois. Les autres erreurs (appareil injoignable, non
 * connecté…) sont tolérées : le contrat porte sur la cohérence des annonces, pas sur le réseau.
 * L'appareil du contrôleur doit donc pointer vers une adresse qui refuse vite (127.0.0.1:1).
 */
suspend fun assertHonorsCapabilities(controller: RemoteController) {
    val failures = mutableListOf<String>()

    suspend fun attempt(label: String, block: suspend () -> Unit) {
        val error = try {
            block()
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            e
        }
        val unsupported = generateSequence(error) { it.cause }.firstOrNull { it is UnsupportedActionException }
        if (unsupported != null) failures += "$label : ${unsupported.message}"
    }

    controller.supportedKeys.forEach { key -> attempt("touche $key") { controller.send(key) } }
    controller.capabilities.forEach { capability ->
        when (capability) {
            Capability.VOLUME -> attempt("capacité VOLUME") { controller.setVolume(50) }
            Capability.SEEK -> attempt("capacité SEEK") { controller.seekTo(1_000) }
            Capability.PLAY_URL -> attempt("capacité PLAY_URL") { controller.playUrl("http://127.0.0.1:1/video.mp4", "Vidéo") }
            Capability.TEXT_INPUT -> attempt("capacité TEXT_INPUT") { controller.sendText("a") }
            // Pas d'action propre : couvertes par les touches et le flux d'état.
            Capability.NAVIGATION, Capability.PLAYBACK, Capability.NOW_PLAYING -> Unit
        }
    }

    if (failures.isNotEmpty()) {
        fail("${controller.device.kind} annonce des actions qu'il ne prend pas en charge :\n" + failures.joinToString("\n"))
    }
}
