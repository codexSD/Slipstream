package com.slipstream.core.control

import com.slipstream.core.SlipstreamLog
import java.net.Socket
import java.security.cert.X509Certificate

/**
 * A framed control-channel connection: JSON-lines messages over an already-established
 * (and, for real connections, already pinned-TLS) socket. Thread-safe for concurrent
 * send/receive from different threads (one writer, one reader), but not for concurrent
 * writers among themselves beyond simple serialization.
 *
 * [verifiedFingerprint] is the fingerprint of the peer certificate as verified by the TLS
 * handshake at the time this connection was accepted (e.g. by [ControlServer]) - it is null
 * only for connections with no TLS-verified peer identity (such as ones constructed directly
 * around a plain, non-TLS socket in tests). Callers that need to know who they're actually
 * talking to (for example, pairing code deriving trust from the handshake) MUST use this
 * value rather than any fingerprint claimed inside a message payload received over the wire.
 */
class ControlConnection(
    private val socket: Socket,
    val verifiedFingerprint: String? = null,
    /** The peer's X.509 certificate exactly as presented during that same handshake, for
     * callers that must persist it (pairing does: a [com.slipstream.core.identity.PairedPeer]
     * stores the certificate itself, not just its fingerprint). Null under the same conditions
     * as [verifiedFingerprint]. */
    val peerCertificate: X509Certificate? = null,
) : AutoCloseable {
    private val input = socket.getInputStream()
    private val output = socket.getOutputStream()

    fun send(message: ControlMessage) {
        SlipstreamLog.i("wire", "-> ${message.type} id=${message.id} ${describe(message)}")
        JsonLineCodec.writeMessage(output, message)
    }

    /** Payload keys only, never values: a listing carries the user's file names and a clipboard
     * message carries whatever they copied, and neither belongs in a log. */
    private fun describe(message: ControlMessage): String =
        message.payload?.keys?.joinToString(",", "{", "}") ?: "{}"

    /**
     * Returns the next message, or null if the peer closed the connection.
     *
     * Per protocol.md §5, a line exceeding the read cap is the one framing violation that is
     * fatal, since the codec cannot resynchronize mid-line. This guarantees the underlying
     * socket is closed before [LineTooLargeException] propagates to the caller, regardless of
     * what the caller does with the exception.
     */
    fun receive(): ControlMessage? {
        try {
            val message = JsonLineCodec.readMessage(input)
            if (message == null) {
                SlipstreamLog.i("wire", "<- (peer closed the connection)")
            } else {
                SlipstreamLog.i("wire", "<- ${message.type} id=${message.id} ${describe(message)}")
            }
            return message
        } catch (e: LineTooLargeException) {
            close()
            throw e
        }
    }

    val isClosed: Boolean
        get() = socket.isClosed

    override fun close() {
        try { socket.close() } catch (_: Exception) {}
    }
}
