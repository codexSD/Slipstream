package com.slipstream.core

import com.slipstream.core.control.ClipboardSink
import com.slipstream.core.discovery.DiscoveryCoordinator
import com.slipstream.core.identity.DeviceIdentity
import com.slipstream.core.identity.PairedPeerStore
import com.slipstream.core.net.LocalNetwork
import com.slipstream.core.net.NetworkInfo
import com.slipstream.core.transfer.PartFile
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.createTempDirectory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowNetwork

private val LOOPBACK_ADDR: InetAddress = InetAddress.getByName("127.0.0.1")

private class SwitchableNetworkInfo(@Volatile var network: LocalNetwork?) : NetworkInfo {
    override fun current(): LocalNetwork? = network
}

private fun freePorts(): Triple<Int, Int, Int> {
    val sockets = List(3) { ServerSocket(0, 1, LOOPBACK_ADDR) }
    val ports = sockets.map { it.localPort }
    sockets.forEach { it.close() }
    return Triple(ports[0], ports[1], ports[2])
}

/**
 * The network-change guard (finding 13) and the served-transfer bookkeeping (finding 8):
 * both are about work the peer must *not* keep doing.
 */
@RunWith(RobolectricTestRunner::class)
class SlipstreamPeerLifecycleTest {

    private fun peer(
        networkInfo: NetworkInfo,
        ports: Triple<Int, Int, Int> = Triple(0, 0, 0),
        onTeardown: () -> Unit = {},
        onRediscover: (com.slipstream.core.discovery.DiscoveryResult?) -> Unit = {},
        onResumeAttempt: (UUID) -> Unit = {},
        dir: File = createTempDirectory().toFile(),
    ) = SlipstreamPeer(
        identity = DeviceIdentity.createNew("Test Device"),
        peerStore = PairedPeerStore(dir),
        networkInfo = networkInfo,
        rootDirectory = dir,
        clipboardSink = ClipboardSink { },
        discoveryCoordinatorFactory = { DiscoveryCoordinator(networkInfo, cache = null, strategies = emptyList()) },
        controlPort = ports.first,
        bulkPort = ports.second,
        mediaPort = ports.third,
        onTeardown = onTeardown,
        onRediscover = onRediscover,
        onResumeAttempt = onResumeAttempt,
    )

    @Test
    fun `start seeds the applied network so the first callback for it is a no-op`() {
        val networkInfo = SwitchableNetworkInfo(LocalNetwork(LOOPBACK_ADDR, null, 32, "k"))
        val teardowns = AtomicInteger(0)
        val peer = peer(networkInfo, freePorts(), onTeardown = { teardowns.incrementAndGet() })
        val network = ShadowNetwork.newInstance(31)

        peer.start(network)
        assertEquals(3, peer.runningServerCount)

        // The very first onNetworkChanged after start() is for the network start() already
        // bound to. Tearing three servers down and rebuilding them here - on every cold start -
        // is pure waste, and kills anything that connected in between.
        peer.onNetworkChanged(network)

        assertEquals("start()'s own bring-up must not be torn down by the first callback", 0, teardowns.get())
        assertEquals(3, peer.runningServerCount)

        // A genuinely different network still restarts everything.
        peer.onNetworkChanged(ShadowNetwork.newInstance(32))
        assertEquals(1, teardowns.get())

        peer.close()
    }

    @Test
    fun `an apply that failed runs neither re-discovery nor resume`() {
        // networkInfo.current() == null: the interface is not up yet, so there is nothing to
        // bind. Discovering over servers that are down, and burning a resume attempt against
        // them, is work that can only fail.
        val networkInfo = SwitchableNetworkInfo(null)
        val rediscovers = AtomicInteger(0)
        val resumes = AtomicInteger(0)
        val dir = createTempDirectory().toFile()
        val peer = peer(
            networkInfo,
            freePorts(),
            onRediscover = { rediscovers.incrementAndGet() },
            onResumeAttempt = { resumes.incrementAndGet() },
            dir = dir,
        )

        val transferId = UUID.randomUUID()
        val part = PartFile.openOrCreate(File(dir, "incomplete.bin"), transferId, size = 64, chunkSize = 16)
        peer.activePulls[transferId] = SlipstreamPeer.ActivePull(
            part, 2, "video.mp4", InetSocketAddress(LOOPBACK_ADDR, 53321),
        )

        peer.onNetworkChanged(ShadowNetwork.newInstance(41))

        Thread.sleep(300)
        assertEquals("no re-discovery after an apply that did not happen", 0, rediscovers.get())
        assertEquals("no resume after an apply that did not happen", 0, resumes.get())

        // Once the interface is ready the same network applies for real, and both run.
        networkInfo.network = LocalNetwork(LOOPBACK_ADDR, null, 32, "k")
        peer.onNetworkChanged(ShadowNetwork.newInstance(41))

        assertTrue(waitFor { resumes.get() == 1 })
        assertTrue(waitFor { rediscovers.get() == 1 })

        part.close()
        peer.close()
    }

    @Test
    fun `served-transfer bookkeeping does not grow without bound`() {
        val dir = createTempDirectory().toFile()
        val peer = peer(SwitchableNetworkInfo(null), dir = dir)

        // Every pull.request answered records a source file keyed by transfer id, and nothing
        // ever removed them: one entry per request, for the process lifetime.
        val ids = (0 until 5).map { UUID.randomUUID() }
        val tokens = ids.mapIndexed { i, id ->
            val file = File(dir, "f$i.bin")
            val token = peer.bulkTokenVault.issueBulk(id, file.path, size = 1, expectedStreams = 1)
            peer.recordServedTransfer(id, file)
            token
        }
        assertEquals(5, peer.servedTransferCount)

        // Completing a transfer drops its authorization AND its source-file entry together.
        peer.completeServedTransfer(ids[0])
        assertEquals(4, peer.servedTransferCount)
        assertEquals(null, peer.bulkTokenVault.validate(tokens[0].value, ids[0]))
        assertEquals(tokens[1], peer.bulkTokenVault.validate(tokens[1].value, ids[1]))

        peer.close()
    }

    private fun waitFor(deadlineMs: Long = 5000, condition: () -> Boolean): Boolean {
        val end = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(deadlineMs)
        while (System.nanoTime() < end) {
            if (condition()) return true
            Thread.sleep(20)
        }
        return condition()
    }
}
