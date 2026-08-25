package com.slipstream.core.discovery

import com.slipstream.core.net.LocalNetwork
import java.net.InetAddress
import java.net.InetSocketAddress

/**
 * S1: direct-connect to the last-known endpoint for the current network, if any. Spec §5:
 * the common case, expected to resolve in tens of milliseconds.
 */
class CachedEndpointStrategy(
    private val cache: EndpointCache,
    private val probe: PeerProbe,
) : DiscoveryStrategy {

    override val name: String = "cached-endpoint"

    override suspend fun find(network: LocalNetwork): DiscoveredPeer? {
        val cached = cache.get(network.key) ?: return null
        val endpoint = parseEndpoint(cached) ?: return null
        return probe.probe(endpoint)
    }

    private fun parseEndpoint(value: String): InetSocketAddress? {
        val separator = value.lastIndexOf(':')
        if (separator <= 0 || separator == value.length - 1) return null

        val host = value.substring(0, separator)
        val port = value.substring(separator + 1).toIntOrNull() ?: return null
        if (port !in 1..65535) return null

        return try {
            InetSocketAddress(InetAddress.getByName(host), port)
        } catch (e: java.net.UnknownHostException) {
            null
        }
    }
}
