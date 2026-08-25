package com.slipstream.core.control

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import kotlinx.serialization.json.Json

/** Thrown when a line would exceed, or does exceed, [JsonLineCodec.MAX_LINE_BYTES]. */
class LineTooLargeException(message: String) : Exception(message)

/**
 * JSON-lines framing over a raw byte stream (protocol.md §5): one UTF-8 JSON object per
 * `\n`-terminated line, a trailing `\r` tolerated and stripped. Reads a byte at a time —
 * control messages are small and infrequent, and this avoids ever having to hand back
 * over-read bytes if the underlying socket is later repurposed for a bulk transfer.
 */
object JsonLineCodec {
    const val MAX_LINE_BYTES = 1_048_576

    private val json = Json { ignoreUnknownKeys = true }

    /** Throws [LineTooLargeException] before writing anything if the encoded line would
     * exceed the cap. */
    fun writeMessage(out: OutputStream, message: ControlMessage) {
        val line = json.encodeToString(ControlMessage.serializer(), message)
        val bytes = line.toByteArray(Charsets.UTF_8)
        if (bytes.size > MAX_LINE_BYTES) {
            throw LineTooLargeException(
                "Line of ${bytes.size} bytes exceeds the ${MAX_LINE_BYTES}-byte cap",
            )
        }
        synchronized(out) {
            out.write(bytes)
            out.write('\n'.code)
            out.flush()
        }
    }

    /**
     * Reads the next well-formed message, silently skipping blank lines and lines that fail
     * to parse as JSON or have a missing/blank `type`. Returns `null` on end of stream.
     * Throws [LineTooLargeException] (fatal — the connection must be torn down) if a line
     * exceeds the cap while being read.
     */
    fun readMessage(input: InputStream): ControlMessage? {
        while (true) {
            val line = readLine(input) ?: return null
            if (line.isBlank()) continue

            val message = try {
                json.decodeFromString(ControlMessage.serializer(), line)
            } catch (e: Exception) {
                continue
            }
            if (message.type.isBlank()) continue
            return message
        }
    }

    /** Returns the next line (without terminator), or null on end of stream with no bytes read. */
    private fun readLine(input: InputStream): String? {
        val buffer = ByteArrayOutputStream()
        var sawAny = false
        while (true) {
            val b = input.read()
            if (b == -1) {
                return if (sawAny) finish(buffer) else null
            }
            sawAny = true
            if (b == '\n'.code) {
                return finish(buffer)
            }
            buffer.write(b)
            if (buffer.size() > MAX_LINE_BYTES) {
                throw LineTooLargeException(
                    "Line exceeded the ${MAX_LINE_BYTES}-byte cap while reading",
                )
            }
        }
    }

    private fun finish(buffer: ByteArrayOutputStream): String {
        var bytes = buffer.toByteArray()
        if (bytes.isNotEmpty() && bytes[bytes.size - 1] == '\r'.code.toByte()) {
            bytes = bytes.copyOf(bytes.size - 1)
        }
        return String(bytes, Charsets.UTF_8)
    }
}
