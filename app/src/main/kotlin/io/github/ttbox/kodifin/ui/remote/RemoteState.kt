package io.github.ttbox.kodifin.ui.remote

import io.github.ttbox.kodifin.model.SupportsFullscreen
import io.github.ttbox.kodifin.model.MediaItemInfo
import io.github.ttbox.kodifin.model.SupportsItemInfo
import io.github.ttbox.kodifin.model.DeclaresAvailability
import io.github.ttbox.kodifin.model.ControlFeature
import io.github.ttbox.kodifin.model.SupportsTransferOut
import io.github.ttbox.kodifin.model.MediaEntry
import io.github.ttbox.kodifin.model.MediaKind
import io.github.ttbox.kodifin.model.MediaSection
import io.github.ttbox.kodifin.model.PlaybackTarget
import io.github.ttbox.kodifin.model.PowerAction
import io.github.ttbox.kodifin.model.RemoteController
import io.github.ttbox.kodifin.model.SeriesContext
import io.github.ttbox.kodifin.model.SupportsChapters
import io.github.ttbox.kodifin.model.SupportsLibrary
import io.github.ttbox.kodifin.model.SupportsLibraryMaintenance
import io.github.ttbox.kodifin.model.SupportsNotify
import io.github.ttbox.kodifin.model.SupportsPlayMode
import io.github.ttbox.kodifin.model.SupportsPower
import io.github.ttbox.kodifin.model.SupportsImages
import io.github.ttbox.kodifin.model.SupportsQueue
import io.github.ttbox.kodifin.model.SupportsSearch
import io.github.ttbox.kodifin.model.SupportsSeries
import io.github.ttbox.kodifin.model.SupportsSeekStep
import io.github.ttbox.kodifin.model.SupportsSpeed
import io.github.ttbox.kodifin.model.SupportsSyncOffset
import io.github.ttbox.kodifin.model.SupportsTargets
import io.github.ttbox.kodifin.model.SupportsTracks
import io.github.ttbox.kodifin.model.SupportsUiCommands
import io.github.ttbox.kodifin.model.UiCommand
import io.github.ttbox.kodifin.text.UiText

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
    /** Plein écran avec état : « Activé / Désactivé » au lieu de la bascule. */
    val fullscreen: Boolean = false,
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
    /** Le média en cours peut être repris par un autre appareil. */
    val transferOut: Boolean = false,
    /** Nettoyage de la bibliothèque possible (sinon seule la mise à jour est proposée). */
    val cleanLibrary: Boolean = false,
    /** Retirer un élément de la file, la vider. */
    val queueEdit: Boolean = false,
    /** Fiches détaillées (résumé d'épisode, distribution). */
    val itemInfo: Boolean = false,
) {
    /** Le volet « Lecture en cours » a quelque chose à montrer au-delà du bandeau. */
    val hasPlaybackDetails: Boolean get() = tracks || speed || syncOffset || playMode || chapters

    companion object {
        /**
         * Fonctions affichées : interfaces implémentées par le contrôleur, moins celles qu'il
         * déclare impossibles avec l'appareil courant ([DeclaresAvailability]).
         */
        fun of(c: RemoteController): RemoteFeatures {
            val availability = c as? DeclaresAvailability
            fun available(feature: ControlFeature) = availability?.isAvailable(feature) ?: true
            return RemoteFeatures(
            tracks = c is SupportsTracks && available(ControlFeature.TRACKS),
            seekStep = c is SupportsSeekStep,
            speed = c is SupportsSpeed && available(ControlFeature.SPEED),
            syncOffset = c is SupportsSyncOffset,
            playMode = c is SupportsPlayMode && available(ControlFeature.PLAY_MODE),
            chapters = c is SupportsChapters && available(ControlFeature.CHAPTERS),
            uiCommands = (c as? SupportsUiCommands)?.uiCommands.orEmpty(),
            fullscreen = c is SupportsFullscreen,
            power = c is SupportsPower,
            notify = c is SupportsNotify && available(ControlFeature.NOTIFY),
            libraryMaintenance = c is SupportsLibraryMaintenance && available(ControlFeature.SCAN_LIBRARY),
            cleanLibrary = c is SupportsLibraryMaintenance && available(ControlFeature.CLEAN_LIBRARY),
            queueEdit = c is SupportsQueue && available(ControlFeature.QUEUE_EDIT),
            itemInfo = c is SupportsItemInfo,
            library = c is SupportsLibrary,
            queue = c is SupportsQueue,
            targets = c is SupportsTargets,
            series = c is SupportsSeries && c is SupportsLibrary,
            search = c is SupportsSearch,
            searchKinds = (c as? SupportsSearch)?.searchKinds.orEmpty(),
            images = c is SupportsImages,
            transferOut = c is SupportsTransferOut,
            )
        }
    }
}

/** Fiche d'un élément ouverte depuis une liste ; [entry] `null` : fermée. */
data class ItemInfoState(
    val entry: MediaEntry? = null,
    val loading: Boolean = false,
    val info: MediaItemInfo? = null,
)

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
    val error: UiText? = null,
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
    val error: UiText? = null,
    val path: List<LibraryLevel> = emptyList(),
    val pathLoading: Boolean = false,
    val pathError: UiText? = null,
) {
    val current: LibraryLevel? get() = path.lastOrNull()
}
