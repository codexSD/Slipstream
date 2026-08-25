package com.slipstream.core.control

import com.slipstream.core.identity.DeviceIdentity
import com.slipstream.core.identity.Fingerprint
import com.slipstream.core.net.LanGuard
import com.slipstream.core.net.NetworkBinder
import java.net.InetSocketAddress
import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.KeyManager
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocketFactory
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/** Thrown when the peer's certificate fingerprint is not the pinned one. */
class UntrustedPeerException(message: String) : Exception(message)

/**
 * Fingerprint-pin-only TLS (protocol.md §4). Trust is never delegated to CA chain
 * validation — a self-signed certificate has no meaningful chain to validate — so both the
 * client and server SSLContext are built with a trust manager that accepts any certificate
 * at the raw handshake layer. The actual trust decision happens one level up:
 *
 * - [connect] (client side) checks the server's fingerprint against [isPinned] immediately
 *   after the handshake completes, and closes+throws if it doesn't match.
 * - The server side (see `ControlServer`) performs the equivalent post-handshake check
 *   against `PairedPeerStore`, dropping the connection before any message is read.
 */
object PinnedTls {

    /** Trusts any certificate chain at the TLS layer. Callers are responsible for checking
     * the peer's fingerprint themselves, after the handshake, against their own pin. */
    private fun acceptAllTrustManager(): X509TrustManager = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String) {}
        override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String) {}
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    private fun keyManagers(identity: DeviceIdentity): Array<KeyManager> {
        val keyStore = KeyStore.getInstance("PKCS12")
        keyStore.load(null, null)
        keyStore.setKeyEntry(
            "device",
            identity.privateKey,
            CharArray(0),
            arrayOf<java.security.cert.Certificate>(identity.certificate),
        )
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        kmf.init(keyStore, CharArray(0))
        return kmf.keyManagers
    }

    /** An [SSLContext] that presents [identity]'s certificate and accepts any peer
     * certificate at the TLS layer (fingerprint pinning happens above this). */
    fun sslContext(identity: DeviceIdentity): SSLContext {
        val context = SSLContext.getInstance("TLS")
        context.init(keyManagers(identity), arrayOf<TrustManager>(acceptAllTrustManager()), SecureRandom())
        return context
    }

    fun serverSocketFactory(identity: DeviceIdentity): SSLServerSocketFactory =
        sslContext(identity).serverSocketFactory

    fun socketFactory(identity: DeviceIdentity): SSLSocketFactory =
        sslContext(identity).socketFactory

    /**
     * Client-side connect: refuses a non-local [endpoint] ([LanGuard]), opens a TCP+TLS
     * connection presenting [identity]'s certificate as the client certificate, and accepts
     * the server's certificate only if [isPinned] returns true for its SHA-256 fingerprint.
     * Throws (and closes the socket) if the target is non-local, the handshake fails, or the
     * server's fingerprint is not accepted.
     */
    fun connect(
        endpoint: InetSocketAddress,
        identity: DeviceIdentity,
        binder: NetworkBinder = NetworkBinder.NONE,
        isPinned: (String) -> Boolean,
    ): SSLSocket {
        LanGuard.ensureLocal(endpoint.address)

        // Created unconnected so the socket can be bound to a specific Network (spec §11
        // layer 3) before the TCP handshake begins - binding after connect() is too late.
        val socket = socketFactory(identity).createSocket() as SSLSocket
        try {
            binder.bind(socket)
            socket.connect(endpoint)
            socket.useClientMode = true
            socket.startHandshake()

            val serverCert = socket.session.peerCertificates.firstOrNull() as? X509Certificate
                ?: throw UntrustedPeerException("Server presented no certificate")
            val fingerprint = Fingerprint.of(serverCert)
            if (!isPinned(fingerprint)) {
                throw UntrustedPeerException("Server fingerprint $fingerprint is not pinned")
            }
            return socket
        } catch (e: Exception) {
            try { socket.close() } catch (_: Exception) {}
            throw e
        }
    }
}
