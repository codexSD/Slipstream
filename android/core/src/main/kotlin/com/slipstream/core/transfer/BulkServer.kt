package com.slipstream.core.transfer

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** Default bulk-transfer port per protocol/bulk-format.md. */
const val BULK_PORT = 53322

/**
 * Serves file ranges over the bulk protocol (protocol/bulk-format.md). Per spec: "A header
 * failing magic, version, or token validation is answered by closing the socket with no
 * reply." Every rejection path below does exactly that - it never writes anything back.
 */
class BulkServer(
    private val tokenVault: TokenVault,
    private val fileForTransfer: (UUID) -> File?,
    port: Int = BULK_PORT,
) : AutoCloseable {

    private val serverSocket = ServerSocket(port)
    val boundPort: Int get() = serverSocket.localPort

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
