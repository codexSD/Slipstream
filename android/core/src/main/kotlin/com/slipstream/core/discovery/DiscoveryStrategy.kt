package com.slipstream.core.discovery

import com.slipstream.core.net.LocalNetwork
import java.net.InetSocketAddress

/**
 * A peer found by a discovery strategy. Address-only for now: the actual TLS handshake
 * that upgrades an address into a verified peer identity is Task 6/12's job — this task
 * only defines the shape strategies and [PeerProbe] implementations produce.
 */
data class DiscoveredPeer(val endpoint: InetSocketAddress)

/**
 * Attempts to turn a candidate endpoint into a [DiscoveredPeer], typically by performing
 * (or, until Task 6 lands, simulating) a TLS handshake and fingerprint check against it.
 * Returns null if the endpoint isn't reachable or isn't a Slipstream peer.
 *
 * Strategies never construct [DiscoveredPeer] themselves — they only ever identify
 * candidate addresses and hand them to the injected [PeerProbe].
 */
fun interface PeerProbe {
    suspend fun probe(endpoint: InetSocketAddress): DiscoveredPeer?
}

/**
 * One of the four ways Slipstream locates a peer on the local network (spec §5: cached
 * endpoint, gateway probe, multicast, subnet sweep). [DiscoveryCoordinator] races all
 * strategies concurrently and returns the first to succeed.
 */
interface DiscoveryStrategy {
    val name: String

    suspend fun find(network: LocalNetwork): DiscoveredPeer?
}
