package com.slipstream.core.discovery

import com.slipstream.core.SlipstreamPorts
import java.net.DatagramPacket
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** One UDP datagram received on a [MulticastTransport], with its source address. */
data class DatagramMessage(val payload: ByteArray, val sender: InetSocketAddress)

/**
 * Abstraction over the multicast UDP socket [MulticastStrategy] talks through. Exists so
 * tests can exercise the strategy's single-receive-loop fan-out logic (responder + active
 * find() calls sharing one socket) deterministically, without depending on real OS
 * multicast delivery.
 *
 * Implementations MUST be safe for exactly one concurrent caller of [receive] — that
 * caller is [MulticastStrategy]'s single receive loop. Nothing else may call [receive].
 */
interface MulticastTransport : AutoCloseable {
    suspend fun send(payload: ByteArray, target: InetSocketAddress)

    /** Suspends until the next datagram arrives. Throws once [close] has been called. */
    suspend fun receive(): DatagramMessage

    override fun close()
}

/** Real [MulticastTransport] backed by a JDK [MulticastSocket] bound to the discovery port. */
class UdpMulticastTransport(
    networkInterface: NetworkInterface? = null,
) : MulticastTransport {

    private val socket = MulticastSocket(SlipstreamPorts.DISCOVERY).apply {
        reuseAddress = true
        val group = InetSocketAddress(SlipstreamPorts.MULTICAST_GROUP, SlipstreamPorts.DISCOVERY)
        if (networkInterface != null) {
            joinGroup(group, networkInterface)
        } else {
            joinGroup(group, null)
        }
    }

    override suspend fun send(payload: ByteArray, target: InetSocketAddress) {
        withContext(Dispatchers.IO) {
            socket.send(DatagramPacket(payload, payload.size, target))
        }
    }

    override suspend fun receive(): DatagramMessage = withContext(Dispatchers.IO) {
        val buffer = ByteArray(2048)
        val packet = DatagramPacket(buffer, buffer.size)
        socket.receive(packet)
        DatagramMessage(
            payload = packet.data.copyOfRange(packet.offset, packet.offset + packet.length),
            sender = InetSocketAddress(packet.address, packet.port),
        )
    }

    override fun close() {
        socket.close()
    }
}
