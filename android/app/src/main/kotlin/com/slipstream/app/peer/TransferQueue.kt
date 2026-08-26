package com.slipstream.app.peer

import java.io.File
import java.util.concurrent.LinkedBlockingQueue
import kotlin.math.absoluteValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Manages a queue of file transfers that run one at a time. Each transfer is given a unique ID;
 * the queue processes them sequentially, continuing after a failure rather than aborting.
 *
 * Progress is throttled to prevent UI thrashing: rapid updates are coalesced to approximately
 * 4 per second (~250ms), matching the design.md precedent for transfer.progress throttling.
 */
class TransferQueue(private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO)) {

    private data class QueuedTransfer(
        val id: String,
        val remotePath: String,
        val destination: File,
        val onProgress: (TransferProgress) -> Unit,
        val onComplete: () -> Unit,
        val onError: ((String, Throwable) -> Unit)?,
        val transferFlow: suspend () -> Flow<TransferProgress>,
    )

    private val queue = LinkedBlockingQueue<QueuedTransfer>()
    private val activeTransfers = mutableMapOf<String, TransferItem>()
    private val cancelledIds = mutableSetOf<String>()
    private var processingJob: Job? = null

    // Expose current queue state as a read-only StateFlow for UI consumption
    private val _activeTransfersState = MutableStateFlow<List<TransferItem>>(emptyList())
    val activeTransfersState: StateFlow<List<TransferItem>> = _activeTransfersState.asStateFlow()

    init {
        startProcessing()
    }

    /**
     * Enqueues a transfer. The [transferFlow] lambda receives a destination [File] and returns
     * a Flow of [TransferProgress] updates. The queue will call this lambda when the transfer
     * reaches the front of the queue.
     */
    fun enqueue(
        id: String,
        remotePath: String,
        destination: File,
        onProgress: (TransferProgress) -> Unit,
        onComplete: () -> Unit,
        onError: ((String, Throwable) -> Unit)? = null,
        transferFlow: suspend () -> Flow<TransferProgress>,
    ) {
        val item = QueuedTransfer(
            id = id,
            remotePath = remotePath,
            destination = destination,
            onProgress = onProgress,
            onComplete = onComplete,
            onError = onError,
            transferFlow = transferFlow,
        )
        queue.offer(item)
    }

    /**
     * Cancels a queued transfer. If the transfer is still queued (not yet running), it is
     * removed. If it is currently running, the cancellation is noted but the running transfer
     * is not interrupted (to avoid leaving a partially-written file).
     */
    fun cancel(id: String) {
        cancelledIds.add(id)
        queue.removeIf { it.id == id }
    }

    private fun startProcessing() {
        processingJob = scope.launch {
            while (isActive) {
                val transfer = queue.take()

                if (transfer.id in cancelledIds) {
                    cancelledIds.remove(transfer.id)
                    continue
                }

                processTransfer(transfer)
            }
        }
    }

    private suspend fun processTransfer(transfer: QueuedTransfer) {
        try {
            val item = TransferItem(remotePath = transfer.remotePath, totalBytes = 0L, id = transfer.id)
            activeTransfers[transfer.id] = item
            _activeTransfersState.value = activeTransfers.values.toList()

            var lastReportTimeMs = System.currentTimeMillis()
            var lastBytesTransferred = 0L

            transfer.transferFlow().collect { progress ->
                if (transfer.id in cancelledIds) {
                    cancelledIds.remove(transfer.id)
                    return@collect
                }

                item.apply(progress)

                // Throttle progress emission to ~4 per second (~250ms)
                val now = System.currentTimeMillis()
                val elapsedMs = now - lastReportTimeMs

                if (elapsedMs >= 250 || progress.bytesTransferred == progress.totalBytes) {
                    // Calculate rate if enough time has passed
                    if (elapsedMs > 0) {
                        val bytesDelta = progress.bytesTransferred - lastBytesTransferred
                        val rateBytes = (bytesDelta.toDouble() / elapsedMs) * 1000.0
                        item.updateRate(rateBytes)
                        lastBytesTransferred = progress.bytesTransferred
                        lastReportTimeMs = now
                    }

                    transfer.onProgress(progress)
                    // Update UI state when reporting progress
                    _activeTransfersState.value = activeTransfers.values.toList()
                }
            }

            item.markComplete()
            _activeTransfersState.value = activeTransfers.values.toList()
            transfer.onComplete()
        } catch (e: Throwable) {
            activeTransfers[transfer.id]?.markFailed()
            _activeTransfersState.value = activeTransfers.values.toList()
            transfer.onError?.invoke(transfer.id, e)
        } finally {
            activeTransfers.remove(transfer.id)
            _activeTransfersState.value = activeTransfers.values.toList()
        }
    }

    fun close() {
        processingJob?.cancel()
    }
}
