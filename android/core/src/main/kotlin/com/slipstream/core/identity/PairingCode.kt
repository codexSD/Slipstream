package com.slipstream.core.identity

import java.nio.ByteBuffer
import java.security.MessageDigest

/**
 * Spec §4. Sorting the two fingerprints before hashing makes the derivation
 * order-independent, so both devices compute the same code without negotiating who
 * is "first". Must match protocol/vectors/pairing-codes.json exactly — the C# side
 * already does.
 */
object PairingCode {
    fun derive(fingerprintA: String, fingerprintB: String): String {
        val a = fingerprintA.trim().lowercase()
        val b = fingerprintB.trim().lowercase()
        val (first, second) = if (a <= b) a to b else b to a

        val digest = MessageDigest.getInstance("SHA-256")
            .digest((first + second).toByteArray(Charsets.US_ASCII))

        // First four bytes, big-endian, as an unsigned 32-bit value.
        val value = ByteBuffer.wrap(digest, 0, 4).int.toUInt()

        return (value % 1_000_000u).toString().padStart(6, '0')
    }
}
