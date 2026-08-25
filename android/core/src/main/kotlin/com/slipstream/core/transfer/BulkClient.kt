package com.slipstream.core.transfer

import com.slipstream.core.net.NetworkBinder
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** One contiguous byte range still owed to a [PartFile], expressed in wire-header terms. */
private data class RangeTask(val rangeStart: Long, val rangeLength: Long)

/**
 * Downloads the missing chunks of a [PartFile] over the bulk protocol using up to [streams]
 * concurrent TCP connections. Missing chunks are gathered as contiguous runs
 * ([PartFile.missingRanges]) and handed out from a shared queue, so a transfer that was
 * dropped mid-flight and left more gaps than there are streams (e.g. 6 gaps, 4 streams) is
 * still resumed correctly: workers simply pull the next range once they finish one, reusing
 * the same multi-use token for however many connections that takes.
 */
class BulkClient(
    private val connectTimeoutMs: Int = 5000,
    private val socketTimeoutMs: Int = 15000,
    private val binder: NetworkBinder = NetworkBinder.NONE,
) {
    fun download(
        endpoint: InetSocketAddress,
        transferId: UUID,
        token: UUID,
        part: PartFile,
        streams: Int,
        onProgress: ((Long) -> Unit)?,
    ) {
        val queue = ConcurrentLinkedDeque<RangeTask>()
        part.missingRanges().forEach { chunkRange ->
            val rangeStart = chunkRange.first.toLong() * part.chunkSize
            val rangeEndExclusive = minOf(
                (chunkRange.last + 1).toLong() * part.chunkSize,
                part.fileSize,
            )
            queue.add(RangeTask(rangeStart, rangeEndExclusive - rangeStart))
        }
        if (queue.isEmpty()) return

        val streamIndex = AtomicInteger(0)
        val failure = AtomicReference<Throwable?>(null)
        val workerCount = maxOf(1, minOf(streams, queue.size))
        val executor = Executors.newFixedThreadPool(workerCount)

        try {
            val futures = (0 until workerCount).map {
                executor.submit {
                    try {
                        while (true) {
                            val task = queue.pollFirst() ?: break
                            runRange(endpoint, transferId, token, part, task, streamIndex.getAndIncrement(), onProgress)
                        }
                    } catch (e: Throwable) {
                        failure.compareAndSet(null, e)
                    }
                }
            }
            futures.forEach { it.get() }
        } finally {
            executor.shutdown()
            executor.awaitTermination(socketTimeoutMs.toLong() * 2, TimeUnit.MILLISECONDS)
        }

        failure.get()?.let { throw it }
    }

    private fun runRange(
        endpoint: InetSocketAddress,
        transferId: UUID,
        token: UUID,
        part: PartFile,
        task: RangeTask,
        streamIndex: Int,
        onProgress: ((Long) -> Unit)?,
    ) {
        Socket().use { socket ->
            binder.bind(socket)
            socket.connect(endpoint, connectTimeoutMs)
            socket.soTimeout = socketTimeoutMs

            val header = BulkFrameHeader(
                version = 1u,
                streamIndex = streamIndex.toUShort(),
                token = token,
                transferId = transferId,
                rangeStart = task.rangeStart,
                rangeLength = task.rangeLength,
                chunkSize = part.chunkSize,
            )
            val output = DataOutputStream(socket.getOutputStream())
            output.write(header.toBytes())
            output.flush()

            val input = DataInputStream(socket.getInputStream())
            var position = task.rangeStart
            var remaining = task.rangeLength
            var ordinal = 0
            while (remaining > 0) {
                val len = input.readInt()
                require(len in 1..part.chunkSize) { "invalid chunk length $len" }
                val data = ByteArray(len)
                input.readFully(data)
                val crc = input.readInt().toLong() and 0xFFFFFFFFL

                val chunkIndex = (task.rangeStart / part.chunkSize).toInt() + ordinal
                part.writeChunk(chunkIndex, data, crc)
                onProgress?.invoke(len.toLong())

                position += len
                remaining -= len
                ordinal++
            }
        }
    }
}
