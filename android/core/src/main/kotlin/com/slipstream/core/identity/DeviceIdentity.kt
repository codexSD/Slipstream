package com.slipstream.core.identity

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

/**
 * DeviceIdentity holds the persistent identity of this device: a unique ID, display name,
 * self-signed certificate, and corresponding private key. Instances are loaded from or
 * created in a PKCS#12 keystore file.
 *
 * The self-signed X.509 certificate is built with a minimal hand-rolled DER encoder using
 * only JDK/Android platform APIs (no third-party crypto libraries), per the project's global
 * "platform APIs only" crypto constraint.
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
        private const val DEVICE_ID_FILENAME = "identity.deviceid"
        private const val KEYSTORE_PASSWORD = ""
        private const val KEYSTORE_ALIAS = "device"
        private const val CERT_VALIDITY_DAYS = 36500L // 100 years

        fun createNew(displayName: String): DeviceIdentity {
            val deviceId = UUID.randomUUID().toString()
                .replace("-", "")
                .take(32)

            val keyPair = generateKeyPair()
            val cert = createSelfSignedCertificate(keyPair, displayName)

            return DeviceIdentity(deviceId, displayName, cert, keyPair.private)
        }

        fun loadOrCreate(dir: File, displayName: String): DeviceIdentity {
            dir.mkdirs()
            val keystorePath = File(dir, KEYSTORE_FILENAME)
            val deviceIdPath = File(dir, DEVICE_ID_FILENAME)

            return if (keystorePath.exists()) {
                load(keystorePath, deviceIdPath)
            } else {
                val identity = createNew(displayName)
                identity.save(keystorePath, deviceIdPath)
                identity
            }
        }

        private fun load(keystorePath: File, deviceIdPath: File): DeviceIdentity {
            val keystore = KeyStore.getInstance("PKCS12")
            FileInputStream(keystorePath).use {
                keystore.load(it, KEYSTORE_PASSWORD.toCharArray())
            }

            val cert = keystore.getCertificate(KEYSTORE_ALIAS) as X509Certificate
            val privateKey = keystore.getKey(KEYSTORE_ALIAS, KEYSTORE_PASSWORD.toCharArray()) as PrivateKey

            // deviceId is persisted verbatim in a sidecar file alongside the keystore so that
            // it round-trips exactly (it is not derived from, or parsed out of, the certificate).
            val deviceId = deviceIdPath.readText().trim()
            val displayName = extractDisplayNameFromCert(cert)

            return DeviceIdentity(deviceId, displayName, cert, privateKey)
        }

        private fun extractDisplayNameFromCert(cert: X509Certificate): String {
            // Parse CN from subject name
            val subjectName = cert.subjectX500Principal.name
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
            keyGen.initialize(256) // P-256 is 256-bit
            return keyGen.generateKeyPair()
        }

        private fun createSelfSignedCertificate(
            keyPair: KeyPair,
            displayName: String,
        ): X509Certificate {
            val now = Date()
            val notAfter = Date(System.currentTimeMillis() + CERT_VALIDITY_DAYS * 24 * 60 * 60 * 1000)
            val serial = BigInteger(64, java.util.Random())

            val der = MinimalX509.buildSelfSignedCertificate(
                keyPair = keyPair,
                subjectCn = displayName,
                serial = serial,
                notBefore = now,
                notAfter = notAfter,
            )

            val cf = CertificateFactory.getInstance("X.509")
            return cf.generateCertificate(ByteArrayInputStream(der)) as X509Certificate
        }

        private fun DeviceIdentity.save(keystorePath: File, deviceIdPath: File) {
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

            deviceIdPath.writeText(deviceId)
        }
    }
}

/**
 * A minimal, dependency-free DER encoder sufficient to build a self-signed X.509 v3
 * certificate for an EC (P-256) key, signed with SHA256withECDSA. Deliberately supports
 * only the small subset of ASN.1 constructs Slipstream needs (no external crypto library).
 */
private object MinimalX509 {
    private val UTC = TimeZone.getTimeZone("UTC")

