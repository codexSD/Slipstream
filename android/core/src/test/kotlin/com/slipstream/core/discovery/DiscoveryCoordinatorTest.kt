package com.slipstream.core.discovery

import com.slipstream.core.net.LocalNetwork
import com.slipstream.core.net.NetworkInfo
import java.net.InetAddress
import java.net.InetSocketAddress
import kotlin.io.path.createTempDirectory
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private class StubStrategy(
    override val name: String,
    private val delayMs: Long,
    private val result: DiscoveredPeer?,
) : DiscoveryStrategy {
    var wasCancelled = false

    override suspend fun find(network: LocalNetwork): DiscoveredPeer? {
        try {
            delay(delayMs)
            return result
        } catch (e: CancellationException) {
            wasCancelled = true
            throw e
        }
    }
}

private class ThrowingStrategy : DiscoveryStrategy {
    override val name = "throwing"

    override suspend fun find(network: LocalNetwork): DiscoveredPeer? {
        // On a device where multicast is blocked, the socket call raises. That must
        // not take discovery down with it.
        throw IllegalStateException("multicast blocked")
    }
}

private class StubNetworkInfo(private val network: LocalNetwork?) : NetworkInfo {
    override fun current(): LocalNetwork? = network
}

class DiscoveryCoordinatorTest {

    private fun network() = LocalNetwork(
        localAddress = InetAddress.getByName("192.168.1.5"),
        gateway = InetAddress.getByName("192.168.1.1"),
        prefixLength = 24,
        key = "net-1|192.168.1.0/24",
    )

    private fun peer(address: String) = DiscoveredPeer(InetSocketAddress(address, 53321))

    @Test
    fun `returns the fastest strategy's result and cancels the losers`() = runTest {
        val slow = StubStrategy("slow", delayMs = 3000, result = peer("192.168.1.10"))
        val fast = StubStrategy("fast", delayMs = 50, result = peer("192.168.1.9"))
        val dir = createTempDirectory().toFile()
        try {
            val cache = EndpointCache(dir)

            val result = DiscoveryCoordinator(StubNetworkInfo(network()), cache, listOf(slow, fast))
                .discover(10.seconds)

            assertEquals("fast", result!!.strategyName)
            assertTrue(slow.wasCancelled)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `a throwing strategy does not prevent another from winning`() = runTest {
        val result = DiscoveryCoordinator(
            StubNetworkInfo(network()),
            cache = null,
            listOf(ThrowingStrategy(), StubStrategy("good", 50, peer("192.168.1.9"))),
        ).discover(5.seconds)

        assertEquals("good", result!!.strategyName)
    }

    @Test
    fun `returns null when there is no current network`() = runTest {
        val result = DiscoveryCoordinator(
            StubNetworkInfo(null),
            cache = null,
            listOf(StubStrategy("good", 50, peer("192.168.1.9"))),
        ).discover(5.seconds)

        assertNull(result)
    }

    @Test
    fun `returns null when every strategy finds nothing`() = runTest {
        val result = DiscoveryCoordinator(
            StubNetworkInfo(network()),
            cache = null,
            listOf(
                StubStrategy("a", 10, null),
                StubStrategy("b", 20, null),
                ThrowingStrategy(),
            ),
        ).discover(5.seconds)

        assertNull(result)
    }

    @Test
    fun `caches the winning peer's endpoint under the current network's key`() = runTest {
        val dir = createTempDirectory().toFile()
        try {
            val cache = EndpointCache(dir)
            val net = network()

            DiscoveryCoordinator(
                StubNetworkInfo(net),
                cache,
                listOf(StubStrategy("fast", 10, peer("192.168.1.9"))),
            ).discover(5.seconds)

            assertEquals("192.168.1.9:53321", cache.get(net.key))
        } finally {
            dir.deleteRecursively()
        }
    }
}
