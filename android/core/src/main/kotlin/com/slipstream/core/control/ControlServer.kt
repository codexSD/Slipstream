package com.slipstream.core.control

import com.slipstream.core.SlipstreamPorts
import com.slipstream.core.identity.DeviceIdentity
import com.slipstream.core.identity.Fingerprint
import com.slipstream.core.identity.PairedPeerStore
import com.slipstream.core.net.LanGuard
import com.slipstream.core.net.NetworkInfo
import com.slipstream.core.pairing.PairingWindow
import java.net.InetSocketAddress
import java.security.cert.X509Certificate
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import kotlin.concurrent.thread

/**
 * Server side of the control channel (protocol.md §4, §9). Binds only to the current local
 * network's own address (never `0.0.0.0`), requires a client certificate but accepts *any*
 * certificate at the raw TLS layer, and only after the handshake completes compares the
 * accepted connection's peer fingerprint against [peerStore] — a mismatch is dropped
 * immediately, before [onPeerConnected] (and therefore any application code) ever sees it.
 * Inbound remote addresses are also checked against [LanGuard] before the handshake starts.
 *
 * pairing.md §1: an unpaired connection is only ever routed to [onPairingConnected] while
 * [pairingWindow] is open at the moment its fingerprint check fails; otherwise it is dropped
 * identically to normal operation. An already-paired peer always reaches [onPeerConnected],
 * never [onPairingConnected], regardless of whether a window happens to be open.
 */
class ControlServer(
    private val identity: DeviceIdentity,
    private val peerStore: PairedPeerStore,
    private val networkInfo: NetworkInfo,
    port: Int = SlipstreamPorts.CONTROL,
    private val pairingWindow: PairingWindow = PairingWindow(),
) : AutoCloseable {

    /** Invoked (on a background thread) for each connection that passes LanGuard, the TLS
     * handshake, and the post-handshake fingerprint check. */
    var onPeerConnected: ((ControlConnection) -> Unit)? = null

    /** Invoked (on a background thread) for a connection whose fingerprint does not match
     * the paired peer but was accepted anyway because [pairingWindow] was open. Never
     * invoked for an already-paired peer's connection - that always goes to
     * [onPeerConnected] instead. */
    var onPairingConnected: ((ControlConnection) -> Unit)? = null

    private val bindAddress = networkInfo.current()?.localAddress
        ?: throw IllegalStateException("No local network available to bind the control server to")

    private val serverSocket: SSLServerSocket =
        (PinnedTls.serverSocketFactory(identity).createServerSocket() as SSLServerSocket).apply {
            needClientAuth = true
            bind(InetSocketAddress(bindAddress, port))
        }

    /** The actual bound address+port, useful when constructed with `port = 0`. */
    val listenEndpoint: InetSocketAddress
        get() = InetSocketAddress(bindAddress, serverSocket.localPort)

    @Volatile
    private var running = true

    /**
     * The accept loop deliberately does NOT start in a property initializer: [onPeerConnected]
     * and [onPairingConnected] are assigned by the caller *after* construction, and a
     * connection landing in that window would be routed to a null callback - dropped silently
     * and, before this, leaked with its socket still open. The owner calls [start] once the
     * handlers are in place.
     */
    private var acceptThread: Thread? = null

    /** Begins accepting connections. Idempotent; call after assigning the handlers. */
    @Synchronized
    fun start(): ControlServer {
        if (acceptThread == null && running) {
            acceptThread = thread(name = "ControlServer-accept", isDaemon = true) { acceptLoop() }
        }
        return this
    }

    private fun acceptLoop() {
        while (running) {
            val socket = try {
                serverSocket.accept() as SSLSocket
            } catch (e: Exception) {
                if (running) continue else break
            }
            thread(name = "ControlServer-conn", isDaemon = true) { handleConnection(socket) }
        }
    }

    private fun handleConnection(socket: SSLSocket) {
        try {
            // Layer 2: refuse a non-local remote address before the handshake even starts.
            if (!LanGuard.isLocal(socket.inetAddress)) {
                socket.close()
                return
            }

            // TLS layer accepts any certificate; the real trust decision is the fingerprint
            // check below, which runs only after the handshake has fully completed.
            socket.startHandshake()

            val clientCert = socket.session.peerCertificates.firstOrNull() as? X509Certificate
            val fingerprint = clientCert?.let { Fingerprint.of(it) }
            val trusted = peerStore.peer?.fingerprint

            if (fingerprint != null && trusted != null && fingerprint == trusted) {
                // A null callback must still close the connection: returning without one
                // leaks the socket (and its accept-side file descriptor) for the process
                // lifetime, since nothing else holds a reference to it.
                val connection = ControlConnection(socket, fingerprint, clientCert)
                val handler = onPeerConnected
                if (handler == null) connection.close() else handler(connection)
                return
            }

            // Not (yet) a paired peer. Only route to the restricted pairing handler if a
            // window is open right now - otherwise this must be byte-for-byte identical to
            // normal operation: dropped before a single message is read.
            if (pairingWindow.isOpen) {
                val connection = ControlConnection(socket, fingerprint, clientCert)
                val handler = onPairingConnected
                if (handler == null) connection.close() else handler(connection)
                return
            }

            socket.close()
        } catch (e: Exception) {
            try { socket.close() } catch (_: Exception) {}
        }
    }

    override fun close() {
        running = false
        try { serverSocket.close() } catch (_: Exception) {}
        acceptThread?.interrupt()
    }
}
