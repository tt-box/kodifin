package io.github.ttbox.kodifin.kodi

import io.github.ttbox.kodifin.model.UdpDatagram
import io.github.ttbox.kodifin.model.UdpDestination
import io.github.ttbox.kodifin.model.UdpOptions
import io.github.ttbox.kodifin.model.UdpSocket
import io.github.ttbox.kodifin.model.UdpSocketFactory
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * Sockets UDP « connectés » pour l'Event Server : l'hôte est résolu une seule fois (IPv4 de
 * préférence, voir [preferIpv4]) puis le socket est lié à cette adresse. Un socket connecté
 * remonte les erreurs ICMP : un Event Server désactivé (port fermé) se traduit par une
 * [java.net.PortUnreachableException] à l'envoi ou à la réception suivante, au lieu de touches
 * perdues en silence. [resolve] est injectable pour les tests.
 */
internal class ConnectedUdpSocketFactory(
    private val resolve: (String) -> List<InetAddress> = { InetAddress.getAllByName(it).toList() },
) : UdpSocketFactory {
    override fun open(options: UdpOptions): UdpSocket = ConnectedUdpSocket(resolve)
}

/**
 * Première adresse IPv4 de [addresses], sinon la première tout court : Kodi n'écoute souvent
 * qu'en IPv4 sur le port de l'Event Server, alors qu'un nom peut aussi résoudre en IPv6.
 */
internal fun preferIpv4(addresses: List<InetAddress>): InetAddress? =
    addresses.firstOrNull { it is Inet4Address } ?: addresses.firstOrNull()

/** Socket connecté à la première destination utilisée ; les envois suivants y vont directement. */
private class ConnectedUdpSocket(private val resolve: (String) -> List<InetAddress>) : UdpSocket {
    private val socket = DatagramSocket()
    private val buffer = ByteArray(MAX_DATAGRAM)

    @Volatile private var target: UdpDestination? = null

    @Synchronized
    private fun connectTo(destination: UdpDestination) {
        if (target == destination) return
        val address = try {
            preferIpv4(resolve(destination.host))
        } catch (e: SecurityException) {
            throw IOException("Résolution refusée", e)
        } ?: throw UnknownHostException(destination.host)
        socket.connect(InetSocketAddress(address, destination.port))
        target = destination
    }

    override fun send(data: ByteArray, destination: UdpDestination) {
        connectTo(destination)
        // Socket connecté : ni adresse ni résolution par paquet.
        socket.send(DatagramPacket(data, data.size))
    }

    override fun receive(timeoutMs: Int): UdpDatagram? {
        socket.soTimeout = timeoutMs.coerceAtLeast(1)
        val packet = DatagramPacket(buffer, buffer.size)
        try {
            socket.receive(packet)
        } catch (_: SocketTimeoutException) {
            return null
        }
        return UdpDatagram(packet.data.copyOfRange(packet.offset, packet.offset + packet.length), packet.address?.hostAddress)
    }

    override fun close() = socket.close()

    companion object {
        const val MAX_DATAGRAM = 2048
    }
}
