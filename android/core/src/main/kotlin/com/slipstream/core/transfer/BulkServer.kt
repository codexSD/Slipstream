package com.slipstream.core.transfer

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import com.slipstream.core.net.LanGuard
import java.io.RandomAccessFile
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** Default bulk-transfer port per protocol/bulk-format.md. */
const val BULK_PORT = 53322

/** Matches [ServerSocket]'s own default backlog; only spelled out because binding to a
 * specific address requires the three-argument constructor. */
private const val ACCEPT_BACKLOG = 50

/**
 * Serves file ranges over the bulk protocol (protocol/bulk-format.md). Per spec: "A header
 * failing magic, version, or token validation is answered by closing the socket with no
 * reply." Every rejection path below does exactly that - it never writes anything back.
 *
 * Spec §11 layer 1: the listening socket is bound to [bindAddress] - the active network's own
 * local address in production - never the wildcard `0.0.0.0`, so the plaintext bulk protocol is
 * not reachable from any other interface. The default is loopback rather than the wildcard so
 * that a caller which forgets to supply an address fails closed, not open.
 *
 * Spec §11 layer 2: every accepted connection's remote address is checked against [LanGuard]
 * before a single header byte is read, and dropped with no reply if it isn't local - the same
 * "close silently" treatment as an invalid token or header.
 */
class BulkServer(
    private val tokenVault: TokenVault,
    private val fileForTransfer: (UUID) -> File?,
    port: Int = BULK_PORT,
    bindAddress: InetAddress = InetAddress.getLoopbackAddress(),
    /** The layer-2 predicate. Overridable only so a test can drive the rejection path - a
     * loopback client is always local, so there is no other way to observe it. */
    private val isLocalAddress: (InetAddress) -> Boolean = LanGuard::isLocal,
) : AutoCloseable {

    private val serverSocket = ServerSocket(port, ACCEPT_BACKLOG, bindAddress)
    val boundPort: Int get() = serverSocket.localPort

    /** The actual address+port this server listens on - never the wildcard address. */
    val listenEndpoint: InetSocketAddress
        get() = InetSocketAddress(serverSocket.inetAddress, serverSocket.localPort)

    private val executor: ExecutorService = Executors.newCachedThreadPool()
    @Volatile private var running = true

    private val acceptThread = thread(name = "BulkServer-accept", isDaemon = true) { acceptLoop() }

    private fun acceptLoop() {
        while (running) {
            val socket = try {
                serverSocket.accept()
            } catch (e: Exception) {
                if (running) continue else return
            }
            executor.submit { handleConnection(socket) }
        }
    }

    private fun handleConnection(socket: Socket) {
        socket.use {
            // Layer 2: refuse a non-local peer before reading anything from it.
            if (!isLocalAddress(socket.inetAddress)) return
            val header = readHeader(socket) ?: return
            if (header.version.toInt() != 1) return
            val token = tokenVault.validate(header.token, header.transferId) ?: return
            val file = fileForTransfer(header.transferId) ?: return
            if (token.sourcePath != file.path) return

            val output = DataOutputStream(socket.getOutputStream())
            RandomAccessFile(file, "r").use { raf ->
                var position = header.rangeStart
                var remaining = header.rangeLength
                while (remaining > 0) {
                    val len = minOf(remaining, header.chunkSize.toLong()).toInt()
                    val buf = ByteArray(len)
                    raf.seek(position)
                    raf.readFully(buf)

                    val crc = Crc32C.compute(buf)
                    output.writeInt(len)
                    output.write(buf)
                    output.writeInt(crc.toInt())

                    position += len
                    remaining -= len
                }
                output.flush()
            }
        }
    }

    private fun readHeader(socket: Socket): BulkFrameHeader? {
        return try {
            val bytes = ByteArray(64)
            DataInputStream(socket.getInputStream()).readFully(bytes)
            BulkFrameHeader.parse(bytes)
        } catch (e: Exception) {
            null
        }
    }

    override fun close() {
        running = false
        try { serverSocket.close() } catch (_: Exception) {}
        executor.shutdown()
        executor.awaitTermination(5, TimeUnit.SECONDS)
        acceptThread.join(2000)
    }
}
