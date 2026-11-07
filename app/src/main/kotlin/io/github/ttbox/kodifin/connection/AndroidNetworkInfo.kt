package io.github.ttbox.kodifin.connection

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat

/**
 * État réseau lu via [ConnectivityManager] et [NetworkCapabilities]. Le SSID n'est visible
 * qu'avec ACCESS_FINE_LOCATION accordée à l'exécution et la localisation activée ; sinon
 * Android renvoie `<unknown ssid>` et [currentSsid] vaut `null`.
 */
class AndroidNetworkInfo(context: Context) : NetworkInfo {
    private val context = context.applicationContext
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val wifi = context.applicationContext.getSystemService(WifiManager::class.java)
    private val location = context.getSystemService(LocationManager::class.java)

    private fun wifiCapabilities(): NetworkCapabilities? {
        val caps = connectivity?.getNetworkCapabilities(connectivity.activeNetwork) ?: return null
        return caps.takeIf { it.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) }
    }

    override fun isWifiConnected(): Boolean = wifiCapabilities() != null

    fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    fun isLocationEnabled(): Boolean = location != null && LocationManagerCompat.isLocationEnabled(location)

    override fun canReadSsid(): Boolean = hasLocationPermission() && isLocationEnabled()

    override fun currentSsid(): String? {
        val caps = wifiCapabilities() ?: return null
        if (!hasLocationPermission()) return null
        // Android 12+ : WifiInfo porté par les capacités du réseau (SSID présent seulement si
        // l'application a la localisation) ; à défaut, ancienne API WifiManager.
        val fromCaps = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (caps.transportInfo as? WifiInfo)?.ssid
        } else {
            null
        }
        return NetworkGuard.normalizeSsid(fromCaps) ?: legacySsid()
    }

    @Suppress("DEPRECATION")
    private fun legacySsid(): String? = NetworkGuard.normalizeSsid(wifi?.connectionInfo?.ssid)
}
