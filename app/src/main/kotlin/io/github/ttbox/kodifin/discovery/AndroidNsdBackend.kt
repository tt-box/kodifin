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
            nsd.resolveService(info, object : NsdManager.ResolveListener {
                override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                    if (cont.isActive) {
                        cont.resume(NsdResolution(serviceInfo.serviceName, listOfNotNull(serviceInfo.host), serviceInfo.port))
                    }
                }

                override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                    if (cont.isActive) cont.resume(null)
                }
            })
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
                override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) = Unit
                override fun onServiceUpdated(serviceInfo: NsdServiceInfo) =
                    onUpdate(NsdResolution(serviceInfo.serviceName, serviceInfo.hostAddresses, serviceInfo.port))

                override fun onServiceLost() = onLost()
                override fun onServiceInfoCallbackUnregistered() = Unit
            }
            nsd.registerServiceInfoCallback(info, direct, callback)
            return AutoCloseable { runCatching { nsd.unregisterServiceInfoCallback(callback) } }
        }
    }
}

/** Verrou multicast Wi-Fi réel. */
internal class AndroidMulticastLock(private val lock: WifiManager.MulticastLock) : MulticastLockPort {
    override val isHeld: Boolean get() = lock.isHeld
    override fun acquire() = lock.acquire()
    override fun release() = lock.release()
}
