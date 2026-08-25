package com.slipstream.core.transfer

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.util.UUID
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** Thrown by [PartFile.writeChunk] when the received data does not match its declared CRC32C. */
class ChunkCrcMismatchException(val chunkIndex: Int) :
    Exception("CRC32C mismatch for chunk $chunkIndex")

/**
 * The on-disk resumable destination for a bulk transfer: a preallocated file plus a bitmap
 * of which chunks are complete and CRC-verified, persisted to a sidecar file so a dropped
 * transfer can resume without re-downloading completed chunks.
 *
 * Concurrency: many streams call [writeChunk] in parallel for disjoint chunk indices.
 * [bitmapLock] guards only the in-memory bit flip - the positioned write to [raf]'s channel
 * happens outside any lock (concurrent positioned writes on a single FileChannel are safe and
 * do not share file-pointer state), so parallel streams never serialize on file I/O. Sidecar
 * persistence is debounced (see [debounceMs]) so 50 sequential chunk writes cost a handful of
 * sidecar writes, not 50.
 */
class PartFile private constructor(
    private val raf: RandomAccessFile,
    private val sidecar: File,
    val transferId: UUID,
    val fileSize: Long,
    val chunkSize: Int,
    val bitmap: ChunkBitmap,
    private val onPersist: (() -> Unit)?,
    private val debounceMs: Long,
) : AutoCloseable {

    private val bitmapLock = ReentrantLock()
    private val persistLock = Any()
    private var lastPersistMs = 0L
    private var dirtySincePersist = false

    /** Writes [data] at [chunkIndex]'s offset after validating it against [crc]. */
    fun writeChunk(chunkIndex: Int, data: ByteArray, crc: Long) {
        val actual = Crc32C.compute(data)
        if (actual != crc) throw ChunkCrcMismatchException(chunkIndex)

        // File I/O happens outside the bitmap lock - positioned FileChannel writes are safe
        // for concurrent callers, so this never serializes parallel streams against each other.
        val position = chunkIndex.toLong() * chunkSize
        raf.channel.write(ByteBuffer.wrap(data), position)

        bitmapLock.withLock { bitmap[chunkIndex] = true }
        maybePersist()
    }

    /** Snapshot of the chunks still missing, as contiguous chunk-index ranges. */
    fun missingRanges(): List<IntRange> = bitmapLock.withLock { bitmap.missingRanges() }

    fun complete(): Boolean = bitmapLock.withLock { bitmap.isComplete() }

    private fun maybePersist() {
        val shouldPersistNow = synchronized(persistLock) {
            val now = System.currentTimeMillis()
            if (now - lastPersistMs >= debounceMs) {
                lastPersistMs = now
                dirtySincePersist = false
                true
            } else {
                dirtySincePersist = true
                false
            }
        }
        if (shouldPersistNow) persistSidecar()
    }

    private fun persistSidecar() {
        val bytes = bitmapLock.withLock { bitmap.rawBytes() }
        sidecar.writeBytes(bytes)
        onPersist?.invoke()
    }

    override fun close() {
        val dirty = synchronized(persistLock) { dirtySincePersist }
        if (dirty) persistSidecar()
        raf.close()
    }

    companion object {
        private const val DEFAULT_DEBOUNCE_MS = 500L

        fun sidecarFor(destination: File): File =
            File(destination.parentFile, destination.name + ".bitmap")

        /**
         * Opens [destination] for resumable writing, preallocating it to [size] bytes and
         * restoring the chunk bitmap from its sidecar if one exists from a prior attempt.
         */
        fun openOrCreate(
            destination: File,
            transferId: UUID,
            size: Long,
            chunkSize: Int,
            onPersist: (() -> Unit)? = null,
            debounceMs: Long = DEFAULT_DEBOUNCE_MS,
        ): PartFile {
            destination.parentFile?.mkdirs()
            val raf = RandomAccessFile(destination, "rw")
            raf.setLength(size)

            val chunkCount = ChunkBitmap.chunkCountFor(size, chunkSize)
            val sidecar = sidecarFor(destination)
            val bitmap = if (sidecar.exists()) {
                ChunkBitmap.fromBytes(sidecar.readBytes(), chunkCount)
            } else {
                ChunkBitmap(chunkCount)
            }

            return PartFile(raf, sidecar, transferId, size, chunkSize, bitmap, onPersist, debounceMs)
        }
    }
}
