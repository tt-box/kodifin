package io.github.jmorille.zapette.ui.remote

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException

/** Décodage d'une image (injectable : remplacé par un faux dans les tests JVM). */
interface ImageDecoder<T : Any> {
    /** Décode [bytes] en visant une largeur d'au moins [targetWidthPx] pixels ; `null` si illisible. */
    fun decode(bytes: ByteArray, targetWidthPx: Int): T?

    /** Taille approximative en octets, pour borner le cache mémoire. */
    fun sizeOf(image: T): Int
}

/**
 * Cache LRU borné en octets (équivalent de `android.util.LruCache`, mais utilisable en JVM).
 * Accès synchronisés : appelé depuis le thread principal et les tâches de décodage.
 */
class SizedLruCache<K : Any, V : Any>(private val maxSize: Long, private val sizeOf: (V) -> Int) {
    private val map = LinkedHashMap<K, V>(16, 0.75f, true)
    private var size = 0L

    @Synchronized
    operator fun get(key: K): V? = map[key]

    @Synchronized
    fun put(key: K, value: V) {
        map.put(key, value)?.let { size -= sizeOf(it) }
        size += sizeOf(value)
        val it = map.entries.iterator()
        while (size > maxSize && it.hasNext()) {
            val eldest = it.next()
            // Une image plus grande que le cache entier n'est pas conservée.
            size -= sizeOf(eldest.value)
            it.remove()
        }
    }

    @Synchronized
    fun clear() {
        map.clear()
        size = 0
    }

    @get:Synchronized
    val currentSize: Long get() = size

    @get:Synchronized
    val count: Int get() = map.size
}

/**
 * Chargeur d'images sans bibliothèque externe : les octets viennent du contrôleur ([fetch],
 * authentifié, jamais d'URL tierce), sont décodés hors du thread principal puis gardés en
 * mémoire. Deux demandes simultanées de la même image partagent une seule requête ; la requête
 * est annulée quand plus personne ne l'attend (élément sorti de l'écran).
 */
class ImageLoader<T : Any>(
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
    private val decoder: ImageDecoder<T>,
    maxCacheBytes: Long,
    private val fetch: suspend (imageKey: String, maxWidth: Int) -> ByteArray?,
) {
    private class Request<T>(val deferred: Deferred<T?>) {
        var waiters = 0
    }

    private val cache = SizedLruCache<String, T>(maxCacheBytes) { decoder.sizeOf(it) }
    private val inFlight = HashMap<String, Request<T>>()

    /** Images introuvables ou illisibles : pas de nouvelle requête pendant la session. */
    private val failed = HashSet<String>()
    private val lock = Any()

    /** Image déjà en mémoire (affichage immédiat, sans clignotement). */
    fun cached(imageKey: String, widthPx: Int): T? = cache[cacheKey(imageKey, widthPx)]

    suspend fun load(imageKey: String, widthPx: Int): T? {
        val bucket = bucket(widthPx)
        val key = cacheKey(imageKey, widthPx)
        cache[key]?.let { return it }
        val request = synchronized(lock) {
            if (key in failed) return null
            inFlight.getOrPut(key) { Request(scope.async { fetchAndDecode(key, imageKey, bucket) }) }
                .also { it.waiters++ }
        }
        try {
            return request.deferred.await()
        } finally {
            synchronized(lock) {
                request.waiters--
                if (request.waiters == 0 && inFlight[key] === request) {
                    inFlight.remove(key)
                    if (!request.deferred.isCompleted) request.deferred.cancel()
                }
            }
        }
    }

    private suspend fun fetchAndDecode(key: String, imageKey: String, width: Int): T? {
        val image = try {
            withContext(io) {
                val bytes = fetch(imageKey, width) ?: return@withContext null
                decoder.decode(bytes, width)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
        synchronized(lock) {
            if (image == null) failed += key
        }
        if (image != null) cache.put(key, image)
        return image
    }

    /** Oublie tout (changement d'appareil ou reconnexion). */
    fun clear() {
        synchronized(lock) {
            inFlight.values.forEach { it.deferred.cancel() }
            inFlight.clear()
            failed.clear()
        }
        cache.clear()
    }

    internal val inFlightCount: Int get() = synchronized(lock) { inFlight.size }
    internal val cachedCount: Int get() = cache.count

    companion object {
        /** Largeurs demandées au serveur : quelques paliers, pour partager le cache entre cellules proches. */
        private val BUCKETS = intArrayOf(120, 180, 240, 320, 480, 640, 960)

        fun bucket(widthPx: Int): Int = BUCKETS.firstOrNull { it >= widthPx } ?: BUCKETS.last()

        private fun cacheKey(imageKey: String, widthPx: Int) = "${bucket(widthPx)}|$imageKey"

        /** ~1/8 de la mémoire accordée à l'application. */
        fun defaultCacheBytes(): Long = Runtime.getRuntime().maxMemory() / 8
    }
}
