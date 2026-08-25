package com.slipstream.core.transfer

import java.util.Base64
import kotlin.math.ceil

/**
 * Bitmap representing which chunks are complete.
 * Little-endian bit order: bit i of byte n is chunk (n*8 + i).
 */
class ChunkBitmap(private val chunkCount: Int) {
    private val byteCount = ceil(chunkCount / 8.0).toInt()
    private val data = ByteArray(byteCount)

    operator fun set(chunkIndex: Int, complete: Boolean) {
        require(chunkIndex >= 0 && chunkIndex < chunkCount) { "chunk index out of range" }
        val byteIndex = chunkIndex / 8
        val bitIndex = chunkIndex % 8
        if (complete) {
            data[byteIndex] = (data[byteIndex].toInt() or (1 shl bitIndex)).toByte()
        } else {
            data[byteIndex] = (data[byteIndex].toInt() and (1 shl bitIndex).inv()).toByte()
        }
    }

    operator fun get(chunkIndex: Int): Boolean {
        require(chunkIndex >= 0 && chunkIndex < chunkCount) { "chunk index out of range" }
        val byteIndex = chunkIndex / 8
        val bitIndex = chunkIndex % 8
        return (data[byteIndex].toInt() and (1 shl bitIndex)) != 0
    }

    fun toBase64(): String = Base64.getEncoder().encodeToString(data)

    companion object {
        fun chunkCountFor(fileSize: Long, chunkSize: Int): Int {
            require(fileSize >= 0) { "file size cannot be negative" }
            require(chunkSize > 0) { "chunk size must be positive" }
            return ceil(fileSize.toDouble() / chunkSize).toInt()
        }
    }
}
