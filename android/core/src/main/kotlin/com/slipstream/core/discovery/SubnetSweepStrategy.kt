package com.slipstream.core.discovery

import com.slipstream.core.SlipstreamPorts
import com.slipstream.core.net.LocalNetwork
import com.slipstream.core.net.SubnetMath
import java.net.InetSocketAddress
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * S4: parallel sweep of every host in the local /24, probing each on the control port.
 * Spec §5: backstop for access points that drop multicast. Bounded to a /24 by
 * [SubnetMath.enumerateHosts] (empty for anything wider), and to [maxConcurrentProbes]
 * simultaneous probes by a [Semaphore] so a sweep never opens 65k sockets at once.
 */
class SubnetSweepStrategy(
    private val probe: PeerProbe,
    private val maxConcurrentProbes: Int = 254,
) : DiscoveryStrategy {

    override val name: String = "subnet-sweep"

    override suspend fun find(network: LocalNetwork): DiscoveredPeer? = coroutineScope {
        val hosts = SubnetMath.enumerateHosts(network.localAddress, network.prefixLength).toList()
        if (hosts.isEmpty()) return@coroutineScope null

        val semaphore = Semaphore(maxConcurrentProbes)
        val result = CompletableDeferred<DiscoveredPeer?>()

        val jobs = hosts.map { host ->
            launch {
                semaphore.withPermit {
                    if (result.isCompleted) return@withPermit
                    val found = try {
                        probe.probe(InetSocketAddress(host, SlipstreamPorts.CONTROL))
                    } catch (e: Exception) {
                        null
                    }
                    if (found != null) {
                        result.complete(found)
                    }
                }
            }
        }

        launch {
            jobs.joinAll()
            result.complete(null)
        }

        val found = result.await()
        jobs.forEach { it.cancel() }
        found
    }
}
