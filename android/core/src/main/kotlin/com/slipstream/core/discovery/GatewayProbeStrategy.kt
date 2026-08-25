package com.slipstream.core.discovery

import com.slipstream.core.SlipstreamPorts
import com.slipstream.core.net.LocalNetwork
import java.net.InetSocketAddress

/**
 * S2: probe the network's default gateway on the control port. Spec §5: this is the
 * decisive strategy for the primary scenario — when the phone is the hotspot, it *is*
 * the PC's gateway, so the PC resolves the peer deterministically without scanning,
 * multicast, or any AP cooperation.
 */
class GatewayProbeStrategy(
    private val probe: PeerProbe,
) : DiscoveryStrategy {

    override val name: String = "gateway-probe"

    override suspend fun find(network: LocalNetwork): DiscoveredPeer? {
        val gateway = network.gateway ?: return null
        return probe.probe(InetSocketAddress(gateway, SlipstreamPorts.CONTROL))
    }
}
