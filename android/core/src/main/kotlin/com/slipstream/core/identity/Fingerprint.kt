package com.slipstream.core.identity

import java.security.cert.X509Certificate
import java.security.MessageDigest

/**
 * Fingerprint is a lowercase hex SHA-256 of a certificate's DER encoding.
 * Always 64 characters (256 bits = 32 bytes = 64 hex digits).
 */
object Fingerprint {
    fun of(cert: X509Certificate): String {
        val derEncoded = cert.encoded
        val digest = MessageDigest.getInstance("SHA-256").digest(derEncoded)
        return digest.joinToString("") { "%02x".format(it) }
    }
}
