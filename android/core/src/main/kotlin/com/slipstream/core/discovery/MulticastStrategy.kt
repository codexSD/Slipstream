package com.slipstream.core.discovery

import com.slipstream.core.SlipstreamPorts
import com.slipstream.core.identity.DeviceIdentity
import com.slipstream.core.identity.PairedPeerStore
import com.slipstream.core.net.LocalNetwork
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * S3: announce/query on the multicast group, answered by unicast reply from a paired
 * peer. Spec §5: the unicast reply is a fallback for networks where multicast delivery
 * only works in one direction.
 *
 * IMPORTANT: this strategy has exactly one receive loop on its socket. It fans every
 * datagram out to two concerns — the long-lived responder (answers a paired peer's
 * query) and whichever [find] call is currently in progress (via a per-call [Channel]) —
 * from a single [MulticastTransport.receive] call site. Two independent receive loops on
 * one socket is the bug this design exists to avoid: the OS hands each datagram to
 * exactly one pending receive, so two loops nondeterministically steal each other's
 * packets (see docs/superpowers/plans/2026-08-25-core-discovery-control-deviations.md).
 *
 * The socket is open whenever *either* concern needs it, refcounted separately for each:
 *
 *  - **find() bursts** ([find]) open the socket AND acquire the multicast lock, releasing
 *    both the moment the last concurrent burst finishes.
 *  - **the always-on responder** ([startResponder]/[stopResponder], driven by
 *    [com.slipstream.core.SlipstreamPeer] for the whole time the peer is running) opens the
 *    socket only. Spec §5 says "the phone only ever listens and responds", and §5/§14 say the
 *    multicast lock is never held while idle - so idle listening happens *without* the lock.
 *    A phone whose Wi-Fi driver filters unlocked multicast still answers via the unicast
 *    fallback path §5 defines for exactly this case, and a phone that is actively discovering
 *    holds the lock anyway.
 *
 * Both concerns share the same single receive loop; the socket closes only once neither
 * needs it.
 */
