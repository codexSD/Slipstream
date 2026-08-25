package com.slipstream.core.discovery

import com.slipstream.core.SlipstreamPorts
import com.slipstream.core.net.LocalNetwork
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SubnetSweepStrategyTest {

    private val network = LocalNetwork(
        localAddress = InetAddress.getByName("192.168.1.5"),
        gateway = InetAddress.getByName("192.168.1.1"),
        prefixLength = 24,
        key = "net-1|192.168.1.0/24",
    )

    @Test
    fun `finds the one host that answers among the swept 254`() = runTest {
        val target = InetSocketAddress(InetAddress.getByName("192.168.1.42"), SlipstreamPorts.CONTROL)
        val probe = PeerProbe { endpoint -> if (endpoint == target) DiscoveredPeer(endpoint) else null }

        val result = SubnetSweepStrategy(probe).find(network)

        assertEquals(target, result?.endpoint)
    }

    @Test
    fun `bounds concurrent probes to the configured semaphore limit`() = runTest {
        val maxConcurrentProbes = 8
        val current = AtomicInteger(0)
        val observedMax = AtomicInteger(0)
        val mutex = Mutex()

        val probe = PeerProbe {
            val now = current.incrementAndGet()
            mutex.withLock { if (now > observedMax.get()) observedMax.set(now) }
            delay(10)
            current.decrementAndGet()
            null
        }

        SubnetSweepStrategy(probe, maxConcurrentProbes = maxConcurrentProbes).find(network)

        assertTrue(
            "observed $observedMax concurrent probes, expected at most $maxConcurrentProbes",
            observedMax.get() <= maxConcurrentProbes,
        )
        assertTrue("expected the sweep to actually run probes concurrently", observedMax.get() > 1)
    }

    @Test
    fun `returns null for a network wider than a slash 24`() = runTest {
        val wideNetwork = network.copy(prefixLength = 16)
        val probe = PeerProbe { throw AssertionError("no host should be probed for a wide network") }

        assertNull(SubnetSweepStrategy(probe).find(wideNetwork))
    }
}
