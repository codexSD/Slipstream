package com.slipstream.core.media

import com.slipstream.core.SlipstreamPorts
import com.slipstream.core.net.LanGuard
import java.io.BufferedOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * A media-serving authorization for one file, per design.md §8: "Tokens expire 12 hours after
 * issue or on app restart, whichever is first." This is a distinct lifetime rule from the bulk
 * transfer token's 5-minute TTL (see [com.slipstream.core.transfer.TokenVault]) - media tokens
 * are meant to outlive a single playback session (seeking re-requests ranges under the same
 * URL) and never persist across an app restart, since [MediaTokenVault] holds them only in
 * memory.
 */
data class MediaToken(val value: UUID, val file: File, val mime: String, val expiresAtMs: Long)

/** Matches [ServerSocket]'s own default backlog; only spelled out because binding to a
 * specific address requires the three-argument constructor. */
private const val ACCEPT_BACKLOG = 50

/** Issues and validates [MediaToken]s. Thread-safe. Not single-use - a playback session and a
 * seek within it both reuse the same token, and it may back multiple concurrent Range requests. */
class MediaTokenVault(private val nowMs: () -> Long = System::currentTimeMillis) {

    private val tokens = ConcurrentHashMap<UUID, MediaToken>()

    fun issue(file: File, mime: String): MediaToken {
        val token = MediaToken(
            value = UUID.randomUUID(),
            file = file,
            mime = mime,
            expiresAtMs = nowMs() + TTL_MS,
        )
        tokens[token.value] = token
        return token
    }

    /** Returns the token record when [tokenValue] is known and unexpired, else null. */
    fun validate(tokenValue: UUID): MediaToken? {
        val token = tokens[tokenValue] ?: return null
        if (nowMs() > token.expiresAtMs) {
            tokens.remove(tokenValue)
            return null
        }
        return token
    }

    /** Drops every issued token, e.g. on app restart per the "or on app restart" clause. */
    fun clear() = tokens.clear()

    companion object {
        const val TTL_MS = 12 * 60 * 60 * 1000L
    }
}

/**
 * Serves file bytes (media files and cached thumbnails alike) over plain HTTP/1.1 on
 * [SlipstreamPorts.MEDIA], per design.md §8-9: whole-file `200` with `Accept-Ranges: bytes`,
 * single-range `Range` requests answered `206` with `Content-Range`, and unsatisfiable ranges
 * answered `416`. Every URL path is `/media/<token>`; an unknown or expired token is `404`.
 *
 * Spec §11 layer 1: the listening socket is bound to [bindAddress] - the active network's own
 * local address in production - never the wildcard `0.0.0.0`, so this plaintext HTTP endpoint is
 * not reachable from any other interface. The default is loopback rather than the wildcard so a
 * caller which forgets to supply an address fails closed, not open.
 *
 * Spec §11 layer 2: every accepted connection's remote address is checked against [LanGuard]
 * before its request line is read; a non-local peer is dropped with no reply at all (not even a
 * 404), matching how the bulk server treats an invalid header.
 */
