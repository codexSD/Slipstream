package com.slipstream.core.pairing

import com.slipstream.core.SlipstreamPorts
import com.slipstream.core.discovery.MulticastLockHandle
import com.slipstream.core.discovery.MulticastTransport
import com.slipstream.core.discovery.NoopMulticastLock
import com.slipstream.core.discovery.PeerAnnouncement
import com.slipstream.core.discovery.UdpMulticastTransport
import com.slipstream.core.identity.DeviceIdentity
import com.slipstream.core.net.LanGuard
import com.slipstream.core.net.LocalNetwork
import com.slipstream.core.net.NetworkInfo
import com.slipstream.core.net.SubnetMath
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.coroutineContext
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull

/** A candidate peer found by [PairingDiscovery]: address-only plus whatever the (untrusted,
 * unverified) announcement claims about itself. TLS + [PairingCoordinator] are what actually
 * verify anything. A candidate found by probing rather than by announcement carries no
 * claimed device id or name at all — those arrive in the peer's `pair.offer`. */
data class PairingCandidate(
    val deviceId: String,
    val name: String,
    val fingerprint: String,
    val endpoint: InetSocketAddress,
)

/**
 * Answers "is there a device here that could pair with us?". Connects unpinned — there is
 * nothing to pin against until pairing completes — and returns null for any unreachable host,
 * which during a sweep is the normal answer, not an error.
 */
fun interface PairingProbe {
    suspend fun probe(endpoint: InetSocketAddress): PairingCandidate?
}

/**
 * pairing.md §6: while a [PairingWindow] is open, looks for a peer belonging to *any* device -
 * not just an already-paired one, since finding an unpaired peer is the whole point of pairing
 * discovery. Outside an open window, [find] returns null immediately without listening or
 * probing at all: no socket is opened, no lock is acquired, no address is contacted.
 *
 * Races the same strategy ladder as [com.slipstream.core.discovery.DiscoveryCoordinator], and
 * for the same reason: multicast is not reliable enough to be anyone's only strategy. The
 * gateway probe is decisive on the product's primary topology (spec §1, phone hotspot), where
 * the phone is the PC's default gateway and an Android softAP delivers no multicast at all -
 * measured on real hardware as zero datagrams in eight seconds against a control port that
 * answers a TLS handshake in tens of milliseconds. The subnet sweep is the backstop for access
 * points that drop multicast without being anyone's gateway.
 *
 * The one difference from paired discovery is the trust filter, and it is deliberate: paired
 * discovery requires a fingerprint match, while pairing discovery accepts any peer. The
 * six-digit code, compared by two humans, is what establishes trust here (pairing.md §5) - so
 * this ladder produces only a *candidate*. It never pairs and never persists anything;
 * [PairingCoordinator] owns mutual confirmation.
 *
 * Still applies spec §11 LAN-only filtering ([LanGuard]) to every strategy, and never surfaces
 * the local device's own announcement.
 *
 * Kept symmetric with the Windows `PairingDiscovery` — a ladder on one side only means pairing
 * works in one direction only.
 */
