package io.github.ttbox.kodifin.ui.remote

import io.github.ttbox.kodifin.model.MediaEntry
import io.github.ttbox.kodifin.model.MediaSection
import io.github.ttbox.kodifin.model.SupportsLibrary
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Volet « Bibliothèque » : rubriques racines puis dossiers parcourus page par page.
 * [play] lance un élément jouable (même action que depuis la recherche ou la série).
 */
class LibraryFeature internal constructor(
    private val session: RemoteSession,
    private val play: (MediaEntry) -> Unit,
) {
    private var job: Job? = null

    /** Charge les rubriques racines une seule fois (à la première ouverture du volet). */
    fun open() {
        val c = session.connectedAs<SupportsLibrary>() ?: return
        val lib = session.value.library
        if (lib.sectionsLoaded || lib.loading) return
        job?.cancel()
        session.update { it.copy(library = it.library.copy(loading = true)) }
        job = session.scope.launch {
            val sections = session.load { c.sections() }
            session.update {
                it.copy(
                    library = it.library.copy(
                        sections = sections ?: it.library.sections,
                        sectionsLoaded = sections != null,
                        loading = false,
                    ),
                )
            }
        }
    }

    fun openSection(section: MediaSection) = pushFolder(section.id, section.title)

    /** Ouvre un dossier, ou lance la lecture d'un élément jouable. */
    fun openEntry(entry: MediaEntry) {
        if (entry.browsable) pushFolder(entry.id, entry.title) else play(entry)
    }

    private fun pushFolder(id: String, title: String) {
        session.connectedAs<SupportsLibrary>() ?: return
        job?.cancel()
        session.update {
            it.copy(library = it.library.copy(path = it.library.path + LibraryLevel(id, title), loading = false))
        }
        loadPage()
    }

    /** Page suivante du dossier courant (défilement en bas de liste). */
    fun loadMore() {
        val lib = session.value.library
        val level = lib.current ?: return
        if (lib.loading || level.endReached) return
        loadPage()
    }

    private fun loadPage() {
        val c = session.connectedAs<SupportsLibrary>() ?: return
        val lib = session.value.library
        val level = lib.current ?: return
        val depth = lib.path.size
        session.update { it.copy(library = it.library.copy(loading = true)) }
        job = session.scope.launch {
            val page = session.load { c.browse(level.id, level.entries.size, LIBRARY_PAGE_SIZE) }
            session.update { s ->
                val path = s.library.path
                // Navigation entre-temps (retour) : la page reçue ne concerne plus le dossier affiché.
                if (path.size != depth || path.last().id != level.id) {
                    return@update s.copy(library = s.library.copy(loading = false))
                }
                val updated = if (page == null) {
                    // Échec : on arrête le chargement automatique (évite une boucle d'erreurs).
                    path.last().copy(endReached = true)
                } else {
                    path.last().copy(entries = path.last().entries + page, endReached = page.size < LIBRARY_PAGE_SIZE)
                }
                s.copy(library = s.library.copy(path = path.dropLast(1) + updated, loading = false))
            }
        }
    }

    /** Remonte d'un niveau ; `false` si l'on est déjà à la racine (le retour système s'applique). */
    fun back(): Boolean {
        if (session.value.library.path.isEmpty()) return false
        job?.cancel()
        session.update { it.copy(library = it.library.copy(path = it.library.path.dropLast(1), loading = false)) }
        return true
    }

    internal fun cancel() {
        job?.cancel()
    }
}
