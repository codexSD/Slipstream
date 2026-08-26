package com.slipstream.core.discovery

import com.slipstream.core.SlipstreamLog
import com.slipstream.core.net.NetworkInfo
import kotlin.time.Duration
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull

/** The outcome of a successful [DiscoveryCoordinator.discover] call. */
data class DiscoveryResult(val strategyName: String, val peer: DiscoveredPeer)

/**
 * Races every [DiscoveryStrategy] against the current network concurrently and returns
 * the first successful result, cancelling the rest. A strategy that throws (e.g. because
 * multicast is blocked on this device) is treated as "found nothing" rather than taking
 * discovery down with it. On success, the winning peer's endpoint is remembered in
 * [cache] under the current network's key, so a future S1 [CachedEndpointStrategy] lookup
 * can skip straight to it.
 */
class DiscoveryCoordinator(
    private val networkInfo: NetworkInfo,
    private val cache: EndpointCache?,
    private val strategies: List<DiscoveryStrategy>,
) {

    suspend fun discover(timeout: Duration): DiscoveryResult? {
        val network = networkInfo.current()
        if (network == null) {
            // Not a rare edge case: this is what "no peer found" looks like when the app has
            // simply picked no interface, which is indistinguishable from a peer that isn't
            // there unless it is said out loud.
            SlipstreamLog.i("discovery", "no usable local network — nothing to search on")
            return null
        }
        SlipstreamLog.i(
            "discovery",
            "searching ${network.localAddress.hostAddress}/${network.prefixLength} " +
                "(${network.key}) with ${strategies.size} strategies: " +
                strategies.joinToString(",") { it.name },
        )

        return coroutineScope {
            var pending = strategies.map { strategy ->
                strategy to async {
                    try {
                        withTimeoutOrNull(timeout) { strategy.find(network) }
                            .also { SlipstreamLog.i("discovery", "${strategy.name}: ${it ?: "nothing"}") }
                    } catch (e: Exception) {
                        SlipstreamLog.w("discovery", "${strategy.name} threw", e)
                        null
                    }
                }
            }

            var winner: DiscoveryResult? = null
            while (pending.isNotEmpty() && winner == null) {
                val (strategy, deferred, peer) = select<Triple<DiscoveryStrategy, Deferred<DiscoveredPeer?>, DiscoveredPeer?>> {
                    pending.forEach { (s, d) ->
                        d.onAwait { value -> Triple(s, d, value) }
                    }
                }
                pending = pending.filterNot { it.second === deferred }
                if (peer != null) {
                    winner = DiscoveryResult(strategy.name, peer)
                }
            }

            // Cancel every strategy that didn't win (or didn't finish in time).
            pending.forEach { (_, deferred) -> deferred.cancel() }

            winner?.let { result ->
                cache?.put(network.key, "${result.peer.endpoint.hostString}:${result.peer.endpoint.port}")
            }

            winner
        }
    }
}
