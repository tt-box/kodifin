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
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.PortUnreachableException
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.CoroutineContext
import kotlin.random.Random

/**
 * Client du Kodi Event Server (UDP). Au premier envoi : ouverture du socket et HELO ; ensuite
 * un PING toutes les [pingIntervalMs] ms maintient la « connexion » (Kodi oublie un client muet
 * au bout d'une minute) ; [close] envoie BYE. Rien n'est acquitté par Kodi.
 *
 * La fabrique par défaut ([ConnectedUdpSocketFactory]) résout l'hôte une fois (IPv4 de
 * préférence) et connecte le socket : un port fermé (Event Server désactivé) remonte alors une
 * erreur ICMP « port injoignable ». Juste après HELO, une courte écoute de [probeTimeoutMs] ms
 * recueille cette erreur ; détectée là ou à n'importe quel envoi, elle rend le client
 * définitivement indisponible ([isUnavailable]) : l'appelant bascule sur JSON-RPC.
 */
class KodiEventServer(
    host: String,
    private val port: Int = DEFAULT_PORT,
    private val sockets: UdpSocketFactory = ConnectedUdpSocketFactory(),
    private val deviceName: String = "Kodifin",
    private val pingIntervalMs: Long = PING_INTERVAL_MS,
    private val uid: Int = Random.nextInt(),
    private val context: CoroutineContext = Dispatchers.IO,
    private val probeTimeoutMs: Int = PROBE_TIMEOUT_MS,
) : AutoCloseable {

    /** Adresse littérale sans crochets IPv6. */
    private val destination = UdpDestination(host.removePrefix("[").removeSuffix("]"), port)
    private val lock = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + context)

    /** Socket courant ; aussi lu et vidé par [close], hors [lock] (d'où la référence atomique). */
    private val socket = AtomicReference<UdpSocket?>(null)

    @Volatile private var pinger: Job? = null

    @Volatile private var closed = false

    @Volatile private var unavailable = false

    /** Event Server injoignable (port fermé) : inutile d'insister pendant cette session. */
    val isUnavailable: Boolean get() = unavailable

    /** Ouvre le socket, envoie HELO et vérifie l'absence de refus, si ce n'est pas déjà fait. */
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

    private fun unreachable(cause: Throwable? = null) = RemoteException(RemoteError.Unreachable(destination.host), cause)

    private fun interrupted() = RemoteException(RemoteError.Interrupted(destination.host))

    /** Appelé sous [lock]. */
    private suspend fun ensureStarted(): UdpSocket {
        if (closed) throw interrupted()
        if (unavailable) throw unreachable()
        socket.get()?.let { return it }
        val opened = withContext(context) {
            try {
                sockets.open(UdpOptions())
            } catch (e: IOException) {
                throw unreachable(e)
            }
        }
        socket.set(opened)
        // close() concurrent : soit il a vu ce socket (et le ferme), soit on le voit fermé ici.
        if (closed) {
            socket.getAndSet(null)?.let { runCatching { it.close() } }
            throw interrupted()
        }
        transmit(opened, KodiEventPacket.helo(uid, deviceName))
        probe(opened)
        pinger = scope.launch {
            while (isActive) {
                delay(pingIntervalMs)
                lock.withLock {
                    // Un PING perdu n'est pas fatal : le prochain envoi rouvrira au besoin.
                    socket.get()?.let { s -> runCatching { transmit(s, KodiEventPacket.ping(uid)) } }
                }
            }
        }
        return opened
    }

    /**
     * Appelé sous [lock]. Écoute brièvement après HELO : sur un socket connecté, un port fermé
     * se manifeste par [PortUnreachableException]. Le silence (cas normal : Kodi n'acquitte pas)
     * ou toute autre erreur de réception n'est pas concluant.
     */
    private suspend fun probe(s: UdpSocket) {
        if (probeTimeoutMs <= 0) return
        try {
            withContext(context) { s.receive(probeTimeoutMs) }
        } catch (e: PortUnreachableException) {
            markUnavailable()
            throw unreachable(e)
        } catch (_: IOException) {
            // Non concluant : l'Event Server est présumé actif.
        }
    }

    /**
     * Appelé sous [lock]. Un échec ferme le socket (le prochain envoi refera HELO) et lève
     * [RemoteException] pour permettre le repli JSON-RPC ; un port injoignable rend le client
     * définitivement indisponible.
     */
    private suspend fun transmit(s: UdpSocket, data: ByteArray) {
        try {
            withContext(context) { s.send(data, destination) }
        } catch (e: PortUnreachableException) {
            markUnavailable()
            throw unreachable(e)
        } catch (e: IOException) {
            reset()
            throw unreachable(e)
        } catch (e: SecurityException) {
            reset()
            throw unreachable(e)
        }
    }

    private fun markUnavailable() {
        unavailable = true
        reset()
    }

    private fun reset() {
        pinger?.cancel()
        pinger = null
        socket.getAndSet(null)?.let { runCatching { it.close() } }
    }

    /** Envoie BYE (au mieux) et libère le socket ; l'instance n'est plus utilisable ensuite. */
    suspend fun stop() {
        lock.withLock {
            if (closed) return
            closed = true
            pinger?.cancel()
            pinger = null
            socket.getAndSet(null)?.let { s -> withContext(NonCancellable + context) { sayByeAndClose(s) } }
        }
        scope.cancel()
    }

    /**
     * Version non suspendue de [stop], appelable depuis n'importe quel thread (fil principal
     * compris) : ni réseau ni résolution sur l'appelant. Le socket est retiré atomiquement ;
     * BYE part sur [context] (adresse déjà résolue du socket connecté), puis le socket est fermé.
     */
    override fun close() {
        if (closed) return
        closed = true
        scope.cancel()
        pinger = null
        val s = socket.getAndSet(null) ?: return
        CoroutineScope(context + NonCancellable).launch { sayByeAndClose(s) }
    }

    private fun sayByeAndClose(s: UdpSocket) {
        try {
            runCatching { s.send(KodiEventPacket.bye(uid), destination) }
        } finally {
            runCatching { s.close() }
        }
    }

    companion object {
        const val DEFAULT_PORT = 9777
        const val PING_INTERVAL_MS = 45_000L

        /** Écoute après HELO pour recueillir un éventuel refus ICMP (port fermé). */
        const val PROBE_TIMEOUT_MS = 300

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
        fun fromDevice(device: Device, sockets: UdpSocketFactory = ConnectedUdpSocketFactory()): KodiEventServer? =
            if (isEnabled(device)) KodiEventServer(device.host, port(device), sockets) else null
    }
}
