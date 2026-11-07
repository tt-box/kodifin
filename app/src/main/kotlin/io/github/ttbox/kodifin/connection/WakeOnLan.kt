package io.github.ttbox.kodifin.connection

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/**
 * Wake-on-LAN : « paquet magique » UDP (6 octets 0xFF suivis de 16 fois l'adresse MAC),
 * envoyé en diffusion sur le réseau local. Format public (AMD Magic Packet).
 */
object WakeOnLan {
    private val HEX12 = Regex("^[0-9A-Fa-f]{12}$")

    /** Accepte `58:47:CA:7E:0D:48`, `58-47-ca-7e-0d-48`, `5847.CA7E.0D48` ou `5847CA7E0D48`. */
    fun parseMac(text: String): ByteArray? {
        val compact = text.trim().replace(":", "").replace("-", "").replace(".", "")
        if (!HEX12.matches(compact)) return null
        val bytes = ByteArray(6) { i -> compact.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
        // Adresses nulle et de diffusion : aucune carte réseau ne les porte.
        if (bytes.all { it == 0.toByte() } || bytes.all { it == 0xFF.toByte() }) return null
        return bytes
    }

    fun formatMac(mac: ByteArray): String = mac.joinToString(":") { "%02X".format(it.toInt() and 0xFF) }

    fun magicPacket(mac: ByteArray): ByteArray {
        require(mac.size == 6) { "Adresse MAC de 6 octets attendue" }
        return ByteArray(6) { 0xFF.toByte() } + ByteArray(16 * 6) { mac[it % 6] }
    }

    /** Envoi réel ; [repeat] envois car l'UDP en diffusion peut se perdre. */
    suspend fun send(
        target: WakeTarget,
        repeat: Int = 3,
        transmit: (ByteArray, String, Int) -> Unit = ::broadcast,
    ) = withContext(Dispatchers.IO) {
        val packet = magicPacket(target.mac)
        repeat(repeat) { transmit(packet, target.broadcast, target.port) }
    }

    private fun broadcast(payload: ByteArray, address: String, port: Int) {
        DatagramSocket().use { socket ->
            socket.broadcast = true
            socket.send(DatagramPacket(payload, payload.size, InetAddress.getByName(address), port))
        }
    }
}
