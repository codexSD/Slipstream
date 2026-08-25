package com.slipstream.core.net

import java.net.InetAddress

/**
 * Parses an IP address *literal* out of untrusted text, without ever consulting a resolver.
 *
 * Spec §11 layer 4 ("no outbound calls of any kind") makes this mandatory for anything a
 * remote peer supplied. Both [InetAddress.getByName] and `InetSocketAddress(String, Int)`
 * fall back to DNS whenever their argument is not recognizably a literal, so neither may be
 * handed peer-controlled text: a malicious `pull.ok` carrying `host: "attacker.example.com"`
 * would otherwise make this device emit a DNS query off the local network before
 * [LanGuard] ever got a look at the resulting address.
 *
 * IPv4 is parsed here byte by byte and assembled with [InetAddress.getByAddress], which is
 * resolver-free by construction. IPv6 is first screened to a character set (hex digits,
 * `:`, `.`, `%` for a scope id) that no DNS name can consist of *and* required to contain a
 * colon - a string that passes cannot be a hostname, so the platform's literal parser is the
 * only path [InetAddress.getByName] can take for it.
 */
object IpLiteral {

    /** Returns the address [host] literally denotes, or null if it is not an IP literal. */
    fun parse(host: String): InetAddress? {
        if (host.isEmpty() || host.length > MAX_LENGTH) return null
        val bare = host.removeSurrounding("[", "]")
        parseIpv4(bare)?.let { return it }
        return parseIpv6(bare)
    }

    private fun parseIpv4(host: String): InetAddress? {
        val parts = host.split('.')
        if (parts.size != 4) return null
        val bytes = ByteArray(4)
        for ((i, part) in parts.withIndex()) {
            // Reject "01", "+1", " 1", "" and anything else that is not a plain 0-255 decimal:
            // leading zeros in particular are parsed as octal by some stacks, so two ends can
            // disagree about which address a string denotes.
            if (part.isEmpty() || part.length > 3) return null
            if (part.length > 1 && part[0] == '0') return null
            if (!part.all { it in '0'..'9' }) return null
            val value = part.toInt()
            if (value > 255) return null
            bytes[i] = value.toByte()
        }
        return InetAddress.getByAddress(bytes)
    }

    private fun parseIpv6(host: String): InetAddress? {
        if (!host.contains(':')) return null
        if (!host.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' || it == ':' || it == '.' || it == '%' }) {
            return null
        }
        // Character-set screened above: no DNS name can look like this, so getByName cannot
        // reach a resolver from here - it can only succeed via its literal parser or fail.
        return try {
            InetAddress.getByName(host)
        } catch (e: java.net.UnknownHostException) {
            null
        }
    }

    private const val MAX_LENGTH = 64
}