class MediaServer(
    private val tokenVault: MediaTokenVault,
    port: Int = SlipstreamPorts.MEDIA,
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

    private val acceptThread = thread(name = "MediaServer-accept", isDaemon = true) { acceptLoop() }

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
            try {
                // Layer 2: refuse a non-local peer before reading (or writing) anything.
                if (!isLocalAddress(socket.inetAddress)) return
                val input = socket.getInputStream()
                val output = BufferedOutputStream(socket.getOutputStream())
                val request = readRequest(input) ?: return
                serve(request, output)
                output.flush()
            } catch (e: Exception) {
                // Malformed request or client disconnect mid-response - drop silently.
            }
        }
    }

    private data class Request(val path: String, val headers: Map<String, String>)

    private fun readRequest(input: InputStream): Request? {
        val requestLine = readLine(input) ?: return null
        val parts = requestLine.split(" ")
        if (parts.size < 2) return null
        val path = parts[1]

        val headers = mutableMapOf<String, String>()
        while (true) {
            val line = readLine(input) ?: break
            if (line.isEmpty()) break
            val idx = line.indexOf(':')
            if (idx <= 0) continue
            headers[line.substring(0, idx).trim().lowercase()] = line.substring(idx + 1).trim()
        }
        return Request(path, headers)
    }

    private fun readLine(input: InputStream): String? {
        val buf = StringBuilder()
        var readAny = false
        while (true) {
            val b = input.read()
            if (b == -1) return if (readAny) buf.toString() else null
            readAny = true
            if (b == '\n'.code) {
                if (buf.isNotEmpty() && buf.last() == '\r') buf.setLength(buf.length - 1)
                return buf.toString()
            }
            buf.append(b.toChar())
        }
    }

    private fun serve(request: Request, output: OutputStream) {
        val token = tokenFromPath(request.path)
        val media = token?.let { tokenVault.validate(it) }
        if (media == null) {
            writeStatusOnly(output, 404, "Not Found")
            return
        }

        val file = media.file
        val length = file.length()
        when (val result = RangeHeader.parse(request.headers["range"], length)) {
            is RangeHeader.ParseResult.Absent -> serveWhole(file, media.mime, output)
            is RangeHeader.ParseResult.Unsatisfiable -> {
                writeHeaders(
                    output, 416, "Range Not Satisfiable",
                    listOf("Content-Range" to "bytes */$length"),
                )
            }
            is RangeHeader.ParseResult.Satisfiable ->
                servePartial(file, media.mime, result.range, output)
        }
    }

    private fun tokenFromPath(path: String): UUID? {
        val prefix = TOKEN_PATH_PREFIXES.firstOrNull { path.startsWith(it) } ?: return null
        return try {
            UUID.fromString(path.substring(prefix.length).substringBefore('?'))
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    private fun serveWhole(file: File, mime: String, output: OutputStream) {
        val length = file.length()
        writeHeaders(
            output, 200, "OK",
            listOf(
                "Content-Type" to mime,
                "Content-Length" to length.toString(),
                "Accept-Ranges" to "bytes",
            ),
        )
        RandomAccessFile(file, "r").use { raf ->
            copyRange(raf, 0, length, output)
        }
    }

    private fun servePartial(file: File, mime: String, range: RangeHeader.Range, output: OutputStream) {
        val length = file.length()
        writeHeaders(
            output, 206, "Partial Content",
            listOf(
                "Content-Type" to mime,
                "Content-Length" to range.length.toString(),
                "Content-Range" to "bytes ${range.start}-${range.end}/$length",
                "Accept-Ranges" to "bytes",
            ),
        )
        RandomAccessFile(file, "r").use { raf ->
            copyRange(raf, range.start, range.length, output)
        }
    }

    private fun copyRange(raf: RandomAccessFile, start: Long, count: Long, output: OutputStream) {
        raf.seek(start)
        val buffer = ByteArray(64 * 1024)
        var remaining = count
        while (remaining > 0) {
            val toRead = minOf(remaining, buffer.size.toLong()).toInt()
            val read = raf.read(buffer, 0, toRead)
            if (read == -1) break
            output.write(buffer, 0, read)
            remaining -= read
        }
    }

    private fun writeStatusOnly(output: OutputStream, code: Int, reason: String) {
        writeHeaders(output, code, reason, emptyList())
    }

    private fun writeHeaders(output: OutputStream, code: Int, reason: String, headers: List<Pair<String, String>>) {
        val sb = StringBuilder()
        sb.append("HTTP/1.1 $code $reason\r\n")
        for ((name, value) in headers) sb.append("$name: $value\r\n")
        sb.append("Connection: close\r\n")
        sb.append("\r\n")
        output.write(sb.toString().toByteArray(StandardCharsets.US_ASCII))
    }

    override fun close() {
        running = false
        try { serverSocket.close() } catch (_: Exception) {}
        executor.shutdown()
        executor.awaitTermination(5, TimeUnit.SECONDS)
        acceptThread.join(2000)
    }

    private companion object {
        /** Two path prefixes route to the same `tokenVault.validate`/`serve` logic - media
         * files and cached thumbnails are both just bytes behind a token (design.md §9). */
        val TOKEN_PATH_PREFIXES = listOf("/media/", "/thumb/")
    }
}
