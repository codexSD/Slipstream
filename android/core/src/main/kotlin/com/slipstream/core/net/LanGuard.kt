package com.slipstream.core.net

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

class NonLocalAddressException(val address: InetAddress) :
    Exception("Refused non-local address $address. Slipstream never leaves the local network.")

/**
 * Spec §11 layer 2: the only addresses Slipstream will connect to or accept from.
 * Applied to inbound and outbound connections alike. Must agree byte-for-byte with
 * the C# LanGuard, or the two ends disagree about whether they are allowed to talk.
 */
object LanGuard {
    fun isLocal(address: InetAddress): Boolean = when (address) {
        is Inet4Address -> isLocalV4(address.address)
        is Inet6Address -> isLocalV6(address)
        else -> false
    }

    private fun isLocalV4(b: ByteArray): Boolean {
        val o0 = b[0].toInt() and 0xFF
        val o1 = b[1].toInt() and 0xFF
        return when {
            o0 == 10 -> true                       // 10.0.0.0/8
            o0 == 172 && o1 in 16..31 -> true      // 172.16.0.0/12
            o0 == 192 && o1 == 168 -> true         // 192.168.0.0/16
            o0 == 169 && o1 == 254 -> true         // link-local
            o0 == 127 -> true                      // loopback, for tests
            else -> false
        }
    }

    private fun isLocalV6(address: Inet6Address): Boolean {
        if (address.isLoopbackAddress || address.isLinkLocalAddress) return true
        return (address.address[0].toInt() and 0xFE) == 0xFC   // fc00::/7 ULA
    }

    fun ensureLocal(address: InetAddress) {
        if (!isLocal(address)) throw NonLocalAddressException(address)
    }
}
