package com.slipstream.core.transfer

/**
 * CRC-32C (Castagnoli) checksum using the reflected polynomial 0x1EDC6F41.
 * Tries to use hardware-accelerated java.util.zip.CRC32C first (API 26+),
 * falls back to table-based implementation if needed.
 */
object Crc32C {

    private val useHardware: Boolean
    private val tableImpl: TableCrc32C?

    init {
        // Try to use hardware-accelerated CRC32C
        useHardware = try {
            val crc = java.util.zip.CRC32C()
            crc.update("123456789".toByteArray())
            val checkValue = crc.value
            checkValue == 0xE3069283L
        } catch (e: Exception) {
            false
        }

        tableImpl = if (!useHardware) TableCrc32C() else null
    }

    fun compute(data: ByteArray): Long {
        return if (useHardware) {
            val crc = java.util.zip.CRC32C()
            crc.update(data)
            crc.value
        } else {
            tableImpl!!.compute(data)
        }
    }
}

/**
 * Table-based CRC-32C implementation with reflected polynomial 0x82F63B78.
 */
private class TableCrc32C {

    private val table = IntArray(256)

    init {
        for (i in 0..255) {
            var crc = i
            for (j in 0..7) {
                crc = if ((crc and 1) != 0) {
                    (crc ushr 1) xor 0x82F63B78.toInt()
                } else {
                    crc ushr 1
                }
            }
            table[i] = crc
        }
    }

    fun compute(data: ByteArray): Long {
        var crc = 0xFFFFFFFFL
        for (b in data) {
            val index = ((crc.toInt() xor b.toInt()) and 0xFF)
            crc = (crc ushr 8) xor (table[index].toLong() and 0xFFFFFFFFL)
        }
        return crc xor 0xFFFFFFFFL
    }
}
