package com.slipstream.core.net

import java.net.InetAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SubnetMathTest {

    @Test
    fun `enumerates 254 hosts for a slash 24`() {
        val hosts = SubnetMath.enumerateHosts(InetAddress.getByName("192.168.1.37"), 24).toList()

        assertEquals(254, hosts.size)
        assertEquals(InetAddress.getByName("192.168.1.1"), hosts.first())
        assertEquals(InetAddress.getByName("192.168.1.254"), hosts.last())
    }

    @Test
    fun `excludes the network and broadcast addresses`() {
        val hosts = SubnetMath.enumerateHosts(InetAddress.getByName("192.168.1.37"), 24).toList()

        assertTrue(InetAddress.getByName("192.168.1.0") !in hosts)
        assertTrue(InetAddress.getByName("192.168.1.255") !in hosts)
    }

    @Test
    fun `refuses to sweep anything wider than a slash 24`() {
        assertEquals(emptyList<InetAddress>(), SubnetMath.enumerateHosts(InetAddress.getByName("10.0.0.1"), 16).toList())
        assertEquals(emptyList<InetAddress>(), SubnetMath.enumerateHosts(InetAddress.getByName("10.0.0.1"), 8).toList())
    }

    @Test
    fun `handles a prefix narrower than a slash 24`() {
        val hosts = SubnetMath.enumerateHosts(InetAddress.getByName("192.168.1.130"), 25).toList()

        assertEquals(126, hosts.size)
        assertEquals(InetAddress.getByName("192.168.1.129"), hosts.first())
        assertEquals(InetAddress.getByName("192.168.1.254"), hosts.last())
    }
}
