package com.slipstream.core.transfer

import com.slipstream.core.Vectors
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test
import org.junit.Assert.assertEquals
import java.util.UUID

const val CHUNK = 1048576

private fun vectorCases(fileName: String) =
    Json.parseToJsonElement(Vectors.read(fileName)).jsonObject["cases"]!!.jsonArray

private fun uuidFromHex(hex: String): UUID {
    val bytes = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    return UUID(
        (bytes[0].toLong() and 0xFF shl 56) or
            (bytes[1].toLong() and 0xFF shl 48) or
            (bytes[2].toLong() and 0xFF shl 40) or
            (bytes[3].toLong() and 0xFF shl 32) or
            (bytes[4].toLong() and 0xFF shl 24) or
            (bytes[5].toLong() and 0xFF shl 16) or
            (bytes[6].toLong() and 0xFF shl 8) or
            (bytes[7].toLong() and 0xFF),
        (bytes[8].toLong() and 0xFF shl 56) or
            (bytes[9].toLong() and 0xFF shl 48) or
            (bytes[10].toLong() and 0xFF shl 40) or
            (bytes[11].toLong() and 0xFF shl 32) or
            (bytes[12].toLong() and 0xFF shl 24) or
            (bytes[13].toLong() and 0xFF shl 16) or
            (bytes[14].toLong() and 0xFF shl 8) or
            (bytes[15].toLong() and 0xFF),
    )
}

private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

// Extension properties to access JSON primitive numeric values
private val kotlinx.serialization.json.JsonPrimitive.int: Int get() = content.toInt()
private val kotlinx.serialization.json.JsonPrimitive.long: Long get() = content.toLong()

class BulkTransferTest {

    @Test
    fun `headers match the shared vectors byte for byte`() {
        for (case in vectorCases("bulk-headers.json")) {
            val f = case.jsonObject["fields"]!!.jsonObject
            val header = BulkFrameHeader(
                version = f["version"]!!.jsonPrimitive.int.toUShort(),
                streamIndex = f["streamIndex"]!!.jsonPrimitive.int.toUShort(),
                token = uuidFromHex(f["token"]!!.jsonPrimitive.content),
                transferId = uuidFromHex(f["transferId"]!!.jsonPrimitive.content),
                rangeStart = f["rangeStart"]!!.jsonPrimitive.long,
                rangeLength = f["rangeLength"]!!.jsonPrimitive.long,
                chunkSize = f["chunkSize"]!!.jsonPrimitive.int,
            )
            assertEquals(
                case.jsonObject["bytes"]!!.jsonPrimitive.content.replace("_", ""),
                header.toBytes().toHex(),
            )
        }
    }

    @Test
    fun `crc32c matches the shared vectors`() {
        for (case in vectorCases("crc32c.json")) {
            assertEquals(
                case.jsonObject["crc_hex"]!!.jsonPrimitive.content,
                "%08x".format(Crc32C.compute(case.jsonObject["input_utf8"]!!.jsonPrimitive.content.toByteArray())),
            )
        }
    }

    @Test
    fun `chunk bitmaps match the shared vectors`() {
        for (case in vectorCases("chunk-bitmaps.json")) {
            val bitmap = ChunkBitmap(case.jsonObject["chunkCount"]!!.jsonPrimitive.int)
            case.jsonObject["complete"]!!.jsonArray.forEach { bitmap[it.jsonPrimitive.int] = true }
            assertEquals(case.jsonObject["base64"]!!.jsonPrimitive.content, bitmap.toBase64())
        }
    }

    @Test
    fun `splitMissing assigns a small file whole`() {
        // Spec §7: below 4 MB, never range-split. The C# side learned this the hard way
        // — its threshold check lived in a method the download path never called.
        val bitmap = ChunkBitmap(ChunkBitmap.chunkCountFor(3L * CHUNK, CHUNK))
        assertEquals(1, TransferPlan.splitMissing(bitmap, 3L * CHUNK, streamCount = 4, CHUNK).size)
    }
}
