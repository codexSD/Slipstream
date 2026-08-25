package com.slipstream.core.discovery

import com.slipstream.core.SlipstreamPorts
import com.slipstream.core.identity.DeviceIdentity
import com.slipstream.core.identity.PairedPeer
import com.slipstream.core.identity.PairedPeerStore
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.createTempDirectory
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.slipstream.core.net.LocalNetwork

/**
 * An in-memory stand-in for a LAN, so tests can exercise [MulticastStrategy]'s
 * single-receive-loop fan-out without depending on real OS multicast delivery.
 * A send to [SlipstreamPorts.MULTICAST_GROUP] fans out to every other registered
 * transport; any other target is delivered only to the transport registered at
 * that exact address, i.e. a unicast reply.
 */
private class FakeBus {
    private val transports = ConcurrentHashMap<InetSocketAddress, FakeMulticastTransport>()

    fun register(transport: FakeMulticastTransport) {
        transports[transport.selfAddress] = transport
    }

    fun unregister(transport: FakeMulticastTransport) {
        transports.remove(transport.selfAddress)
    }

    suspend fun deliver(payload: ByteArray, target: InetSocketAddress, sender: InetSocketAddress) {
        if (target.address == SlipstreamPorts.MULTICAST_GROUP) {
            transports.values
                .filter { it.selfAddress != sender }
                .forEach { it.inbox.send(DatagramMessage(payload, sender)) }
        } else {
            transports[target]?.inbox?.send(DatagramMessage(payload, sender))
        }
    }
}

private class FakeMulticastTransport(
    val selfAddress: InetSocketAddress,
    private val bus: FakeBus,
) : MulticastTransport {
    val inbox = Channel<DatagramMessage>(Channel.UNLIMITED)

    init {
        bus.register(this)
    }

    override suspend fun send(payload: ByteArray, target: InetSocketAddress) {
        bus.deliver(payload, target, selfAddress)
    }

    override suspend fun receive(): DatagramMessage = inbox.receive()

    override fun close() {
        bus.unregister(this)
        inbox.close()
    }
}

private class CountingMulticastLock : MulticastLockHandle {
    var acquireCount = 0
    var releaseCount = 0
    val heldAfterFirstAcquire get() = acquireCount > releaseCount

    override fun acquire() {
        acquireCount++
    }

