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
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.io.path.createTempDirectory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowNetwork

private val LOOPBACK: InetAddress = InetAddress.getByName("127.0.0.1")

private class FixedNetworkInfo(private val network: LocalNetwork?) : NetworkInfo {
    override fun current(): LocalNetwork? = network
}

/** Three ports that were free a moment ago. Deliberately *fixed* for the storm test: with
 * `port = 0` every restart would land on a fresh port and a leaked, still-bound server from a
 * previous generation would go unnoticed. With fixed ports, a leak makes the next bind fail. */
private fun threeFreePorts(): Triple<Int, Int, Int> {
    val sockets = List(3) { ServerSocket(0, 1, LOOPBACK) }
    val ports = sockets.map { it.localPort }
    sockets.forEach { it.close() }
    return Triple(ports[0], ports[1], ports[2])
}

/**
 * `ConnectivityManager` delivers `onAvailable` / `onCapabilitiesChanged` / `onLost`
 * concurrently, on its own threads, and they overlap in practice on any ordinary Wi-Fi
 * transition. Robolectric is used here only for [ShadowNetwork], which is the one way to get
 * real, distinguishable [Network] instances in a JVM unit test.
 */
@RunWith(RobolectricTestRunner::class)
class SlipstreamPeerNetworkChangeTest {

    private fun peer(
        networkInfo: NetworkInfo,
        ports: Triple<Int, Int, Int> = Triple(0, 0, 0),
        onTeardown: () -> Unit = {},
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
        onResumeAttempt = onResumeAttempt,
    )

    @Test
    fun `overlapping network changes never escape as an exception and never leak a server`() {
        val networkInfo = FixedNetworkInfo(LocalNetwork(LOOPBACK, null, 32, "k"))
        val ports = threeFreePorts()
        val peer = peer(networkInfo, ports)

        val networks = listOf(ShadowNetwork.newInstance(101), ShadowNetwork.newInstance(202))
        val failures = CopyOnWriteArrayList<Throwable>()
        val barrier = CyclicBarrier(THREADS)

        val threads = (0 until THREADS).map { index ->
            thread(isDaemon = true) {
                barrier.await(10, TimeUnit.SECONDS)
                repeat(EVENTS_PER_THREAD) { round ->
                    try {
                        peer.onNetworkChanged(networks[(index + round) % networks.size])
                    } catch (t: Throwable) {
                        // A NetworkCallback has nowhere to catch this - it would kill the
                        // service - so anything escaping at all is the failure.
                        failures.add(t)
                    }
                }
            }
        }
        threads.forEach { it.join(60_000) }

        assertTrue("onNetworkChanged must never throw out into the framework callback: $failures", failures.isEmpty())
        assertEquals(
            "after the storm exactly one live set of servers must remain - a leaked, still-bound " +
                "server from an earlier generation would have made the final bind fail",
            3,
            peer.runningServerCount,
        )
        peer.close()
        assertEquals(0, peer.runningServerCount)
    }

    @Test
    fun `a repeat event for the network already in use is ignored`() {
        val networkInfo = FixedNetworkInfo(LocalNetwork(LOOPBACK, null, 32, "k"))
        val teardowns = AtomicInteger(0)
        val peer = peer(networkInfo, threeFreePorts(), onTeardown = { teardowns.incrementAndGet() })

        val network = ShadowNetwork.newInstance(7)
        peer.onNetworkChanged(network)
        assertEquals(1, teardowns.get())

        // This is what onCapabilitiesChanged does all day long: fire for an unchanged network.
        // Restarting the servers here would kill every in-flight transfer for no reason.
        repeat(5) { peer.onNetworkChanged(network) }
        assertEquals("a repeat of the current network must be a no-op", 1, teardowns.get())

        // A genuinely different network still restarts everything.
        peer.onNetworkChanged(ShadowNetwork.newInstance(8))
        assertEquals(2, teardowns.get())

        peer.close()
    }

    @Test
    fun `losing the network entirely is applied even though it is the initial binder state`() {
        // networkBinder.network starts out null, so "unchanged" must not be decided by
        // comparing against it before anything has ever been applied.
        val teardowns = AtomicInteger(0)
        val peer = peer(FixedNetworkInfo(null), onTeardown = { teardowns.incrementAndGet() })
        peer.onNetworkChanged(null)
        assertEquals(1, teardowns.get())
        peer.close()
    }

    @Test
    fun `two overlapping resume passes never start the same transfer twice`() {
        val dir = createTempDirectory().toFile()
        val transferId = UUID.randomUUID()
        val part = PartFile.openOrCreate(File(dir, "incomplete.bin"), transferId, size = 64, chunkSize = 16)

        val attempts = AtomicInteger(0)
        val firstAttemptStarted = CountDownLatch(1)
        val release = CountDownLatch(1)

        val peer = peer(
            FixedNetworkInfo(null),
            dir = dir,
            onResumeAttempt = {
                attempts.incrementAndGet()
                firstAttemptStarted.countDown()
                // Hold the first pass inside the resume so the second pass genuinely overlaps
                // it, rather than arriving after it has already finished and cleaned up.
                release.await(10, TimeUnit.SECONDS)
            },
        )
        peer.activePulls[transferId] = SlipstreamPeer.ActivePull(
            part = part,
            streams = 2,
            remotePath = "video.mp4",
            peerControlEndpoint = InetSocketAddress(LOOPBACK, 53321),
        )

        val first = thread(isDaemon = true) { peer.resumeActivePulls() }
        assertTrue(firstAttemptStarted.await(10, TimeUnit.SECONDS))

        // Second network change while the first resume is still in flight. Two concurrent
        // TransferEngine.pull loops on one PartFile would interleave chunk writes and corrupt it.
        peer.resumeActivePulls()
        assertEquals("a resume already in progress must not be started again", 1, attempts.get())

        release.countDown()
        first.join(10_000)
        part.close()
        peer.close()
    }

    private companion object {
        const val THREADS = 4
        const val EVENTS_PER_THREAD = 5
    }
}
