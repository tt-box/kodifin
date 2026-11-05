package io.github.jmorille.zapette.bluetooth

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothHidDeviceAppSdpSettings
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Profil Bluetooth HID Device d'Android (API 28+) : le téléphone se présente comme un clavier
 * avec touches multimédia. Spécification publique Bluetooth SIG (HID Profile), aucun protocole
 * propriétaire. Toutes les entrées vérifient la permission avant d'appeler la pile.
 */
@SuppressLint("MissingPermission")
class AndroidHidKeyboard(context: Context) : HidKeyboardPort {
    private val context = context.applicationContext
    private val adapter: BluetoothAdapter? = context.getSystemService(BluetoothManager::class.java)?.adapter
    private val state = MutableStateFlow(HidLinkState())
    override val link: StateFlow<HidLinkState> = state.asStateFlow()

    private var hid: BluetoothHidDevice? = null
    private var host: BluetoothDevice? = null
    private var requested = false

    override fun availability(): HidAvailability = when {
        Build.VERSION.SDK_INT < Build.VERSION_CODES.P -> HidAvailability.UNSUPPORTED
        adapter == null -> HidAvailability.NO_BLUETOOTH
        !hasPermission() -> HidAvailability.NO_PERMISSION
        !adapter.isEnabled -> HidAvailability.BLUETOOTH_OFF
        else -> HidAvailability.READY
    }

    private fun hasPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    override fun start() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P || availability() != HidAvailability.READY) return
        if (requested) return
        requested = true
        val ok = adapter?.getProfileProxy(context, listener, BluetoothProfile.HID_DEVICE) == true
        if (!ok) {
            requested = false
            state.update { it.copy(error = "Profil clavier Bluetooth indisponible sur cet appareil") }
        }
    }

    private val listener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
            val device = proxy as BluetoothHidDevice
            hid = device
            register(device)
        }

        override fun onServiceDisconnected(profile: Int) {
            hid = null
            host = null
            state.value = HidLinkState()
        }
    }

    private fun register(device: BluetoothHidDevice) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P || !hasPermission()) return
        val sdp = BluetoothHidDeviceAppSdpSettings(
            "Zapette",
            "Télécommande clavier",
            "Zapette",
            BluetoothHidDevice.SUBCLASS1_COMBO,
            HidReports.DESCRIPTOR,
        )
        val ok = runCatching { device.registerApp(sdp, null, null, context.mainExecutor, callback) }.getOrDefault(false)
        if (!ok) state.update { it.copy(error = "Déclaration du clavier refusée par la pile Bluetooth") }
    }

    private val callback = object : BluetoothHidDevice.Callback() {
        override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, registered: Boolean) {
            state.update { it.copy(registered = registered, error = null) }
        }

        override fun onConnectionStateChanged(device: BluetoothDevice, newState: Int) {
            val h = HidHost(nameOf(device), device.address)
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    host = device
                    state.update { it.copy(connected = h, connecting = null, error = null) }
                }
                BluetoothProfile.STATE_CONNECTING -> state.update { it.copy(connecting = h) }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    if (host?.address == device.address) host = null
                    state.update {
                        it.copy(
                            connected = it.connected?.takeIf { c -> c.address != device.address },
                            connecting = it.connecting?.takeIf { c -> c.address != device.address },
                        )
                    }
                }
            }
        }

        override fun onGetReport(device: BluetoothDevice, type: Byte, id: Byte, bufferSize: Int) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P || !hasPermission()) return
            // L'hôte demande l'état courant : aucune touche enfoncée.
            val report = when (id.toInt()) {
                HidReports.CONSUMER_REPORT_ID -> ByteArray(2)
                else -> ByteArray(8)
            }
            hid?.replyReport(device, type, id, report)
        }
    }

    private fun nameOf(device: BluetoothDevice): String =
        (if (hasPermission()) runCatching { device.name }.getOrNull() else null)?.takeIf { it.isNotBlank() } ?: device.address

    override fun stop() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
        val device = hid
        if (device != null && hasPermission()) {
            runCatching { device.unregisterApp() }
            adapter?.closeProfileProxy(BluetoothProfile.HID_DEVICE, device)
        }
        hid = null
        host = null
        requested = false
        state.value = HidLinkState()
    }

    override fun bondedHosts(): List<HidHost> {
        if (!hasPermission()) return emptyList()
        val bonded = runCatching { adapter?.bondedDevices }.getOrNull().orEmpty()
        return bonded.map { HidHost(nameOf(it), it.address) }.sortedBy { it.name.lowercase() }
    }

    override fun connect(address: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P || !hasPermission()) return
        val device = runCatching { adapter?.getRemoteDevice(address) }.getOrNull() ?: return
        val ok = runCatching { hid?.connect(device) }.getOrNull() == true
        if (ok) {
            state.update { it.copy(connecting = HidHost(nameOf(device), address), error = null) }
        } else {
            state.update { it.copy(error = "Connexion à ${nameOf(device)} refusée") }
        }
    }

    override fun disconnect() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P || !hasPermission()) return
        host?.let { runCatching { hid?.disconnect(it) } }
    }

    override fun send(report: HidReport): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P || !hasPermission()) return false
        val target = host ?: return false
        return runCatching { hid?.sendReport(target, report.id, report.data) }.getOrNull() == true
    }
}
