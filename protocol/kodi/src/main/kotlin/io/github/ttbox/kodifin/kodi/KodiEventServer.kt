package io.github.ttbox.kodifin.kodi

import io.github.ttbox.kodifin.model.Device
import io.github.ttbox.kodifin.model.RemoteError
import io.github.ttbox.kodifin.model.RemoteException
import io.github.ttbox.kodifin.model.UdpDestination
import io.github.ttbox.kodifin.model.UdpOptions
import io.github.ttbox.kodifin.model.UdpSocket
import io.github.ttbox.kodifin.model.UdpSocketFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import kotlin.coroutines.CoroutineContext
import kotlin.random.Random

/**
 * Client du Kodi Event Server (UDP). Au premier envoi : ouverture du socket et HELO ; ensuite
 * un PING toutes les [pingIntervalMs] ms maintient la « connexion » (Kodi oublie un client muet
 * au bout d'une minute) ; [close] envoie BYE. Rien n'est attendu en retour : UDP n'acquitte pas,
 * seul un échec d'envoi local (hôte injoignable, réseau absent) est détecté.
 */
class KodiEventServer(
    host: String,
    private val port: Int = DEFAULT_PORT,
    private val sockets: UdpSocketFactory = UdpSocketFactory.Default,
    private val deviceName: String = "Kodifin",
    private val pingIntervalMs: Long = PING_INTERVAL_MS,
    private val uid: Int = Random.nextInt(),
    private val context: CoroutineContext = Dispatchers.IO,
) : AutoCloseable {

    /** Adresse littérale sans crochets IPv6. */
    private val destination = UdpDestination(host.removePrefix("[").removeSuffix("]"), port)
    private val lock = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + context)
    private var socket: UdpSocket? = null
    private var pinger: Job? = null

    @Volatile private var closed = false

    /** Ouvre le socket et envoie HELO si ce n'est pas déjà fait. */
    suspend fun start() {
        lock.withLock { ensureStarted() }
    }

    /** Envoie une touche par son nom de table Kodi (`KB` clavier, `R1` télécommande…). */
    suspend fun sendButton(name: String, map: String = MAP_KEYBOARD, flags: Int = KodiEventPacket.ButtonFlags.TAP) {
        send { KodiEventPacket.buttonByName(uid, map, name, flags) }
    }

    /** Envoie une touche par son code numérique. */
    suspend fun sendButtonCode(code: Int, map: String = "", flags: Int = KodiEventPacket.ButtonFlags.TAP) {
        send { KodiEventPacket.buttonByCode(uid, code, map, flags) }
    }

    private suspend fun send(packet: () -> ByteArray) {
        lock.withLock {
            val s = ensureStarted()
            transmit(s, packet())
        }
    }

    /** Appelé sous [lock]. */
    private suspend fun ensureStarted(): UdpSocket {
        if (closed) throw RemoteException(RemoteError.Interrupted(destination.host))
        socket?.let { return it }
        val opened = withContext(context) {
            try {
                sockets.open(UdpOptions())
            } catch (e: IOException) {
                throw RemoteException(RemoteError.Unreachable(destination.host), e)
            }
        }
        socket = opened
        transmit(opened, KodiEventPacket.helo(uid, deviceName))
        pinger = scope.launch {
            while (isActive) {
                delay(pingIntervalMs)
                lock.withLock {
                    // Un PING perdu n'est pas fatal : le prochain envoi rouvrira au besoin.
                    socket?.let { s -> runCatching { transmit(s, KodiEventPacket.ping(uid)) } }
                }
            }
        }
        return opened
    }

    /**
     * Appelé sous [lock]. Un échec ferme le socket (le prochain envoi refera HELO) et lève
     * [RemoteException] pour permettre le repli JSON-RPC.
     */
    private suspend fun transmit(s: UdpSocket, data: ByteArray) {
        try {
            withContext(context) { s.send(data, destination) }
        } catch (e: IOException) {
            reset()
            throw RemoteException(RemoteError.Unreachable(destination.host), e)
        } catch (e: SecurityException) {
            reset()
            throw RemoteException(RemoteError.Unreachable(destination.host), e)
        }
    }

    private fun reset() {
        pinger?.cancel()
        pinger = null
        socket?.let { runCatching { it.close() } }
        socket = null
    }

    /** Envoie BYE (au mieux) et libère le socket ; l'instance n'est plus utilisable ensuite. */
    suspend fun stop() {
        lock.withLock {
            if (closed) return
            closed = true
            socket?.let { s -> runCatching { withContext(context) { s.send(KodiEventPacket.bye(uid), destination) } } }
            reset()
        }
        scope.cancel()
    }

    /** Version non suspendue de [stop] (BYE envoyé sur le thread appelant). */
    override fun close() {
        if (closed) return
        closed = true
        scope.cancel()
        socket?.let { s -> runCatching { s.send(KodiEventPacket.bye(uid), destination) } }
        reset()
    }

    companion object {
        const val DEFAULT_PORT = 9777
        const val PING_INTERVAL_MS = 45_000L
        const val MAP_KEYBOARD = "KB"
        const val MAP_REMOTE = "R1"

        /** Extra « Event Server activé » (valeur `true`) et port associé. */
        const val EXTRA_ENABLED = "kodi_eventserver"
        const val EXTRA_PORT = "kodi_eventserver_port"

        /** Port configuré, ou [DEFAULT_PORT] s'il est absent ou invalide. */
        fun port(device: Device): Int =
            device.extras[EXTRA_PORT]?.trim()?.toIntOrNull()?.takeIf { it in 1..65_535 } ?: DEFAULT_PORT

        fun isEnabled(device: Device): Boolean = device.extras[EXTRA_ENABLED]?.trim().equals("true", ignoreCase = true)

        /** Client Event Server pour [device], ou `null` si l'option n'est pas activée. */
        fun fromDevice(device: Device, sockets: UdpSocketFactory = UdpSocketFactory.Default): KodiEventServer? =
            if (isEnabled(device)) KodiEventServer(device.host, port(device), sockets) else null
    }
}
