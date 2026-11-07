package io.github.ttbox.kodifin.ui.remote

import io.github.ttbox.kodifin.model.SupportsLibrary
import io.github.ttbox.kodifin.model.SupportsSeries
import io.github.ttbox.kodifin.text.UiText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Volet « Série en cours » : saisons de la série du média lu et épisodes de la saison choisie. */
class SeriesFeature internal constructor(private val session: RemoteSession) {
    private var job: Job? = null

    /** Recharge la série du média en cours ; la saison courante est sélectionnée par défaut. */
    fun refresh() {
        val c = session.connectedAs<SupportsSeries>() ?: return
        job?.cancel()
        session.update { it.copy(series = it.series.copy(loading = true, error = null)) }
        job = session.scope.launch {
            val context = try {
                withContext(session.io) { c.currentSeries() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                session.update { it.copy(series = it.series.copy(loading = false, error = session.errorMessage(e))) }
                return@launch
            }
            if (context == null) {
                session.update { it.copy(series = SeriesState(loaded = true)) }
                return@launch
            }
            val seasonId = context.currentSeasonId?.takeIf { id -> context.seasons.any { it.id == id } }
                ?: context.seasons.firstOrNull()?.id
            session.update {
                it.copy(
                    series = SeriesState(context = context, loaded = true, seasonId = seasonId, episodesLoading = seasonId != null),
                )
            }
            if (seasonId != null) loadEpisodes(seasonId)
        }
    }

    /** Affiche les épisodes d'une autre saison de la série. */
    fun selectSeason(seasonId: String) {
        val series = session.value.series
        if (series.context == null || (seasonId == series.seasonId && series.episodes.isNotEmpty())) return
        job?.cancel()
        session.update {
            it.copy(series = it.series.copy(seasonId = seasonId, episodes = emptyList(), episodesLoading = true, error = null))
        }
        job = session.scope.launch { loadEpisodes(seasonId) }
    }

    private suspend fun loadEpisodes(seasonId: String) {
        val c = session.connectedAs<SupportsLibrary>() ?: return
        var error: UiText? = null
        val episodes = try {
            withContext(session.io) { c.browse(seasonId, 0, SEASON_PAGE_SIZE) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = session.errorMessage(e)
            emptyList()
        }
        session.update { s ->
            // Autre saison choisie entre-temps : réponse ignorée.
            if (s.series.seasonId != seasonId) return@update s
            s.copy(series = s.series.copy(episodes = episodes, episodesLoading = false, error = error))
        }
    }

    internal fun cancel() {
        job?.cancel()
    }

    private companion object {
        const val SEASON_PAGE_SIZE = 300
    }
}