class MulticastStrategy(
    private val identity: DeviceIdentity,
    private val pairedPeerStore: PairedPeerStore,
    private val probe: PeerProbe,
    private val transportFactory: () -> MulticastTransport = { UdpMulticastTransport() },
    /** Public so the app-level wiring can be proven, by test, to have supplied a real lock
     * rather than silently inheriting [NoopMulticastLock]. */
    val multicastLock: MulticastLockHandle = NoopMulticastLock,
    private val receiverScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) : DiscoveryStrategy, DiscoveryResponder {

    override val name: String = "multicast"

    private val lifecycleMutex = Mutex()
    private var transport: MulticastTransport? = null
    private var receiveJob: Job? = null
    private var findRefCount = 0
    private var responderRefCount = 0
    private val subscribers = CopyOnWriteArrayList<Channel<DatagramMessage>>()

    override suspend fun find(network: LocalNetwork): DiscoveredPeer? {
        val paired = pairedPeerStore.peer ?: return null

        start(forFind = true)
        val channel = Channel<DatagramMessage>(Channel.UNLIMITED)
        subscribers.add(channel)
        return try {
            val query = PeerAnnouncement(
                v = SlipstreamPorts.PROTOCOL_VERSION,
                deviceId = identity.deviceId,
                name = identity.displayName,
                fingerprint = identity.fingerprint,
                control = SlipstreamPorts.CONTROL,
                kind = "query",
            )
            val activeTransport = transport ?: return null
            activeTransport.send(
                query.toJson().toByteArray(Charsets.UTF_8),
                InetSocketAddress(SlipstreamPorts.MULTICAST_GROUP, SlipstreamPorts.DISCOVERY),
            )

            for (msg in channel) {
                val announcement = PeerAnnouncement.tryParse(String(msg.payload, Charsets.UTF_8)) ?: continue
                if (announcement.kind == "announce" && announcement.fingerprint == paired.fingerprint) {
                    return probe.probe(InetSocketAddress(msg.sender.address, announcement.control))
                }
            }
            null
        } finally {
            // find() may reach here because it was cancelled (e.g. the coordinator
            // cancelled a losing strategy). Cleanup must still run to completion —
            // otherwise a cancelled find() would leak its subscriber channel, leave
            // the socket open, and never release the multicast lock.
            withContext(NonCancellable) {
                subscribers.remove(channel)
                channel.close()
                stop(forFind = true)
            }
        }
    }

    /**
     * Starts (or joins) the long-lived responder: the socket stays bound, and this device
     * answers a paired peer's query, for as long as the peer is running - not merely while
     * one of its own [find] calls happens to be in flight. Without this a PC's multicast
     * query reaches a phone with no bound socket and goes unanswered (spec §5 S3).
     */
    override suspend fun startResponder() {
        start(forFind = false)
    }

    override suspend fun stopResponder() {
        stop(forFind = false)
    }

    private suspend fun start(forFind: Boolean) = lifecycleMutex.withLock {
        val alreadyOpen = findRefCount + responderRefCount > 0
        val takesLock = forFind && findRefCount == 0
        if (takesLock) multicastLock.acquire()
        try {
            if (!alreadyOpen) {
                val newTransport = transportFactory()
                transport = newTransport
                receiveJob = receiverScope.launch { receiveLoop(newTransport) }
            }
        } catch (e: Throwable) {
            // Undo the partial start so the lock isn't leaked and the refcounts stay at
            // 0 for the next attempt to retry cleanly, rather than showing a phantom
            // count with no transport ever set up.
            transport = null
            if (takesLock) multicastLock.release()
            throw e
        }
        if (forFind) findRefCount++ else responderRefCount++
    }

    private suspend fun stop(forFind: Boolean) = lifecycleMutex.withLock {
        if (forFind) {
            findRefCount--
            if (findRefCount <= 0) {
                findRefCount = 0
                // Released as soon as the last *burst* ends, even if the responder keeps the
                // socket open afterwards: idle listening must never hold the lock.
                multicastLock.release()
            }
        } else {
            responderRefCount--
            if (responderRefCount < 0) responderRefCount = 0
        }

        if (findRefCount + responderRefCount <= 0) {
            // Close first: unblocks a socket-level receive() so the loop exits on its
            // own, then join it. Order matters — cancelling first can leave a blocking
            // JDK socket read stuck until the OS notices.
            transport?.close()
            receiveJob?.cancelAndJoin()
            receiveJob = null
            transport = null
        }
    }

    /**
     * The single receive loop. Every datagram is read here exactly once and handed to
     * [handleDatagram], which fans it out to the responder and to subscribers. Nothing
     * else may call [MulticastTransport.receive].
     */
    private suspend fun receiveLoop(transport: MulticastTransport) {
        while (true) {
            val message = try {
                transport.receive()
            } catch (e: Exception) {
                return
            }
            handleDatagram(message, transport)
        }
    }

    private suspend fun handleDatagram(message: DatagramMessage, transport: MulticastTransport) {
        val announcement = PeerAnnouncement.tryParse(String(message.payload, Charsets.UTF_8))
        if (announcement != null) {
            respondIfPairedQuery(announcement, message.sender, transport)
        }

        // Fan-out concern #2: whichever find() call(s) are active see the raw datagram
        // too, regardless of whether the responder above also acted on it.
        subscribers.forEach { it.trySend(message) }
    }

    private suspend fun respondIfPairedQuery(
        announcement: PeerAnnouncement,
        sender: InetSocketAddress,
        transport: MulticastTransport,
    ) {
        val paired = pairedPeerStore.peer ?: return
        if (announcement.kind != "query" || announcement.fingerprint != paired.fingerprint) return

        val reply = PeerAnnouncement(
            v = SlipstreamPorts.PROTOCOL_VERSION,
            deviceId = identity.deviceId,
            name = identity.displayName,
            fingerprint = identity.fingerprint,
            control = SlipstreamPorts.CONTROL,
            kind = "announce",
        )
        try {
            transport.send(reply.toJson().toByteArray(Charsets.UTF_8), sender)
        } catch (e: Exception) {
            // Best-effort unicast reply; a send failure here must not kill the receive loop.
        }
    }
}
