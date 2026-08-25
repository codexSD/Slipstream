package com.slipstream.core.discovery

import com.slipstream.core.SlipstreamPorts
import com.slipstream.core.net.LocalNetwork
import java.net.InetAddress
import java.net.InetSocketAddress
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GatewayProbeStrategyTest {

    @Test
    fun `probes the gateway on the control port when one is present`() = runTest {
        val network = LocalNetwork(
            localAddress = InetAddress.getByName("192.168.1.5"),
            gateway = InetAddress.getByName("192.168.1.1"),
            prefixLength = 24,
            key = "net-1|192.168.1.0/24",
        )

        var probed: InetSocketAddress? = null
        val probe = PeerProbe { endpoint ->
            probed = endpoint
            DiscoveredPeer(endpoint)
        }

        val result = GatewayProbeStrategy(probe).find(network)

        assertEquals(InetSocketAddress(InetAddress.getByName("192.168.1.1"), SlipstreamPorts.CONTROL), probed)
        assertEquals(probed, result?.endpoint)
    }

    @Test
    fun `returns null when the network has no gateway`() = runTest {
        val network = LocalNetwork(
            localAddress = InetAddress.getByName("192.168.1.5"),
            gateway = null,
            prefixLength = 24,
            key = "net-1|192.168.1.0/24",
        )

        val probe = PeerProbe { throw AssertionError("probe should not be called without a gateway") }

        assertNull(GatewayProbeStrategy(probe).find(network))
    }
}
