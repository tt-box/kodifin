package io.github.ttbox.kodifin.discovery

import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import androidx.annotation.RequiresApi
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.Executor
import kotlin.coroutines.resume

/** Câblage NsdManager → [NsdBackend] : traduction des rappels, sans logique propre. */
internal class AndroidNsdBackend(private val nsd: NsdManager) : NsdBackend {

    override fun startDiscovery(serviceType: String, events: NsdBackend.Events): AutoCloseable {
        val listener = object : NsdManager.DiscoveryListener {
            override fun onServiceFound(info: NsdServiceInfo) {
                events.found(Service(info.serviceName ?: return, info))
            }

            override fun onServiceLost(info: NsdServiceInfo) {
                events.lost(info.serviceName ?: return)
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) = events.failed(errorCode)
            override fun onDiscoveryStarted(serviceType: String) = Unit
            override fun onDiscoveryStopped(serviceType: String) = Unit
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
        }
        nsd.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, listener)
        // Lève si la découverte n'a jamais démarré (onStartDiscoveryFailed) : sans conséquence.
        return AutoCloseable { runCatching { nsd.stopServiceDiscovery(listener) } }
    }

    private inner class Service(override val name: String, private val info: NsdServiceInfo) : NsdBackend.Service {
        override fun watch(onUpdate: (NsdResolution) -> Unit, onLost: () -> Unit): AutoCloseable? =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                Api34.watch(nsd, info, onUpdate, onLost)
            } else {
                null
            }

        @Suppress("DEPRECATION")
        override suspend fun resolve(): NsdResolution? = suspendCancellableCoroutine { cont ->
            val listener = object : NsdManager.ResolveListener {
                override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                    if (cont.isActive) cont.resume(serviceInfo.toResolution())
                }

                override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                    if (cont.isActive) cont.resume(null)
                }

                override fun onResolutionStopped(serviceInfo: NsdServiceInfo) {
                    if (cont.isActive) cont.resume(null)
                }
            }
            nsd.resolveService(info, listener)
            // Délai expiré (withTimeoutOrNull de NsdSource) ou découverte arrêtée : la résolution
            // système est arrêtée aussi (Android 14+), sinon la suivante échoue en FAILURE_ALREADY_ACTIVE.
            cont.invokeOnCancellation {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) Api34.stopResolution(nsd, listener)
            }
        }
    }

    /** API Android 14+ isolée pour ne pas charger ces classes sur les versions antérieures. */
    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private object Api34 {
        private val direct = Executor { it.run() }

        fun watch(
            nsd: NsdManager,
            info: NsdServiceInfo,
            onUpdate: (NsdResolution) -> Unit,
            onLost: () -> Unit,
        ): AutoCloseable {
            val callback = object : NsdManager.ServiceInfoCallback {
                // Suivi refusé (ex. trop de rappels enregistrés) : repli sur une résolution
                // ponctuelle, sans la limite d'une résolution à la fois sur Android 14+.
                override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) = resolveOnce(nsd, info, onUpdate)
                override fun onServiceUpdated(serviceInfo: NsdServiceInfo) =
                    onUpdate(NsdResolution(serviceInfo.serviceName, serviceInfo.hostAddresses, serviceInfo.port))

                override fun onServiceLost() = onLost()
                override fun onServiceInfoCallbackUnregistered() = Unit
            }
            nsd.registerServiceInfoCallback(info, direct, callback)
            return AutoCloseable { runCatching { nsd.unregisterServiceInfoCallback(callback) } }
        }

        // Repli volontaire sur l'API dépréciée : seule voie quand l'abonnement (API 34+) est refusé.
        @Suppress("DEPRECATION")
        private fun resolveOnce(nsd: NsdManager, info: NsdServiceInfo, onUpdate: (NsdResolution) -> Unit) {
            runCatching {
                nsd.resolveService(info, direct, object : NsdManager.ResolveListener {
                    override fun onServiceResolved(serviceInfo: NsdServiceInfo) = onUpdate(serviceInfo.toResolution())
                    override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = Unit
                })
            }
        }

        fun stopResolution(nsd: NsdManager, listener: NsdManager.ResolveListener) {
            // Lève si la résolution est déjà terminée : sans conséquence.
            runCatching { nsd.stopServiceResolution(listener) }
        }
    }
}

/** Résolution NSD → [NsdResolution] : toutes les adresses sur Android 14+, une seule avant. */
@Suppress("DEPRECATION")
private fun NsdServiceInfo.toResolution(): NsdResolution =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        NsdResolution(serviceName, hostAddresses, port)
    } else {
        NsdResolution(serviceName, listOfNotNull(host), port)
    }

/** Verrou multicast Wi-Fi réel. */
internal class AndroidMulticastLock(private val lock: WifiManager.MulticastLock) : MulticastLockPort {
    override val isHeld: Boolean get() = lock.isHeld
    override fun acquire() = lock.acquire()
    override fun release() = lock.release()
}