    override fun release() {
        releaseCount++
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class MulticastStrategyTest {

    private val network = LocalNetwork(
        localAddress = InetAddress.getByName("192.168.1.5"),
        gateway = InetAddress.getByName("192.168.1.1"),
        prefixLength = 24,
        key = "net-1|192.168.1.0/24",
    )

    @Test
    fun `one receive loop serves the responder and an in-progress find concurrently`() = runTest {
        val dirA = createTempDirectory().toFile()
        try {
            val identityA = DeviceIdentity.createNew("Device A")
            val identityB = DeviceIdentity.createNew("Device B")

            val pairedStoreA = PairedPeerStore(dirA)
            pairedStoreA.store(PairedPeer(identityB.deviceId, identityB.fingerprint, identityB.certificate))

            val bus = FakeBus()
            val addrA = InetSocketAddress(InetAddress.getByName("10.0.0.1"), SlipstreamPorts.DISCOVERY)
            val addrB = InetSocketAddress(InetAddress.getByName("10.0.0.2"), SlipstreamPorts.DISCOVERY)

            val probeA = PeerProbe { endpoint -> DiscoveredPeer(endpoint) }
            val strategyA = MulticastStrategy(
                identity = identityA,
                pairedPeerStore = pairedStoreA,
                probe = probeA,
                transportFactory = { FakeMulticastTransport(addrA, bus) },
                receiverScope = backgroundScope,
            )

            // find() starts A's one receive loop and sends A's own multicast query.
            val resultDeferred = async { strategyA.find(network) }
            testScheduler.runCurrent()
            advanceUntilIdle()

            val transportB = FakeMulticastTransport(addrB, bus)

            // Two datagrams land on A's single loop "concurrently": a query from the
            // paired peer (must be answered by the responder), and a direct announce
            // from the same peer (must resolve A's in-progress find()). Neither must
            // steal the other's datagram.
            val bQuery = PeerAnnouncement(
                SlipstreamPorts.PROTOCOL_VERSION,
                identityB.deviceId,
                "Device B",
                identityB.fingerprint,
                SlipstreamPorts.CONTROL,
                "query",
            )
            val bAnnounce = PeerAnnouncement(
                SlipstreamPorts.PROTOCOL_VERSION,
                identityB.deviceId,
                "Device B",
                identityB.fingerprint,
                SlipstreamPorts.CONTROL,
                "announce",
            )
            transportB.send(bQuery.toJson().toByteArray(Charsets.UTF_8), InetSocketAddress(SlipstreamPorts.MULTICAST_GROUP, SlipstreamPorts.DISCOVERY))
            transportB.send(bAnnounce.toJson().toByteArray(Charsets.UTF_8), addrA)

            advanceUntilIdle()

            // The find() concern resolved, from the announce meant for it.
            val result = resultDeferred.await()
            assertEquals(InetSocketAddress(addrB.address, SlipstreamPorts.CONTROL), result?.endpoint)

            // The responder concern separately answered B's query, by unicast.
            val reply = transportB.inbox.tryReceive().getOrNull()
            assertTrue("expected a unicast reply from A's responder", reply != null)
            val parsedReply = PeerAnnouncement.tryParse(String(reply!!.payload, Charsets.UTF_8))
            assertEquals(identityA.fingerprint, parsedReply?.fingerprint)
            assertEquals("announce", parsedReply?.kind)
        } finally {
            dirA.deleteRecursively()
        }
    }

    @Test
    fun `returns null when there is no paired peer to look for`() = runTest {
        val dir = createTempDirectory().toFile()
        try {
            val identity = DeviceIdentity.createNew("Device A")
            val pairedStore = PairedPeerStore(dir)
            val bus = FakeBus()
            val addr = InetSocketAddress(InetAddress.getByName("10.0.0.9"), SlipstreamPorts.DISCOVERY)

            val strategy = MulticastStrategy(
                identity = identity,
                pairedPeerStore = pairedStore,
                probe = PeerProbe { throw AssertionError("should never probe without a paired peer") },
                transportFactory = { FakeMulticastTransport(addr, bus) },
                receiverScope = backgroundScope,
            )

            assertNull(strategy.find(network))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `acquires the multicast lock only for the duration of a burst`() = runTest {
        val dir = createTempDirectory().toFile()
        try {
            val identityA = DeviceIdentity.createNew("Device A")
            val identityB = DeviceIdentity.createNew("Device B")
            val pairedStore = PairedPeerStore(dir)
            pairedStore.store(PairedPeer(identityB.deviceId, identityB.fingerprint, identityB.certificate))

            val bus = FakeBus()
            val addr = InetSocketAddress(InetAddress.getByName("10.0.0.1"), SlipstreamPorts.DISCOVERY)
            val lock = CountingMulticastLock()

            val strategy = MulticastStrategy(
                identity = identityA,
                pairedPeerStore = pairedStore,
                probe = PeerProbe { null },
                transportFactory = { FakeMulticastTransport(addr, bus) },
                multicastLock = lock,
                receiverScope = backgroundScope,
            )

            assertEquals(0, lock.acquireCount)

            val resultDeferred = async { strategy.find(network) }
            advanceUntilIdle()
            assertTrue("expected the lock to be held while the burst is in progress", lock.heldAfterFirstAcquire)

            resultDeferred.cancelAndJoin()

            assertEquals(1, lock.acquireCount)
            assertEquals(1, lock.releaseCount)
        } finally {
            dir.deleteRecursively()
        }
    }
}
