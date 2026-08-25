package com.slipstream.core.discovery

import com.slipstream.core.SlipstreamPorts
import com.slipstream.core.identity.DeviceIdentity
import com.slipstream.core.identity.PairedPeer
import com.slipstream.core.identity.PairedPeerStore
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.createTempDirectory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.slipstream.core.net.LocalNetwork

/** Same in-memory LAN as [MulticastStrategyTest], kept local to this file so the two suites
 * stay independent. */
private class Bus {
    private val transports = ConcurrentHashMap<InetSocketAddress, FakeTransport>()

    fun register(t: FakeTransport) { transports[t.selfAddress] = t }
    fun unregister(t: FakeTransport) { transports.remove(t.selfAddress) }

    suspend fun deliver(payload: ByteArray, target: InetSocketAddress, sender: InetSocketAddress) {
        if (target.address == SlipstreamPorts.MULTICAST_GROUP) {
            transports.values.filter { it.selfAddress != sender }.forEach { it.inbox.send(DatagramMessage(payload, sender)) }
        } else {
            transports[target]?.inbox?.send(DatagramMessage(payload, sender))
        }
    }
}

private class FakeTransport(val selfAddress: InetSocketAddress, private val bus: Bus) : MulticastTransport {
    val inbox = Channel<DatagramMessage>(Channel.UNLIMITED)
    val sent = mutableListOf<InetSocketAddress>()
    var closed = false

    init { bus.register(this) }

    override suspend fun send(payload: ByteArray, target: InetSocketAddress) {
        sent.add(target)
        bus.deliver(payload, target, selfAddress)
    }
    override suspend fun receive(): DatagramMessage = inbox.receive()
    override fun close() {
        closed = true
        bus.unregister(this)
        inbox.close()
    }
}

private class CountingLock : MulticastLockHandle {
    var acquireCount = 0
    var releaseCount = 0
    override fun acquire() { acquireCount++ }
    override fun release() { releaseCount++ }
}