class PairingDiscovery(
    private val identity: DeviceIdentity,
    private val window: PairingWindow,
    private val transportFactory: () -> MulticastTransport = { UdpMulticastTransport() },
    private val multicastLock: MulticastLockHandle = NoopMulticastLock,
    private val networkInfo: NetworkInfo? = null,
    private val probe: PairingProbe? = null,
    private val sweepProbe: PairingProbe? = probe,
    private val maxConcurrentProbes: Int = 254,
) {
    suspend fun find(timeout: Duration = 5.seconds): PairingCandidate? {
        if (!window.isOpen) return null

        val network = networkInfo?.current()

        multicastLock.acquire()
        val transport = transportFactory()

        // Deliberately not a `coroutineScope`: the multicast arm parks in a blocking socket
        // receive that only `transport.close()` can wake, so this must be able to abandon its
        // children rather than join them. Cancel, then close, then return.
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val result = CompletableDeferred<PairingCandidate?>()

        try {
            val arms = mutableListOf<suspend () -> PairingCandidate?>(
                { fromMulticast(transport) },
            )
            if (network != null && probe != null) arms += { fromGateway(network) }
            if (network != null && (sweepProbe ?: probe) != null) arms += { fromSweep(network) }

            // Once every arm has come up empty there is nothing left to wait for; without
            // this the caller would sit out the full timeout after the ladder is exhausted.
            val outstanding = AtomicInteger(arms.size)

            arms.forEach { arm ->
                scope.launch {
                    val found = try {
                        arm()
                    } catch (e: Exception) {
                        // A strategy that throws is a strategy that found nothing: one
                        // adapter's firewall policy must not take pairing discovery down.
                        null
                    }
                    if (found != null) result.complete(found)
                    if (outstanding.decrementAndGet() == 0) result.complete(null)
                }
            }

            // The window can close mid-search (user cancelled, or 120 s elapsed). Nothing
            // else here polls, so this arm ends the race the moment the gate shuts.
            scope.launch {
                while (window.isOpen) delay(WINDOW_POLL)
                result.complete(null)
            }

            val found = withTimeoutOrNull(timeout) { result.await() }

            // A result is only a result while the window is still open.
            return if (window.isOpen) found else null
        } finally {
            scope.cancel()
            transport.close()
            multicastLock.release()
        }
    }

    /** S3: announce/query on the multicast group and take the first announcement from anyone
     * that is not us. An announcement already carries the peer's claimed identity, so unlike
     * the probing arms this one needs no connection to produce a candidate. */
    private suspend fun fromMulticast(transport: MulticastTransport): PairingCandidate? {
        val query = PeerAnnouncement(
            v = SlipstreamPorts.PROTOCOL_VERSION,
            deviceId = identity.deviceId,
            name = identity.displayName,
            fingerprint = identity.fingerprint,
            control = SlipstreamPorts.CONTROL,
            kind = "query",
        )
        transport.send(
            query.toJson().toByteArray(Charsets.UTF_8),
            InetSocketAddress(SlipstreamPorts.MULTICAST_GROUP, SlipstreamPorts.DISCOVERY),
        )

        while (window.isOpen) {
            val message = transport.receive()
            if (!LanGuard.isLocal(message.sender.address)) continue

            val announcement = PeerAnnouncement.tryParse(String(message.payload, Charsets.UTF_8)) ?: continue
            if (announcement.kind != "announce") continue
            if (announcement.deviceId == identity.deviceId) continue

            return PairingCandidate(
                deviceId = announcement.deviceId,
                name = announcement.name,
                fingerprint = announcement.fingerprint,
                endpoint = InetSocketAddress(message.sender.address, announcement.control),
            )
        }
        return null
    }

    /** S2: one probe at the default gateway. Decisive in hotspot mode, where the peer *is*
     * the gateway, and free everywhere else. */
    private suspend fun fromGateway(network: LocalNetwork): PairingCandidate? {
        val gateway = network.gateway ?: return null
        if (!LanGuard.isLocal(gateway)) return null
        if (!window.isOpen) return null

        return probe?.probe(InetSocketAddress(gateway, SlipstreamPorts.CONTROL))
    }

    /** S4: the backstop. Bounded to a /24 by [SubnetMath.enumerateHosts] (empty for anything
     * wider) and to [maxConcurrentProbes] simultaneous probes. */
    private suspend fun fromSweep(network: LocalNetwork): PairingCandidate? {
        val sweep = sweepProbe ?: probe ?: return null

        val hosts = SubnetMath.enumerateHosts(network.localAddress, network.prefixLength)
            .filter { it != network.localAddress }
            .toList()
        if (hosts.isEmpty()) return null
        if (!window.isOpen) return null

        val semaphore = Semaphore(maxConcurrentProbes)
        val winner = CompletableDeferred<PairingCandidate?>()
        val remaining = AtomicInteger(hosts.size)

        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        try {
            hosts.forEach { host ->
                scope.launch {
                    semaphore.withPermit {
                        // Re-checked per host: a window that closes halfway through a 254-way
                        // sweep must stop the remaining probes, not merely discard their
                        // results.
                        if (!winner.isCompleted && window.isOpen) {
                            val found = try {
                                sweep.probe(InetSocketAddress(host, SlipstreamPorts.CONTROL))
                            } catch (e: Exception) {
                                null
                            }
                            if (found != null) winner.complete(found)
                        }
                    }
                    if (remaining.decrementAndGet() == 0) winner.complete(null)
                }
            }
            return winner.await()
        } finally {
            scope.cancel()
        }
    }

    private companion object {
        /** How often to re-check that the window is still open while a search is running. */
        val WINDOW_POLL = 100.milliseconds
    }
}
