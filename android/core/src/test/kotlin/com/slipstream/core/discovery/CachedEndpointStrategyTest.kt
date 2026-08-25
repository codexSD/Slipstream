package com.slipstream.core.discovery

import com.slipstream.core.net.LocalNetwork
import java.net.InetAddress
import java.net.InetSocketAddress
import kotlin.io.path.createTempDirectory
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CachedEndpointStrategyTest {

    private val network = LocalNetwork(
        localAddress = InetAddress.getByName("192.168.1.5"),
        gateway = InetAddress.getByName("192.168.1.1"),
        prefixLength = 24,
        key = "net-1|192.168.1.0/24",
    )

    @Test
    fun `probes the cached endpoint for the current network`() = runTest {
        val dir = createTempDirectory().toFile()
        try {
            val cache = EndpointCache(dir)
            cache.put(network.key, "192.168.1.10:53321")

            var probed: InetSocketAddress? = null
            val probe = PeerProbe { endpoint ->
                probed = endpoint
                DiscoveredPeer(endpoint)
            }

            val strategy = CachedEndpointStrategy(cache, probe)
            val result = strategy.find(network)

            assertEquals(InetSocketAddress("192.168.1.10", 53321), probed)
            assertEquals(InetSocketAddress("192.168.1.10", 53321), result?.endpoint)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `returns null when nothing is cached for this network`() = runTest {
        val dir = createTempDirectory().toFile()
        try {
            val cache = EndpointCache(dir)
            val probe = PeerProbe { fail("probe should not be called when cache is empty") }

            val strategy = CachedEndpointStrategy(cache, probe)
            assertNull(strategy.find(network))
        } finally {
            dir.deleteRecursively()
        }
    }

    private fun fail(message: String): Nothing = throw AssertionError(message)
}