/**
 * Spec §5: "the phone only ever listens and responds." As originally built the responder only
 * ran while one of this device's own [MulticastStrategy.find] calls held the socket open, so a
 * phone that was merely *running* answered nothing - a PC's multicast query for S3 discovery
 * found silence. These cover the always-on responder mode, and the §5/§14 constraint that the
 * multicast lock is still never held while idle.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MulticastResponderTest {

    private val network = LocalNetwork(
        localAddress = InetAddress.getByName("192.168.1.5"),
        gateway = InetAddress.getByName("192.168.1.1"),
        prefixLength = 24,
        key = "if:wlan0|192.168.1.0/24",
    )

    private class Fixture(
        val bus: Bus,
        val lock: CountingLock,
        val strategy: MulticastStrategy,
        val peerIdentity: DeviceIdentity,
        val opened: List<FakeTransport>,
    )

    private fun fixture(scope: kotlinx.coroutines.CoroutineScope): Fixture {
        val dir = createTempDirectory().toFile()
        val self = DeviceIdentity.createNew("Phone")
        val peer = DeviceIdentity.createNew("PC")
        val store = PairedPeerStore(dir)
        store.store(PairedPeer(peer.deviceId, peer.fingerprint, peer.certificate))

        val bus = Bus()
        val lock = CountingLock()
        val addr = InetSocketAddress(InetAddress.getByName("10.0.0.1"), SlipstreamPorts.DISCOVERY)
        val opened = mutableListOf<FakeTransport>()
        val strategy = MulticastStrategy(
            identity = self,
            pairedPeerStore = store,
            probe = PeerProbe { endpoint -> DiscoveredPeer(endpoint) },
            transportFactory = { FakeTransport(addr, bus).also { opened.add(it) } },
            multicastLock = lock,
            receiverScope = scope,
        )
        return Fixture(bus, lock, strategy, peer, opened)
    }

    private fun query(identity: DeviceIdentity) = PeerAnnouncement(
        SlipstreamPorts.PROTOCOL_VERSION,
        identity.deviceId,
        identity.displayName,
        identity.fingerprint,
        SlipstreamPorts.CONTROL,
        "query",
    )

    @Test
    fun `an idle peer that is not discovering still answers a paired peer's query`() = runTest {
        val f = fixture(CoroutineScope(UnconfinedTestDispatcher(testScheduler)))

        // No find() anywhere: this is a phone doing nothing but running.
        f.strategy.startResponder()
        advanceUntilIdle()
        assertEquals("the responder must actually bind a socket", 1, f.opened.size)

        val pc = FakeTransport(InetSocketAddress(InetAddress.getByName("10.0.0.2"), SlipstreamPorts.DISCOVERY), f.bus)
        pc.send(
            query(f.peerIdentity).toJson().toByteArray(Charsets.UTF_8),
            InetSocketAddress(SlipstreamPorts.MULTICAST_GROUP, SlipstreamPorts.DISCOVERY),
        )
        advanceUntilIdle()

        val reply = pc.inbox.tryReceive().getOrNull()
        assertNotNull(
            "an idle phone must answer a multicast query (spec §5 S3)",
            reply,
        )
        val parsed = PeerAnnouncement.tryParse(String(reply!!.payload, Charsets.UTF_8))
        assertEquals("announce", parsed?.kind)

        f.strategy.stopResponder()
        advanceUntilIdle()
    }

    @Test
    fun `idle listening never holds the multicast lock`() = runTest {
        val f = fixture(CoroutineScope(UnconfinedTestDispatcher(testScheduler)))

        f.strategy.startResponder()
        advanceUntilIdle()

        // Spec §5/§14: the lock is a battery cost, only justified for an active burst.
        assertEquals("the always-on responder must not acquire the lock", 0, f.lock.acquireCount)

        val find = async { f.strategy.find(network) }
        advanceUntilIdle()
        assertEquals("an actual discovery burst still takes the lock", 1, f.lock.acquireCount)

        find.cancelAndJoin()
        assertEquals("and gives it straight back when the burst ends", 1, f.lock.releaseCount)
        assertEquals(0, f.lock.acquireCount - f.lock.releaseCount)

        f.strategy.stopResponder()
        advanceUntilIdle()
    }

    @Test
    fun `a find that ends does not tear the responder's socket down under it`() = runTest {
        val f = fixture(CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
        f.strategy.startResponder()
        advanceUntilIdle()

        val find = async { f.strategy.find(network) }
        advanceUntilIdle()
        find.cancelAndJoin()
        advanceUntilIdle()

        // Socket still live: the responder still has a reference to it.
        val pc = FakeTransport(InetSocketAddress(InetAddress.getByName("10.0.0.3"), SlipstreamPorts.DISCOVERY), f.bus)
        pc.send(
            query(f.peerIdentity).toJson().toByteArray(Charsets.UTF_8),
            InetSocketAddress(SlipstreamPorts.MULTICAST_GROUP, SlipstreamPorts.DISCOVERY),
        )
        advanceUntilIdle()
        assertNotNull(
            "the responder must survive an unrelated find() finishing",
            pc.inbox.tryReceive().getOrNull(),
        )

        f.strategy.stopResponder()
        advanceUntilIdle()
    }

    @Test
    fun `stopping the responder closes the socket once nothing needs it`() = runTest {
        val opened = mutableListOf<FakeTransport>()
        val dir = createTempDirectory().toFile()
        val self = DeviceIdentity.createNew("Phone")
        val peer = DeviceIdentity.createNew("PC")
        val store = PairedPeerStore(dir)
        store.store(PairedPeer(peer.deviceId, peer.fingerprint, peer.certificate))
        val bus = Bus()
        val strategy = MulticastStrategy(
            identity = self,
            pairedPeerStore = store,
            probe = PeerProbe { null },
            transportFactory = {
                FakeTransport(InetSocketAddress(InetAddress.getByName("10.0.0.4"), SlipstreamPorts.DISCOVERY), bus)
                    .also { opened.add(it) }
            },
            receiverScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
        )

        strategy.startResponder()
        advanceUntilIdle()
        assertEquals(1, opened.size)
        assertTrue(!opened.single().closed)

        strategy.stopResponder()
        advanceUntilIdle()
        assertTrue("the socket must not stay bound after the last reference goes", opened.single().closed)
    }
}
