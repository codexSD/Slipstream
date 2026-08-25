package com.slipstream.core.media

import java.io.BufferedInputStream
import java.io.File
import java.net.InetAddress
import java.net.Socket
import kotlin.io.path.createTempDirectory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.Assert.assertArrayEquals

private val LOOPBACK: InetAddress = InetAddress.getByName("127.0.0.1")

private data class HttpResponse(val status: Int, val headers: Map<String, String>, val body: ByteArray)

/** A tiny raw HTTP/1.1 client sufficient to exercise [MediaServer] without extra dependencies. */
private fun rawHttpGet(port: Int, path: String, rangeHeader: String? = null): HttpResponse {
    Socket(LOOPBACK, port).use { socket ->
        val request = buildString {
            append("GET $path HTTP/1.1\r\n")
            append("Host: localhost\r\n")
            if (rangeHeader != null) append("Range: $rangeHeader\r\n")
            append("\r\n")
        }
        socket.getOutputStream().write(request.toByteArray(Charsets.US_ASCII))
        socket.getOutputStream().flush()

        val input = BufferedInputStream(socket.getInputStream())
        val statusLine = readLine(input) ?: error("no status line")
        val status = statusLine.split(" ")[1].toInt()

        val headers = mutableMapOf<String, String>()
        while (true) {
            val line = readLine(input) ?: break
            if (line.isEmpty()) break
            val idx = line.indexOf(':')
            if (idx > 0) headers[line.substring(0, idx).trim().lowercase()] = line.substring(idx + 1).trim()
        }

        val contentLength = headers["content-length"]?.toIntOrNull() ?: 0
        val body = ByteArray(contentLength)
        var read = 0
        while (read < contentLength) {
            val n = input.read(body, read, contentLength - read)
            if (n == -1) break
            read += n
        }
        return HttpResponse(status, headers, body.copyOf(read))
    }
}

private fun readLine(input: BufferedInputStream): String? {
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

class MediaServerTest {

    private fun makeFile(bytes: ByteArray): File {
        val dir = createTempDirectory().toFile()
        val file = File(dir, "media.bin")
        file.writeBytes(bytes)
        return file
    }

    @Test
    fun `whole file request returns 200 with Accept-Ranges and full body`() {
        val bytes = ByteArray(1000) { it.toByte() }
        val vault = MediaTokenVault()
        val server = MediaServer(vault, port = 0)
        try {
            val token = vault.issue(makeFile(bytes), "application/octet-stream")
            val response = rawHttpGet(server.boundPort, "/media/${token.value}")

            assertEquals(200, response.status)
            assertEquals("bytes", response.headers["accept-ranges"])
            assertEquals("1000", response.headers["content-length"])
            assertArrayEquals(bytes, response.body)
        } finally {
            server.close()
        }
    }

    @Test
    fun `closed range request returns 206 with correct bytes and Content-Range`() {
        val bytes = ByteArray(1000) { it.toByte() }
        val vault = MediaTokenVault()
        val server = MediaServer(vault, port = 0)
        try {
            val token = vault.issue(makeFile(bytes), "application/octet-stream")
            val response = rawHttpGet(server.boundPort, "/media/${token.value}", rangeHeader = "bytes=100-199")

            assertEquals(206, response.status)
            assertEquals("bytes 100-199/1000", response.headers["content-range"])
            assertEquals("100", response.headers["content-length"])
            assertArrayEquals(bytes.copyOfRange(100, 200), response.body)
        } finally {
            server.close()
        }
    }

    @Test
    fun `open-ended range request returns bytes to end of file`() {
        val bytes = ByteArray(1000) { it.toByte() }
        val vault = MediaTokenVault()
        val server = MediaServer(vault, port = 0)
        try {
            val token = vault.issue(makeFile(bytes), "application/octet-stream")
            val response = rawHttpGet(server.boundPort, "/media/${token.value}", rangeHeader = "bytes=900-")

            assertEquals(206, response.status)
            assertEquals("bytes 900-999/1000", response.headers["content-range"])
            assertArrayEquals(bytes.copyOfRange(900, 1000), response.body)
        } finally {
            server.close()
        }
    }

    @Test
    fun `suffix range request returns last N bytes`() {
        val bytes = ByteArray(1000) { it.toByte() }
        val vault = MediaTokenVault()
        val server = MediaServer(vault, port = 0)
        try {
            val token = vault.issue(makeFile(bytes), "application/octet-stream")
            val response = rawHttpGet(server.boundPort, "/media/${token.value}", rangeHeader = "bytes=-100")

            assertEquals(206, response.status)
            assertEquals("bytes 900-999/1000", response.headers["content-range"])
            assertArrayEquals(bytes.copyOfRange(900, 1000), response.body)
        } finally {
            server.close()
        }
    }

    @Test
    fun `unsatisfiable range returns 416`() {
        val bytes = ByteArray(1000) { it.toByte() }
        val vault = MediaTokenVault()
        val server = MediaServer(vault, port = 0)
        try {
            val token = vault.issue(makeFile(bytes), "application/octet-stream")
            val response = rawHttpGet(server.boundPort, "/media/${token.value}", rangeHeader = "bytes=2000-3000")

            assertEquals(416, response.status)
            assertEquals("bytes */1000", response.headers["content-range"])
            assertEquals(0, response.body.size)
        } finally {
            server.close()
        }
    }

    @Test
    fun `unknown token returns 404`() {
        val vault = MediaTokenVault()
        val server = MediaServer(vault, port = 0)
        try {
            val response = rawHttpGet(server.boundPort, "/media/${java.util.UUID.randomUUID()}")

            assertEquals(404, response.status)
        } finally {
            server.close()
        }
    }

    @Test
    fun `expired token returns 404`() {
        val bytes = ByteArray(10)
        var now = 0L
        val vault = MediaTokenVault(nowMs = { now })
        val server = MediaServer(vault, port = 0)
        try {
            val token = vault.issue(makeFile(bytes), "application/octet-stream")
            now += MediaTokenVault.TTL_MS + 1

            val response = rawHttpGet(server.boundPort, "/media/${token.value}")

            assertEquals(404, response.status)
        } finally {
            server.close()
        }
    }
}
