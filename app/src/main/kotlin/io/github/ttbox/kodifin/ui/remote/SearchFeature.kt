package io.github.ttbox.kodifin.ui.remote

import io.github.ttbox.kodifin.model.MediaEntry
import io.github.ttbox.kodifin.model.MediaKind
import io.github.ttbox.kodifin.model.SupportsLibrary
import io.github.ttbox.kodifin.model.SupportsSearch
import io.github.ttbox.kodifin.text.UiText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Vue « Recherche » : saisie au fil de la frappe (après [debounceMs] sans nouvelle touche),
 * filtres par type, puis navigation dans un résultat (série, saison, album…).
 */
class SearchFeature internal constructor(
    private val session: RemoteSession,
    private val debounceMs: Long,
) {
    private var searchJob: Job? = null
    private var pathJob: Job? = null

    fun open() {
        if (!session.value.features.search) return
        session.update { it.copy(search = it.search.copy(open = true)) }
    }

    /** Ferme la vue de recherche ; la saisie et les résultats sont conservés pour la réouverture. */
    fun close() {
        pathJob?.cancel()
        session.update {
            it.copy(search = it.search.copy(open = false, path = emptyList(), pathLoading = false, pathError = null))
        }
    }

    /** Recherche au fil de la frappe, après [debounceMs] sans nouvelle saisie. */
    fun setQuery(query: String) {
        if (query == session.value.search.query) return
        session.update { it.copy(search = it.search.copy(query = query)) }
        schedule(debounceMs)
    }

    /** Touche « Rechercher » du clavier : sans attendre. */
    fun submit() = schedule(0)

    fun toggleKind(kind: MediaKind) {
        session.update {
            val kinds = it.search.kinds
            it.copy(search = it.search.copy(kinds = if (kind in kinds) kinds - kind else kinds + kind))
        }
        schedule(0)
    }

    fun clearKinds() {
        if (session.value.search.kinds.isEmpty()) return
        session.update { it.copy(search = it.search.copy(kinds = emptySet())) }
        schedule(0)
    }

    private fun schedule(delayMs: Long) {
        searchJob?.cancel()
        val query = session.value.search.query.trim()
        if (query.length < MIN_LENGTH) {
            session.update {
                it.copy(search = it.search.copy(results = emptyList(), loading = false, searched = false, error = null))
            }
            return
        }
        val c = session.connectedAs<SupportsSearch>() ?: return
        session.update { it.copy(search = it.search.copy(loading = true, error = null)) }
        searchJob = session.scope.launch {
            if (delayMs > 0) delay(delayMs)
            val kinds = session.value.search.kinds
            try {
                val results = withContext(session.io) { c.search(query, kinds, LIMIT) }
                session.update {
                    it.copy(search = it.search.copy(results = results, loading = false, searched = true, error = null))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                session.update {
                    it.copy(search = it.search.copy(loading = false, searched = false, error = session.errorMessage(e)))
                }
            }
        }
    }

    /** Résultat navigable (série, saison, album…) : ouvert dans la vue de recherche. */
    fun openEntry(entry: MediaEntry) {
        if (!entry.browsable) return
        session.connectedAs<SupportsLibrary>() ?: return
        pathJob?.cancel()
        session.update {
            it.copy(
                search = it.search.copy(path = it.search.path + LibraryLevel(entry.id, entry.title), pathLoading = false, pathError = null),
            )
        }
        loadPage()
    }

    fun loadMore() {
        val s = session.value.search
        val level = s.current ?: return
        if (s.pathLoading || level.endReached) return
        loadPage()
    }

    /** Remonte d'un niveau dans la vue de recherche ; `false` si l'on est déjà sur les résultats. */
    fun back(): Boolean {
        if (session.value.search.path.isEmpty()) return false
        pathJob?.cancel()
        session.update {
            it.copy(search = it.search.copy(path = it.search.path.dropLast(1), pathLoading = false, pathError = null))
        }
        return true
    }

    private fun loadPage() {
        val c = session.connectedAs<SupportsLibrary>() ?: return
        val search = session.value.search
        val level = search.current ?: return
        val depth = search.path.size
        session.update { it.copy(search = it.search.copy(pathLoading = true, pathError = null)) }
        pathJob = session.scope.launch {
            var error: UiText? = null
            val page = try {
                withContext(session.io) { c.browse(level.id, level.entries.size, LIBRARY_PAGE_SIZE) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = session.errorMessage(e)
                null
            }
            session.update { s ->
                val path = s.search.path
                // Navigation entre-temps : la page reçue ne concerne plus le dossier affiché.
                if (path.size != depth || path.last().id != level.id) {
                    return@update s.copy(search = s.search.copy(pathLoading = false))
                }
                val updated = if (page == null) {
                    path.last().copy(endReached = true)
                } else {
                    path.last().copy(entries = path.last().entries + page, endReached = page.size < LIBRARY_PAGE_SIZE)
                }
                s.copy(search = s.search.copy(path = path.dropLast(1) + updated, pathLoading = false, pathError = error))
            }
        }
    }

    internal fun cancel() {
        searchJob?.cancel()
        pathJob?.cancel()
    }

    internal companion object {
        const val DEFAULT_DEBOUNCE_MS = 350L
        const val LIMIT = 60
        const val MIN_LENGTH = 2
    }
}
