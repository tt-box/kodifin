package io.github.ttbox.kodifin.ui.remote

import io.github.ttbox.kodifin.model.SupportsQueue
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Volet « File d'attente » : contenu de la playlist en cours et actions sur ses éléments. */
class QueueFeature internal constructor(private val session: RemoteSession) {
    private var job: Job? = null

    fun refresh() {
        val c = session.connectedAs<SupportsQueue>() ?: return
        job?.cancel()
        session.update { it.copy(queue = it.queue.copy(loading = true)) }
        job = session.scope.launch {
            val result = session.load { c.queue() to c.queuePosition() }
            session.update {
                if (result == null) {
                    it.copy(queue = it.queue.copy(loading = false))
                } else {
                    it.copy(queue = QueueState(result.first, result.second, loading = false, loaded = true))
                }
            }
        }
    }

    private val afterChange: () -> Unit = { refresh() }

    fun play(index: Int) = session.runOn<SupportsQueue>(afterChange) { it.playQueueItem(index) }
    fun remove(index: Int) = session.runOn<SupportsQueue>(afterChange) { it.removeQueueItem(index) }
    fun clear() = session.runOn<SupportsQueue>(afterChange) { it.clearQueue() }

    internal fun cancel() {
        job?.cancel()
    }
}
