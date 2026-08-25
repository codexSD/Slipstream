package com.slipstream.core.net

import com.slipstream.core.SlipstreamPeer
import java.net.Inet4Address
import java.net.Inet6Address
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Spec §11 layer 4: an address that came off the wire may never be handed to a resolver.
 * These cover [IpLiteral] itself and the one place a remote peer's text reaches it -
 * [SlipstreamPeer.bulkEndpointFrom], which parses the `host` of a `pull.ok`.
 */
class IpLiteralTest {

    @Test
    fun `a dotted-quad literal parses without a resolver`() {
        val address = IpLiteral.parse("192.168.1.7")
        assertTrue(address is Inet4Address)
        assertEquals("192.168.1.7", address!!.hostAddress)
    }

    @Test
    fun `an IPv6 literal parses`() {
        val address = IpLiteral.parse("fe80::1")
        assertTrue(address is Inet6Address)
        // A bracketed literal (URL form) denotes the same address.
        assertEquals(address, IpLiteral.parse("[fe80::1]"))
    }

    @Test
    fun `a hostname is refused rather than resolved`() {
        // The whole point: anything DNS *could* answer must be rejected before it gets there.
        assertNull(IpLiteral.parse("attacker.example.com"))
        assertNull(IpLiteral.parse("localhost"))
        assertNull(IpLiteral.parse("nas"))
        assertNull(IpLiteral.parse("192.168.1.7.example.com"))
    }

    @Test
    fun `ambiguous or malformed IPv4 text is refused`() {
        // Leading zeros are octal to some stacks and decimal to others; a string two ends read
        // differently is exactly the kind of thing a LAN check must never have to reason about.
        assertNull(IpLiteral.parse("010.0.0.1"))
        assertNull(IpLiteral.parse("1.2.3"))
        assertNull(IpLiteral.parse("1.2.3.4.5"))
        assertNull(IpLiteral.parse("256.1.1.1"))
        assertNull(IpLiteral.parse("1.2.3.-4"))
        assertNull(IpLiteral.parse(" 1.2.3.4"))
        assertNull(IpLiteral.parse(""))
    }

    @Test
    fun `a pull ok host that is a hostname is refused`() {
        val e = assertThrows(IllegalArgumentException::class.java) {
            SlipstreamPeer.bulkEndpointFrom("attacker.example.com", 53322)
        }
        assertTrue(e.message!!.contains("not an IP literal"))
    }

    @Test
    fun `a pull ok host outside the LAN is refused`() {
        assertThrows(NonLocalAddressException::class.java) {
            SlipstreamPeer.bulkEndpointFrom("93.184.216.34", 53322)
        }
    }

    @Test
    fun `a pull ok host on the LAN is accepted`() {
        val endpoint = SlipstreamPeer.bulkEndpointFrom("192.168.1.7", 53322)
        assertEquals("192.168.1.7", endpoint.address.hostAddress)
        assertEquals(53322, endpoint.port)
        assertTrue("must already be resolved, never a lazy hostname", !endpoint.isUnresolved)
    }

    @Test
    fun `a pull ok port outside the valid range is refused`() {
        assertThrows(IllegalArgumentException::class.java) {
            SlipstreamPeer.bulkEndpointFrom("192.168.1.7", 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            SlipstreamPeer.bulkEndpointFrom("192.168.1.7", 70000)
        }
    }
}
