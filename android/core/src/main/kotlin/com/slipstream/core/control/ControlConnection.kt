package com.slipstream.core.control

import java.net.Socket

/**
 * A framed control-channel connection: JSON-lines messages over an already-established
 * (and, for real connections, already pinned-TLS) socket. Thread-safe for concurrent
 * send/receive from different threads (one writer, one reader), but not for concurrent
 * writers among themselves beyond simple serialization.
 */
class ControlConnection(private val socket: Socket) : AutoCloseable {
    private val input = socket.getInputStream()
    private val output = socket.getOutputStream()

    fun send(message: ControlMessage) = JsonLineCodec.writeMessage(output, message)

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
            return JsonLineCodec.readMessage(input)
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
