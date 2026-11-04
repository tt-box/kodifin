package io.github.jmorille.zapette.discovery

import io.github.jmorille.zapette.model.Device
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.cancellation.CancellationException

/** Intervalle par défaut entre deux découvertes ponctuelles (SSDP, UDP, GDM). */
internal const val DEFAULT_PERIOD_MS = 15_000L

/** Délai maximal par défaut d'une résolution NSD avant Android 14. */
internal const val DEFAULT_RESOLVE_TIMEOUT_MS = 10_000L

/**
 * Une source de découverte (un protocole) : flux des appareils actuellement visibles,
 * chaque émission remplaçant la précédente pour cette source.
 */
fun interface DiscoverySource {
    fun devices(): Flow<List<Device>>
}

/** Verrou multicast (WifiManager.MulticastLock), abstrait pour être testable sans Android. */
interface MulticastLockPort {
    val isHeld: Boolean
    fun acquire()
    fun release()
}

/**
 * Fusionne les sources : dédoublonnage par identifiant (voir [NsdMapping.consolidate]), tri insensible à la casse. Une source
 * en échec ne publie plus rien mais n'efface pas les appareils des autres. Le verrou multicast
 * est tenu pendant toute la collecte et libéré même en cas d'échec ou d'annulation.
 */
internal fun mergeSources(
    sources: List<DiscoverySource>,
    multicastLock: () -> MulticastLockPort?,
): Flow<List<Device>> = channelFlow {
    // Trié par indice de source : à identifiant égal, la source déclarée en premier donne le nom.
    val bySource = java.util.TreeMap<Int, List<Device>>()
    // Les sources publient en parallèle : sans verrou, un instantané périmé pourrait être émis en dernier.
    val mutex = Mutex()
    suspend fun update(index: Int, devices: List<Device>) = mutex.withLock {
        bySource[index] = devices
        send(NsdMapping.consolidate(bySource.values.flatten()).sortedBy { it.name.lowercase() })
    }

    val lock = runCatching { multicastLock() }.getOrNull()
    try {
        // Sans le verrou (permission retirée…), la découverte reste partiellement utile : on continue.
        runCatching { lock?.acquire() }
        sources.forEachIndexed { index, source ->
            launch {
                source.devices().catch { emit(emptyList()) }.collect { update(index, it) }
            }
        }
        awaitClose()
    } finally {
        // Libéré aussi si le flux échoue ou est annulé avant awaitClose (batterie).
        runCatching { if (lock?.isHeld == true) lock.release() }
    }
}

/** Relance une découverte ponctuelle toutes les [periodMs] tant que le flux est collecté. */
internal fun periodic(periodMs: Long = DEFAULT_PERIOD_MS, block: suspend () -> List<Device>): Flow<List<Device>> = flow {
    while (true) {
        emit(attempt { block() } ?: emptyList())
        delay(periodMs)
    }
}

/** Source périodique (SSDP, UDP, GDM…). */
internal fun periodicSource(periodMs: Long = DEFAULT_PERIOD_MS, block: suspend () -> List<Device>) =
    DiscoverySource { periodic(periodMs, block) }

/**
 * Comme runCatching, sans avaler l'annulation de la coroutine (sinon la boucle survit à l'écran).
 * Une [CancellationException] levée alors que la coroutine est toujours active (délai interne
 * `withTimeout` non rattrapé) est un simple échec : elle ne doit pas arrêter la source en silence.
 */
internal suspend fun <T> attempt(block: suspend () -> T): T? = try {
    block()
} catch (e: CancellationException) {
    currentCoroutineContext().ensureActive()
    null
} catch (e: Exception) {
    null
}
