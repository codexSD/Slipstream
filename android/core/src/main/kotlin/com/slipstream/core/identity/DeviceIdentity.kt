package com.slipstream.core.identity

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.PublicKey
import java.security.cert.X509Certificate
import java.util.Date
import java.util.UUID
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder

/**
 * DeviceIdentity holds the persistent identity of this device: a unique ID, display name,
 * self-signed certificate, and corresponding private key. Instances are loaded from or
 * created in a PKCS#12 keystore file.
 */
data class DeviceIdentity(
    val deviceId: String,
    val displayName: String,
    val certificate: X509Certificate,
    val privateKey: PrivateKey,
) {
    val fingerprint: String
        get() = Fingerprint.of(certificate)

    companion object {
        private const val KEYSTORE_FILENAME = "identity.p12"
        private const val KEYSTORE_PASSWORD = ""
        private const val KEYSTORE_ALIAS = "device"
        private const val CERT_VALIDITY_DAYS = 36500L // 100 years

        fun createNew(displayName: String): DeviceIdentity {
            val deviceId = UUID.randomUUID().toString()
                .replace("-", "")
                .take(32)

            val keyPair = generateKeyPair()
            val cert = createSelfSignedCertificate(keyPair, deviceId, displayName)

            return DeviceIdentity(deviceId, displayName, cert, keyPair.private)
        }

        fun loadOrCreate(dir: File, displayName: String): DeviceIdentity {
            dir.mkdirs()
            val keystorePath = File(dir, KEYSTORE_FILENAME)

            return if (keystorePath.exists()) {
                load(keystorePath)
            } else {
                val identity = createNew(displayName)
                identity.save(keystorePath)
                identity
            }
        }

        private fun load(keystorePath: File): DeviceIdentity {
            val keystore = KeyStore.getInstance("PKCS12")
            FileInputStream(keystorePath).use {
                keystore.load(it, KEYSTORE_PASSWORD.toCharArray())
            }

            val cert = keystore.getCertificate(KEYSTORE_ALIAS) as X509Certificate
            val privateKey = keystore.getKey(KEYSTORE_ALIAS, KEYSTORE_PASSWORD.toCharArray()) as PrivateKey

            // Extract deviceId and displayName from certificate subject name
            val subjectName = cert.subjectX500Principal.name
            val deviceId = extractDeviceIdFromCert(cert)
            val displayName = extractDisplayNameFromCert(cert)

            return DeviceIdentity(deviceId, displayName, cert, privateKey)
        }

        private fun extractDeviceIdFromCert(cert: X509Certificate): String {
            // Extract from certificate subject CN extension or return default
            val subjectName = cert.subjectX500Principal.name
            // For now, return a placeholder - the deviceId should ideally be stored in an extension
            return "extracted"
        }

        private fun extractDisplayNameFromCert(cert: X509Certificate): String {
            // Extract CN from subject name
            val subjectName = cert.subjectX500Principal.name
            // Parse CN=value from the subject name
            val cnPrefix = "CN="
            val cnStart = subjectName.indexOf(cnPrefix)
            if (cnStart != -1) {
                val start = cnStart + cnPrefix.length
                val end = subjectName.indexOf(",", start).let { if (it == -1) subjectName.length else it }
                return subjectName.substring(start, end)
            }
            return "Device"
        }

        private fun generateKeyPair(): KeyPair {
            val keyGen = KeyPairGenerator.getInstance("EC")
            keyGen.initialize(256)  // P-256 is 256-bit
            return keyGen.generateKeyPair()
        }

        private fun createSelfSignedCertificate(
            keyPair: KeyPair,
            deviceId: String,
            displayName: String,
        ): X509Certificate {
            val now = Date()
            val notAfter = Date(System.currentTimeMillis() + CERT_VALIDITY_DAYS * 24 * 60 * 60 * 1000)

            val subject = X500Name("CN=$displayName")
            val serial = BigInteger(64, java.util.Random())

            val certBuilder = JcaX509v3CertificateBuilder(
                subject,  // issuer
                serial,
                now,
                notAfter,
                subject,  // subject
                keyPair.public,
            )

            val signer = JcaContentSignerBuilder("SHA256withECDSA")
                .build(keyPair.private)

            val certHolder = certBuilder.build(signer)
            return JcaX509CertificateConverter()
                .getCertificate(certHolder)
        }

        private fun DeviceIdentity.save(keystorePath: File) {
            val keystore = KeyStore.getInstance("PKCS12")
            keystore.load(null, null)

            // Store the certificate chain and private key
            val certChain = arrayOf(certificate as java.security.cert.Certificate)
            keystore.setKeyEntry(
                KEYSTORE_ALIAS,
                privateKey,
                KEYSTORE_PASSWORD.toCharArray(),
                certChain,
            )

            FileOutputStream(keystorePath).use {
                keystore.store(it, KEYSTORE_PASSWORD.toCharArray())
            }
        }
    }
}
