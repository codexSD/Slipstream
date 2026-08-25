package com.slipstream.core.identity

import java.security.cert.X509Certificate
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.Base64

/**
 * PairedPeer represents a single paired peer device. Holds the peer's device ID,
 * fingerprint, and certificate.
 */
@Serializable
data class PairedPeer(
    val deviceId: String,
    val fingerprint: String,
    val certificateDER: String, // Base64-encoded DER certificate
) {
    constructor(
        deviceId: String,
        fingerprint: String,
        certificate: X509Certificate,
    ) : this(
        deviceId,
        fingerprint,
        Base64.getEncoder().encodeToString(certificate.encoded),
    )

    fun toCertificate(): X509Certificate {
        val derBytes = Base64.getDecoder().decode(certificateDER)
        val factory = java.security.cert.CertificateFactory.getInstance("X.509")
        return factory.generateCertificate(derBytes.inputStream()) as X509Certificate
    }

    companion object {
        fun fromJson(json: String): PairedPeer = Json.decodeFromString(json)
    }

    fun toJson(): String = Json.encodeToString(this)
}
