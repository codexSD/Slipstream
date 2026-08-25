package com.slipstream.core.transfer

/**
 * Range assignment for bulk transfer.
 *
 * Spec: Files < 4 MB are assigned whole to a single stream: one range covering [0, size).
 * Larger files are split into min(streamCount, chunkCount) contiguous ranges, each
 * starting on a chunk boundary. Remainder chunks go to the earliest ranges, so range
 * lengths differ by at most one chunk.
 */
object TransferPlan {

    data class Range(
        val streamIndex: Int,
        val rangeStart: Long,
        val rangeLength: Long,
    )

    fun splitMissing(bitmap: ChunkBitmap, fileSize: Long, streamCount: Int, chunkSize: Int): List<Range> {
        val chunkCount = ChunkBitmap.chunkCountFor(fileSize, chunkSize)

        // Files < 4 MB: assign whole to single stream
        if (fileSize < 4L * 1024 * 1024) {
            return listOf(
                Range(
                    streamIndex = 0,
                    rangeStart = 0,
                    rangeLength = fileSize,
                )
            )
        }

        // Larger files: split into min(streamCount, chunkCount) ranges
        val numRanges = minOf(streamCount, chunkCount)
        val ranges = mutableListOf<Range>()

        // Compute chunks per range and remainder
        val chunksPerRange = chunkCount / numRanges
        val remainderChunks = chunkCount % numRanges

        var currentChunk = 0
        for (i in 0 until numRanges) {
            // Ranges 0..remainderChunks-1 get an extra chunk
            val chunksInThisRange = chunksPerRange + if (i < remainderChunks) 1 else 0
            val rangeStart = currentChunk.toLong() * chunkSize
            val rangeLengthBytes = if (currentChunk + chunksInThisRange >= chunkCount) {
                // Last chunk might be shorter than chunkSize
                fileSize - rangeStart
            } else {
                chunksInThisRange.toLong() * chunkSize
            }

            ranges.add(
                Range(
                    streamIndex = i,
                    rangeStart = rangeStart,
                    rangeLength = rangeLengthBytes,
                )
            )

            currentChunk += chunksInThisRange
        }

        return ranges
    }
}
