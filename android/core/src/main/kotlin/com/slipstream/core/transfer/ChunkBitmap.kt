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

    /** True when every chunk in range is marked complete. */
    fun isComplete(): Boolean {
        for (i in 0 until chunkCount) if (!this[i]) return false
        return true
    }

    /** Defensive copy of the raw little-endian-per-byte bitmap bytes, for sidecar persistence. */
    fun rawBytes(): ByteArray = data.copyOf()

    /** Contiguous runs of chunk indices that are not yet complete, in ascending order. */
    fun missingRanges(): List<IntRange> {
        val ranges = mutableListOf<IntRange>()
        var start = -1
        for (i in 0 until chunkCount) {
            if (!this[i]) {
                if (start == -1) start = i
            } else if (start != -1) {
                ranges.add(start until i)
                start = -1
            }
        }
        if (start != -1) ranges.add(start until chunkCount)
        return ranges
    }

    companion object {
        fun chunkCountFor(fileSize: Long, chunkSize: Int): Int {
            require(fileSize >= 0) { "file size cannot be negative" }
            require(chunkSize > 0) { "chunk size must be positive" }
            return ceil(fileSize.toDouble() / chunkSize).toInt()
        }

        /** Restores a bitmap from previously persisted raw bytes (e.g. a sidecar file). */
        fun fromBytes(bytes: ByteArray, chunkCount: Int): ChunkBitmap {
            val bitmap = ChunkBitmap(chunkCount)
            val byteCount = minOf(bytes.size, bitmap.data.size)
            System.arraycopy(bytes, 0, bitmap.data, 0, byteCount)
            return bitmap
        }
    }
}
