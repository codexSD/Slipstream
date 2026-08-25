package com.slipstream.core.net

import java.net.InetAddress
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LanGuardTest {

    @Test
    fun `isLocal accepts private and link local addresses`() {
        val accepted = listOf(
            "10.0.0.1",
            "10.255.255.254",
            "172.16.0.1",
            "172.31.255.254",
            "192.168.1.1",
            "192.168.43.1", // the Android hotspot gateway
            "169.254.10.20", // link-local
            "127.0.0.1", // loopback, needed for same-machine tests
            "::1",
            "fe80::1",
        )
        for (address in accepted) {
            assertTrue("expected $address to be local", LanGuard.isLocal(InetAddress.getByName(address)))
        }
    }

    @Test
    fun `isLocal rejects public addresses`() {
        val rejected = listOf(
            "8.8.8.8",
            "1.1.1.1",
            "172.15.255.255", // just below the 172.16/12 block
            "172.32.0.1", // just above it
            "192.167.1.1", // near-miss on 192.168/16
            "11.0.0.1", // near-miss on 10/8
            "2001:4860:4860::8888",
        )
        for (address in rejected) {
            assertFalse("expected $address to be rejected", LanGuard.isLocal(InetAddress.getByName(address)))
        }
    }

    @Test
    fun `isLocal accepts fc00 unique local IPv6`() {
        assertTrue(LanGuard.isLocal(InetAddress.getByName("fc00::1")))
        assertTrue(LanGuard.isLocal(InetAddress.getByName("fd12:3456:789a::1")))
    }

    @Test(expected = NonLocalAddressException::class)
    fun `ensureLocal throws for public address`() {
        LanGuard.ensureLocal(InetAddress.getByName("8.8.8.8"))
    }

    @Test
    fun `ensureLocal exception message contains the address`() {
        val ex = try {
            LanGuard.ensureLocal(InetAddress.getByName("8.8.8.8"))
            null
        } catch (e: NonLocalAddressException) {
            e
        }
        assertTrue(ex != null && ex.message!!.contains("8.8.8.8"))
    }

    @Test
    fun `ensureLocal passes for private address`() {
        LanGuard.ensureLocal(InetAddress.getByName("192.168.1.5"))
    }
}
