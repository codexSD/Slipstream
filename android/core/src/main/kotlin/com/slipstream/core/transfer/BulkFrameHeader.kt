package com.slipstream.core.transfer

import java.nio.ByteBuffer
import java.util.UUID

/**
 * 64-byte bulk stream frame header. All multi-byte integers are big-endian.
 *
 * Layout:
 * - 0-4: magic "SLPS"
 * - 4-6: version (UShort)
 * - 6-8: streamIndex (UShort)
 * - 8-24: token (UUID bytes)
 * - 24-40: transferId (UUID bytes)
 * - 40-48: rangeStart (Long)
 * - 48-56: rangeLength (Long)
 * - 56-60: chunkSize (Int)
 * - 60-64: reserved (must be zero)
 */
data class BulkFrameHeader(
    val version: UShort,
    val streamIndex: UShort,
    val token: UUID,
    val transferId: UUID,
    val rangeStart: Long,
    val rangeLength: Long,
    val chunkSize: Int,
) {

    fun toBytes(): ByteArray {
        val buf = ByteBuffer.allocate(64)

        // Magic "SLPS"
        buf.put("SLPS".toByteArray())

        // Version (big-endian UShort)
        buf.putShort(version.toShort())

        // StreamIndex (big-endian UShort)
        buf.putShort(streamIndex.toShort())

        // Token (UUID - 16 bytes)
        val tokenMsb = token.mostSignificantBits
        val tokenLsb = token.leastSignificantBits
        buf.putLong(tokenMsb)
        buf.putLong(tokenLsb)

        // TransferId (UUID - 16 bytes)
        val transferIdMsb = transferId.mostSignificantBits
        val transferIdLsb = transferId.leastSignificantBits
        buf.putLong(transferIdMsb)
        buf.putLong(transferIdLsb)

        // RangeStart (big-endian Long)
        buf.putLong(rangeStart)

        // RangeLength (big-endian Long)
        buf.putLong(rangeLength)

        // ChunkSize (big-endian Int)
        buf.putInt(chunkSize)

        // Reserved (4 zero bytes)
        buf.putInt(0)

        return buf.array()
    }

    companion object {
        fun parse(bytes: ByteArray): BulkFrameHeader {
            require(bytes.size == 64) { "Header must be exactly 64 bytes, got ${bytes.size}" }

            val buf = ByteBuffer.wrap(bytes)

            // Magic
            val magic = ByteArray(4)
            buf.get(magic)
            require(magic.contentEquals("SLPS".toByteArray())) { "Invalid magic" }

            // Version
            val version = buf.short.toUShort()

            // StreamIndex
            val streamIndex = buf.short.toUShort()

            // Token
            val tokenMsb = buf.long
            val tokenLsb = buf.long
            val token = UUID(tokenMsb, tokenLsb)

            // TransferId
            val transferIdMsb = buf.long
            val transferIdLsb = buf.long
            val transferId = UUID(transferIdMsb, transferIdLsb)

            // RangeStart
            val rangeStart = buf.long

            // RangeLength
            val rangeLength = buf.long

            // ChunkSize
            val chunkSize = buf.int

            // Reserved
            val reserved = buf.int
            require(reserved == 0) { "Reserved field must be zero" }

            return BulkFrameHeader(
                version = version,
                streamIndex = streamIndex,
                token = token,
                transferId = transferId,
                rangeStart = rangeStart,
                rangeLength = rangeLength,
                chunkSize = chunkSize,
            )
        }
    }
}
