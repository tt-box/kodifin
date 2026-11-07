package io.github.ttbox.kodifin.ui.remote

import io.github.ttbox.kodifin.R
import io.github.ttbox.kodifin.model.RemoteController
import io.github.ttbox.kodifin.text.AppException
import io.github.ttbox.kodifin.text.UiText
import io.github.ttbox.kodifin.text.toUiText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Contexte partagé entre [RemoteViewModel] et ses volets (file, bibliothèque, série,
 * recherche, transfert) : état de l'écran, portée, contrôleur connecté et exécution des
 * commandes avec remontée des erreurs. Chaque volet ne voit que ce contexte, pas le
 * cycle de connexion du ViewModel.
 */
internal class RemoteSession(
    private val state: MutableStateFlow<RemoteUiState>,
    private val messages: SendChannel<UiText>,
    val scope: CoroutineScope,
    val io: CoroutineDispatcher,
    private val current: () -> RemoteController?,
) {
    val value: RemoteUiState get() = state.value

    /** Contrôleur courant, connecté ou non (`null` avant la première connexion). */
    val controller: RemoteController? get() = current()

    fun update(transform: (RemoteUiState) -> RemoteUiState) = state.update(transform)

    fun message(text: UiText) {
        messages.trySend(text)
    }

    /** Contrôleur connecté s'il implémente [T], sinon `null`. */
    inline fun <reified T : Any> connectedAs(): T? = controller?.takeIf { value.connected } as? T

    /**
     * Exécute une commande sur l'appareil connecté ; [after] n'est appelé qu'en cas de
     * succès, par exemple pour recharger un volet.
     */
    fun run(after: (() -> Unit)? = null, action: suspend (RemoteController) -> Unit) {
        val c = controller
        if (c == null || !value.connected) {
            message(UiText.Res(R.string.remote_not_connected))
            return
        }
        scope.launch {
            try {
                withContext(io) { action(c) }
                after?.invoke()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                message(errorMessage(e))
            }
        }
    }

    /** Commande d'un contrôle avancé : refusée proprement si le contrôleur ne l'implémente pas. */
    inline fun <reified T : Any> runOn(
        noinline after: (() -> Unit)? = null,
        crossinline action: suspend (T) -> Unit,
    ) = run(after) { c -> action(c as? T ?: throw AppException(NOT_SUPPORTED)) }

    /** Lecture d'une donnée de l'appareil ; `null` (et message court) en cas d'échec. */
    suspend fun <R> load(block: suspend () -> R): R? = try {
        withContext(io) { block() }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        message(errorMessage(e))
        null
    }

    /** Message d'un échec : raison typée du protocole traduite, sinon un libellé générique. */
    fun errorMessage(e: Exception): UiText = e.toUiText(UiText.Res(R.string.remote_command_refused))

    companion object {
        val NOT_SUPPORTED = UiText.Res(R.string.remote_not_supported)
    }
}

/** Taille d'une page de dossier (bibliothèque et navigation depuis la recherche). */
internal const val LIBRARY_PAGE_SIZE = 100
