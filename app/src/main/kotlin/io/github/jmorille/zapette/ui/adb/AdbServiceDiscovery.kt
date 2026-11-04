package io.github.jmorille.zapette.ui.adb

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.resume

/**
 * Découverte mDNS (NsdManager, local, sans service tiers) des services publiés par
 * « Débogage sans fil » : `_adb-tls-pairing._tcp` (visible tant que la fenêtre
 * « Associer avec un code » est ouverte) et `_adb-tls-connect._tcp`.
 *
 * Émet la liste courante des services résolus ; s'arrête quand le collecteur s'en va.
 */
class AdbServiceDiscovery(context: Context) {
    private val nsd = context.applicationContext.getSystemService(NsdManager::class.java)

    // Avant Android 14, NsdManager ne résout qu'un service à la fois.
    private val resolving = Mutex()

    fun services(): Flow<List<AdbService>> = callbackFlow {
        val found = LinkedHashMap<String, AdbService>()
        val listeners = listOf(AdbServiceTypes.PAIRING, AdbServiceTypes.CONNECT).map { type ->
            object : NsdManager.DiscoveryListener {
                override fun onServiceFound(info: NsdServiceInfo) {
                    launch {
                        val resolved = resolve(info) ?: return@launch
                        @Suppress("DEPRECATION")
                        val host = resolved.host?.hostAddress ?: return@launch
                        found["$type/${info.serviceName}"] = AdbService(type, info.serviceName, host, resolved.port)
                        trySend(found.values.toList())
                    }
                }

                override fun onServiceLost(info: NsdServiceInfo) {
                    launch {
                        if (found.remove("$type/${info.serviceName}") != null) trySend(found.values.toList())
                    }
                }

                override fun onDiscoveryStarted(serviceType: String) = Unit
                override fun onDiscoveryStopped(serviceType: String) = Unit
                override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
                override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
            }.also { nsd?.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, it) }
        }
        trySend(emptyList())
        awaitClose {
            listeners.forEach { runCatching { nsd?.stopServiceDiscovery(it) } }
        }
    }

    @Suppress("DEPRECATION")
    private suspend fun resolve(info: NsdServiceInfo): NsdServiceInfo? {
        val manager = nsd ?: return null
        return resolving.withLock {
            suspendCancellableCoroutine { cont ->
                manager.resolveService(info, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                        if (cont.isActive) cont.resume(null)
                    }

                    override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                        if (cont.isActive) cont.resume(serviceInfo)
                    }
                })
            }
        }
    }
}