    fun buildSelfSignedCertificate(
        keyPair: KeyPair,
        subjectCn: String,
        serial: BigInteger,
        notBefore: Date,
        notAfter: Date,
    ): ByteArray {
        val name = buildName(subjectCn)
        val sigAlgId = sequence(oid(OID_ECDSA_WITH_SHA256))
        val spki = keyPair.public.encoded // already a DER SubjectPublicKeyInfo

        val tbs = sequence(
            contextExplicit(0, integer(BigInteger.valueOf(2))), // version v3
            integer(serial),
            sigAlgId,
            name, // issuer
            sequence(generalizedTime(notBefore), generalizedTime(notAfter)), // validity
            name, // subject (self-signed)
            spki,
            contextExplicit(3, sequence(keyUsageExtension())),
        )

        val signature = Signature.getInstance("SHA256withECDSA").apply {
            initSign(keyPair.private)
            update(tbs)
        }.sign()

        return sequence(tbs, sigAlgId, bitString(signature))
    }

    private fun keyUsageExtension(): ByteArray {
        // KeyUsage: digitalSignature (bit 0) | keyEncipherment (bit 2) -> 1010 0000, 5 unused bits
        val bits = bitString(byteArrayOf(0xA0.toByte()), unusedBits = 5)
        return sequence(oid(OID_KEY_USAGE), boolean(true), octetString(bits))
    }

    private fun buildName(cn: String): ByteArray {
        val attr = sequence(oid(OID_COMMON_NAME), utf8String(cn))
        val rdn = tlv(0x31, attr) // SET
        return sequence(rdn) // RDNSequence == Name
    }

    // --- DER primitives ---

    private fun tlv(tag: Int, content: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(tag)
        writeLength(out, content.size)
        out.write(content)
        return out.toByteArray()
    }

    private fun writeLength(out: ByteArrayOutputStream, length: Int) {
        if (length < 0x80) {
            out.write(length)
        } else {
            val bytes = ByteArrayOutputStream()
            var l = length
            while (l > 0) {
                bytes.write(l and 0xFF)
                l = l ushr 8
            }
            val lenBytes = bytes.toByteArray().reversedArray()
            out.write(0x80 or lenBytes.size)
            out.write(lenBytes)
        }
    }

    private fun sequence(vararg elements: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        for (e in elements) out.write(e)
        return tlv(0x30, out.toByteArray())
    }

    private fun contextExplicit(tagNum: Int, content: ByteArray): ByteArray =
        tlv(0xA0 or tagNum, content)

    private fun integer(value: BigInteger): ByteArray = tlv(0x02, value.toByteArray())

    private fun boolean(value: Boolean): ByteArray = tlv(0x01, byteArrayOf(if (value) 0xFF.toByte() else 0x00))

    private fun bitString(content: ByteArray, unusedBits: Int = 0): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(unusedBits)
        out.write(content)
        return tlv(0x03, out.toByteArray())
    }

    private fun octetString(content: ByteArray): ByteArray = tlv(0x04, content)

    private fun utf8String(s: String): ByteArray = tlv(0x0C, s.toByteArray(Charsets.UTF_8))

    private fun generalizedTime(date: Date): ByteArray {
        val fmt = SimpleDateFormat("yyyyMMddHHmmss'Z'", Locale.US).apply { timeZone = UTC }
        return tlv(0x18, fmt.format(date).toByteArray(Charsets.US_ASCII))
    }

    private fun oid(dotted: String): ByteArray {
        val parts = dotted.split(".").map { it.toInt() }
        val out = ByteArrayOutputStream()
        out.write(parts[0] * 40 + parts[1])
        for (i in 2 until parts.size) {
            out.write(encodeOidArc(parts[i]))
        }
        return tlv(0x06, out.toByteArray())
    }

    private fun encodeOidArc(value: Int): ByteArray {
        if (value == 0) return byteArrayOf(0)
        var v = value
        val bytes = mutableListOf<Int>()
        while (v > 0) {
            bytes.add(0, v and 0x7F)
            v = v ushr 7
        }
        for (i in 0 until bytes.size - 1) {
            bytes[i] = bytes[i] or 0x80
        }
        val out = ByteArrayOutputStream()
        bytes.forEach { out.write(it) }
        return out.toByteArray()
    }

    private const val OID_COMMON_NAME = "2.5.4.3"
    private const val OID_KEY_USAGE = "2.5.29.15"
    private const val OID_ECDSA_WITH_SHA256 = "1.2.840.10045.4.3.2"
}
