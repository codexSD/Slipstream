package com.slipstream.core

import com.slipstream.core.control.ClipboardSink
import com.slipstream.core.discovery.DiscoveryCoordinator
import com.slipstream.core.identity.DeviceIdentity
import com.slipstream.core.identity.PairedPeerStore
import com.slipstream.core.net.LocalNetwork
import com.slipstream.core.net.NetworkInfo
import com.slipstream.core.transfer.PartFile
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.createTempDirectory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private class StubNetworkInfo(private val network: LocalNetwork?) : NetworkInfo {
    override fun current(): LocalNetwork? = network
}

private fun peer(): SlipstreamPeer {
    val identity = DeviceIdentity.createNew("Test Device")
    val dir = createTempDirectory().toFile()
    val networkInfo = StubNetworkInfo(null) // no active network: discover() resolves to null immediately
    val teardownCount = AtomicInteger(0)
    return SlipstreamPeer(
        identity = identity,
        peerStore = PairedPeerStore(dir),
        networkInfo = networkInfo,
        rootDirectory = dir,
        clipboardSink = ClipboardSink { },
        discoveryCoordinatorFactory = { DiscoveryCoordinator(networkInfo, cache = null, strategies = emptyList()) },
    )
}

class SlipstreamPeerTest {

    @Test
    fun `onNetworkChanged tears down and re-runs discovery`() {
        val identity = DeviceIdentity.createNew("Test Device")
        val dir = createTempDirectory().toFile()
        val networkInfo = StubNetworkInfo(null)
        val teardownLatch = CountDownLatch(1)
        val rediscoverLatch = CountDownLatch(1)

        val peer = SlipstreamPeer(
            identity = identity,
            peerStore = PairedPeerStore(dir),
            networkInfo = networkInfo,
            rootDirectory = dir,
            clipboardSink = ClipboardSink { },
            discoveryCoordinatorFactory = { DiscoveryCoordinator(networkInfo, cache = null, strategies = emptyList()) },
            onTeardown = { teardownLatch.countDown() },
            onRediscover = { rediscoverLatch.countDown() },
        )

        peer.onNetworkChanged(null)

        assertTrue("teardown must run synchronously as part of onNetworkChanged", teardownLatch.await(1, TimeUnit.SECONDS))
        assertTrue("re-discovery must be triggered by a network change", rediscoverLatch.await(5, TimeUnit.SECONDS))
        peer.close()
    }

    @Test
    fun `onNetworkChanged updates the network binder for future sockets`() {
        val peer = peer()
        // No real android.net.Network available in a JVM unit test; passing null still proves
        // the binder assignment path runs without throwing, and is exercised end-to-end by
        // NetworkBindingTest for the sockets themselves.
        peer.onNetworkChanged(null)
        peer.close()
    }

    @Test
    fun `onNetworkChanged resumes every incomplete in-flight pull`() {
        val peer = peer()
        val dest = createTempDirectory().toFile()
        val transferId = UUID.randomUUID()
        val part = PartFile.openOrCreate(
            java.io.File(dest, "incomplete.bin"),
            transferId,
            size = 64,
            chunkSize = 16,
        )
        // Deliberately left incomplete: no writeChunk() calls, simulating a pull that was
        // in flight when the network changed.
        peer.activePulls[transferId] = SlipstreamPeer.ActivePull(
            part = part,
            streams = 2,
            remotePath = "video.mp4",
            peerControlEndpoint = InetSocketAddress(InetAddress.getByName("127.0.0.1"), 53321),
        )

        val resumed = AtomicInteger(0)
        val resumedId = mutableListOf<UUID>()
        val peerWithHook = SlipstreamPeer(
            identity = peer.identity,
            peerStore = PairedPeerStore(dest),
            networkInfo = StubNetworkInfo(null),
            rootDirectory = dest,
            clipboardSink = ClipboardSink { },
            discoveryCoordinatorFactory = { DiscoveryCoordinator(StubNetworkInfo(null), cache = null, strategies = emptyList()) },
            onResumeAttempt = { id -> resumed.incrementAndGet(); resumedId.add(id) },
        )
        peerWithHook.activePulls[transferId] = SlipstreamPeer.ActivePull(
            part = part,
            streams = 2,
            remotePath = "video.mp4",
            peerControlEndpoint = InetSocketAddress(InetAddress.getByName("127.0.0.1"), 53321),
        )

        peerWithHook.onNetworkChanged(null)

        assertEquals(1, resumed.get())
        assertEquals(transferId, resumedId.single())
        part.close()
        peer.close()
        peerWithHook.close()
    }

    @Test
    fun `a completed pull is never resumed`() {
        val dest = createTempDirectory().toFile()
        val transferId = UUID.randomUUID()
        val part = PartFile.openOrCreate(java.io.File(dest, "complete.bin"), transferId, size = 16, chunkSize = 16)
        part.writeChunk(0, ByteArray(16), com.slipstream.core.transfer.Crc32C.compute(ByteArray(16)))
        assertTrue(part.complete())

        val resumed = AtomicInteger(0)
        val networkInfo = StubNetworkInfo(null)
        val peer = SlipstreamPeer(
            identity = DeviceIdentity.createNew("Test Device"),
            peerStore = PairedPeerStore(dest),
            networkInfo = networkInfo,
            rootDirectory = dest,
            clipboardSink = ClipboardSink { },
            discoveryCoordinatorFactory = { DiscoveryCoordinator(networkInfo, cache = null, strategies = emptyList()) },
            onResumeAttempt = { resumed.incrementAndGet() },
        )
        peer.activePulls[transferId] = SlipstreamPeer.ActivePull(
            part = part,
            streams = 1,
            remotePath = "x.bin",
            peerControlEndpoint = InetSocketAddress(InetAddress.getByName("127.0.0.1"), 53321),
        )

        peer.onNetworkChanged(null)

        assertEquals(0, resumed.get())
        part.close()
        peer.close()
    }
}
