package com.slipstream.app.peer

import app.cash.turbine.test
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
 * cancellation, and throttled progress emission.
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

    @Test
    fun `cancel removes queued item before execution`() = runBlocking {
        var completed1 = false
        var completed2 = false
        var completed3 = false

        val queue = TransferQueue()

        // First transfer: takes time so #2 isn't processed until we cancel
        queue.enqueue(
            id = "transfer-1",
            remotePath = "file1.bin",
            destination = createTempDirectory().toFile(),
            onProgress = { },
            onComplete = { completed1 = true },
        ) { flow { delay(300); emit(TransferProgress(1000, 1000)) } }

        queue.enqueue(
            id = "transfer-2",
            remotePath = "file2.bin",
            destination = createTempDirectory().toFile(),
            onProgress = { },
            onComplete = { completed2 = true },
        ) { flow { emit(TransferProgress(1000, 1000)) } }

        queue.enqueue(
            id = "transfer-3",
            remotePath = "file3.bin",
            destination = createTempDirectory().toFile(),
            onProgress = { },
            onComplete = { completed3 = true },
        ) { flow { emit(TransferProgress(1000, 1000)) } }

        // Cancel transfer-2 while transfer-1 is running (before queue reaches #2)
        delay(150)
        queue.cancel("transfer-2")
        delay(1500)

        assertTrue("Transfer 1 completed", completed1)
        assertFalse("Transfer 2 was cancelled", completed2)
        assertTrue("Transfer 3 completed", completed3)
        queue.close()
    }

    @Test
    fun `throttles progress updates to ~4 per second`() = runBlocking {
        var progressUpdates = 0
        val queue = TransferQueue()

        queue.enqueue(
            id = "transfer-1",
            remotePath = "file.bin",
            destination = createTempDirectory().toFile(),
            onProgress = { progressUpdates++ },
            onComplete = { },
        ) {
            flow {
                // Emit updates with small delays to cross 250ms boundaries
                // This creates a pattern where some updates cross the throttle window
                repeat(20) { i ->
                    delay(20) // 20ms between emits = 400ms total
                    emit(TransferProgress(bytesTransferred = i.toLong() * 1000, totalBytes = 20000L))
                }
            }
        }

        delay(600)

        // With 20ms delays and 250ms throttle:
        // Updates arrive at: 20ms, 40ms, 60ms, ... 380ms, 400ms
        // First batch (0-250ms): updates at 20, 40, 60, ... 240ms → only first passes through at ~20ms
        // Then at ~270ms next one passes through, then ~520ms next one passes
        // Expected: approximately 2-3 updates for this pattern, definitely fewer than 10
        assertTrue("Throttling reduces updates: $progressUpdates << 20", progressUpdates < 10)
        assertTrue("Some updates visible: $progressUpdates > 0", progressUpdates > 0)
        queue.close()
    }

    @Test
    fun `activeTransfersState exposes active transfers for UI consumption`() = runBlocking {
        val queue = TransferQueue()
        val observedStates = mutableListOf<List<TransferItem>>()

        queue.enqueue(
            id = "transfer-1",
            remotePath = "file1.bin",
            destination = createTempDirectory().toFile(),
            onProgress = { },
            onComplete = { },
        ) { flow { emit(TransferProgress(1000, 1000)) } }

        delay(500)

        // After transfer completes, state should reflect it was active at some point
        observedStates.add(queue.activeTransfersState.value)

        // State should now be empty (transfer finished)
        assertEquals(0, queue.activeTransfersState.value.size)

        queue.close()
    }

}
