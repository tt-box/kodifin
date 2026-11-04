package io.github.jmorille.zapette.ui.remote

import io.github.jmorille.zapette.model.MediaEntry
import io.github.jmorille.zapette.model.MediaKind
import io.github.jmorille.zapette.model.MediaSection
import io.github.jmorille.zapette.model.PlaybackTarget
import io.github.jmorille.zapette.model.PowerAction
import io.github.jmorille.zapette.model.RemoteController
import io.github.jmorille.zapette.model.SeriesContext
import io.github.jmorille.zapette.model.SupportsChapters
import io.github.jmorille.zapette.model.SupportsLibrary
import io.github.jmorille.zapette.model.SupportsLibraryMaintenance
import io.github.jmorille.zapette.model.SupportsNotify
import io.github.jmorille.zapette.model.SupportsPlayMode
import io.github.jmorille.zapette.model.SupportsPower
import io.github.jmorille.zapette.model.SupportsImages
import io.github.jmorille.zapette.model.SupportsQueue
import io.github.jmorille.zapette.model.SupportsSearch
import io.github.jmorille.zapette.model.SupportsSeries
import io.github.jmorille.zapette.model.SupportsSeekStep
import io.github.jmorille.zapette.model.SupportsSpeed
import io.github.jmorille.zapette.model.SupportsSyncOffset
import io.github.jmorille.zapette.model.SupportsTargets
import io.github.jmorille.zapette.model.SupportsTracks
import io.github.jmorille.zapette.model.SupportsUiCommands
import io.github.jmorille.zapette.model.UiCommand

/**
 * Contrôles avancés réellement disponibles pour le contrôleur courant : un contrôle n'est
 * affiché que si l'interface correspondante est implémentée (et, pour les sous-ensembles,
 * si la commande ou l'action d'alimentation est annoncée).
 */
data class RemoteFeatures(
    val tracks: Boolean = false,
    val seekStep: Boolean = false,
    val speed: Boolean = false,
    val syncOffset: Boolean = false,
    val playMode: Boolean = false,
    val chapters: Boolean = false,
    val uiCommands: Set<UiCommand> = emptySet(),
    val power: Boolean = false,
    /** Actions d'alimentation annoncées par l'appareil (chargées après la connexion). */
    val powerActions: Set<PowerAction> = emptySet(),
    val notify: Boolean = false,
    val libraryMaintenance: Boolean = false,
    val library: Boolean = false,
    val queue: Boolean = false,
    val targets: Boolean = false,
    /** Navigation dans la série en cours (saisons, épisodes). */
    val series: Boolean = false,
    /** Recherche native ; [searchKinds] : natures proposées en filtres. */
    val search: Boolean = false,
    val searchKinds: Set<MediaKind> = emptySet(),
    /** Affiches et vignettes fournies par le contrôleur. */
    val images: Boolean = false,
) {
    /** Le volet « Lecture en cours » a quelque chose à montrer au-delà du bandeau. */
    val hasPlaybackDetails: Boolean get() = tracks || speed || syncOffset || playMode || chapters

    companion object {
        fun of(c: RemoteController): RemoteFeatures = RemoteFeatures(
            tracks = c is SupportsTracks,
            seekStep = c is SupportsSeekStep,
            speed = c is SupportsSpeed,
            syncOffset = c is SupportsSyncOffset,
            playMode = c is SupportsPlayMode,
            chapters = c is SupportsChapters,
            uiCommands = (c as? SupportsUiCommands)?.uiCommands.orEmpty(),
            power = c is SupportsPower,
            notify = c is SupportsNotify,
            libraryMaintenance = c is SupportsLibraryMaintenance,
            library = c is SupportsLibrary,
            queue = c is SupportsQueue,
            targets = c is SupportsTargets,
            series = c is SupportsSeries && c is SupportsLibrary,
            search = c is SupportsSearch,
            searchKinds = (c as? SupportsSearch)?.searchKinds.orEmpty(),
            images = c is SupportsImages,
        )
    }
}

/** Volets de contenu dont l'affichage déclenche un chargement réseau. */
enum class RemotePanel { DETAILS, QUEUE, LIBRARY }

/** File d'attente active. */
data class QueueState(
    val items: List<MediaEntry> = emptyList(),
    val position: Int? = null,
    val loading: Boolean = false,
    val loaded: Boolean = false,
)

/** Niveau de navigation dans la bibliothèque (rubrique ou dossier) et ses éléments déjà chargés. */
data class LibraryLevel(
    val id: String,
    val title: String,
    val entries: List<MediaEntry> = emptyList(),
    val endReached: Boolean = false,
)

/** Bibliothèque : rubriques racines puis pile des dossiers ouverts. */
data class LibraryState(
    val sections: List<MediaSection> = emptyList(),
    val sectionsLoaded: Boolean = false,
    val path: List<LibraryLevel> = emptyList(),
    val loading: Boolean = false,
) {
    val current: LibraryLevel? get() = path.lastOrNull()
}

/** Cibles de lecture (sessions) proposées par le serveur. */
data class TargetsState(
    val items: List<PlaybackTarget> = emptyList(),
    val currentId: String? = null,
) {
    val current: PlaybackTarget? get() = items.firstOrNull { it.id == currentId }
}

/** Série du média en cours (vue « Série » du volet File) : saisons et épisodes de la saison choisie. */
data class SeriesState(
    val context: SeriesContext? = null,
    /** `currentSeries()` a répondu (éventuellement `null` : pas un épisode). */
    val loaded: Boolean = false,
    val loading: Boolean = false,
    val seasonId: String? = null,
    val episodes: List<MediaEntry> = emptyList(),
    val episodesLoading: Boolean = false,
    val error: String? = null,
)

/** Recherche plein écran : saisie, filtres, résultats et pile des dossiers ouverts depuis un résultat. */
data class SearchState(
    val open: Boolean = false,
    val query: String = "",
    /** Filtres choisis ; vide : toutes les natures. */
    val kinds: Set<MediaKind> = emptySet(),
    val results: List<MediaEntry> = emptyList(),
    val loading: Boolean = false,
    /** Une recherche a abouti pour [query] (distingue « aucun résultat » de « rien saisi »). */
    val searched: Boolean = false,
    val error: String? = null,
    val path: List<LibraryLevel> = emptyList(),
    val pathLoading: Boolean = false,
    val pathError: String? = null,
) {
    val current: LibraryLevel? get() = path.lastOrNull()
}
