package io.github.jmorille.zapette.discovery

import io.github.jmorille.zapette.model.Device
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.net.InetAddress

/** Résultat d'une résolution DNS-SD (indépendant de NsdServiceInfo). */
data class NsdResolution(val serviceName: String?, val addresses: List<InetAddress>, val port: Int)

/** Accès minimal à NsdManager, remplaçable par un faux dans les tests. */
interface NsdBackend {
    /** Lance la découverte de [serviceType] ; le résultat arrête la découverte à la fermeture. */
    fun startDiscovery(serviceType: String, events: Events): AutoCloseable

    interface Events {
        fun found(service: Service)
        fun lost(name: String)

        /** Échec du démarrage (`onStartDiscoveryFailed`). */
        fun failed(errorCode: Int)
    }

    /** Un service annoncé. */
    interface Service {
        val name: String

        /**
         * Suivi continu (Android 14+) : `null` si non disponible, la résolution ponctuelle
         * [resolve] est alors utilisée.
         */
        fun watch(onUpdate: (NsdResolution) -> Unit, onLost: () -> Unit): AutoCloseable?

        /** Résolution ponctuelle ; `null` en cas d'échec. */
        suspend fun resolve(): NsdResolution?
    }
}

/**
 * Découverte mDNS d'un type de service. [resolveLock] est partagé entre les types : avant
 * Android 14, `resolveService` n'accepte qu'une résolution à la fois. Le délai [resolveTimeoutMs]
 * évite qu'une résolution sans réponse bloque toutes les suivantes.
 */
internal class NsdSource(
    private val backend: NsdBackend,
    private val serviceType: String,
    private val resolveLock: Mutex,
    private val resolveTimeoutMs: Long = DEFAULT_RESOLVE_TIMEOUT_MS,
) : DiscoverySource {

    override fun devices(): Flow<List<Device>> = callbackFlow {
        val tracker = NsdTracker()

        fun onResolved(name: String, resolution: NsdResolution) {
            val device = NsdMapping.preferredHost(resolution.addresses)?.let { host ->
                NsdMapping.toDevice(serviceType, resolution.serviceName ?: name, host, resolution.port)
            }
            tracker.resolved(name, device)?.let { trySend(it) }
        }

        fun onLost(name: String) {
            tracker.lost(name)?.let { trySend(it) }
        }

        val events = object : NsdBackend.Events {
            override fun found(service: NsdBackend.Service) {
                val name = service.name
                if (!tracker.found(name)) return
                // Android 14+ : suivi continu (changement d'adresse) sans la limite d'une résolution à la fois.
                val watcher = runCatching {
                    service.watch(onUpdate = { onResolved(name, it) }, onLost = { onLost(name) })
                }.getOrNull()
                if (watcher != null) {
                    tracker.attach(name, watcher)
                    return
                }
                launch {
                    val resolution = attempt {
                        resolveLock.withLock { withTimeoutOrNull(resolveTimeoutMs) { service.resolve() } }
                    } ?: return@launch
                    onResolved(name, resolution)
                }
            }

            override fun lost(name: String) = onLost(name)

            override fun failed(errorCode: Int) {
                close()
            }
        }

        val discovery = backend.startDiscovery(serviceType, events)
        awaitClose {
            runCatching { discovery.close() }
            tracker.closeAll()
        }
    }
}
