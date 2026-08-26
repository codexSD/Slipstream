package com.slipstream.app.peer

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Tests for [TransferQueue]: formatting, one-at-a-time execution, failure resilience,
 * and cancellation.
 */
@RunWith(RobolectricTestRunner::class)
class TransferQueueTest {

    @Test
    fun `formats progress with tabular size rate and eta`() {
        val item = TransferItem("test.bin", totalBytes = 4L * 1024 * 1024 * 1024)
        // 1 GB transferred out of 4 GB
        item.apply(TransferProgress(bytesTransferred = 1L shl 30, totalBytes = 4L shl 30))
        // Update rate to 50 MB/s
        item.updateRate(50.0 * 1024 * 1024)

        // Format shows transferred/total with matching units
        // 1 GB = 1024 MB, 4 GB = 4096 MB, so at 50 MB/s: 3072 MB / 50 MB/s = 61.44 seconds
        assertEquals("1.0 / 4.0 GB", item.sizeText)
        assertEquals("50.0 MB/s", item.rateText)
        assertEquals("1m 1s left", item.etaText)
    }

    @Test
    fun `formats size without total when totalBytes is zero`() {
        val item = TransferItem("test.bin", totalBytes = 0L)
        item.apply(TransferProgress(bytesTransferred = 512 * 1024 * 1024, totalBytes = 0L))

        assertEquals("512.0 MB", item.sizeText)
    }

    @Test
    fun `executes transfers one at a time`() = runBlocking {
        val executionOrder = mutableListOf<Int>()
        val queue = TransferQueue()

        queue.enqueue(
            id = "transfer-1",
            remotePath = "file1.bin",
            destination = createTempDirectory().toFile(),
            onProgress = { },
            onComplete = { executionOrder.add(1) },
        ) { flow { emit(TransferProgress(1000, 1000)) } }

        queue.enqueue(
            id = "transfer-2",
            remotePath = "file2.bin",
            destination = createTempDirectory().toFile(),
            onProgress = { },
            onComplete = { executionOrder.add(2) },
        ) { flow { emit(TransferProgress(1000, 1000)) } }

        queue.enqueue(
            id = "transfer-3",
            remotePath = "file3.bin",
            destination = createTempDirectory().toFile(),
            onProgress = { },
            onComplete = { executionOrder.add(3) },
        ) { flow { emit(TransferProgress(1000, 1000)) } }

        delay(1500)

        assertEquals("Transfers execute in order", listOf(1, 2, 3), executionOrder)
        queue.close()
    }

    @Test
    fun `failed transfer does not stop the queue`() = runBlocking {
        var completedCount = 0
        var failedId: String? = null

        val queue = TransferQueue()

        queue.enqueue(
            id = "transfer-1",
            remotePath = "file1.bin",
            destination = createTempDirectory().toFile(),
            onProgress = { },
            onComplete = { completedCount++ },
            onError = { _, _ -> },
        ) { flow { emit(TransferProgress(1000, 1000)) } }

        queue.enqueue(
            id = "transfer-2",
            remotePath = "file2.bin",
            destination = createTempDirectory().toFile(),
            onProgress = { },
            onComplete = { completedCount++ },
            onError = { id, _ -> failedId = id },
        ) { flow { throw Exception("Transfer failed") } }

        queue.enqueue(
            id = "transfer-3",
            remotePath = "file3.bin",
            destination = createTempDirectory().toFile(),
            onProgress = { },
            onComplete = { completedCount++ },
            onError = { _, _ -> },
        ) { flow { emit(TransferProgress(1000, 1000)) } }

        delay(1500)

        assertEquals("Two transfers complete", 2, completedCount)
        assertEquals("Transfer 2 failed", "transfer-2", failedId)
        queue.close()
    }

}
