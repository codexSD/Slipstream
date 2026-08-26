package com.slipstream.app.peer

import java.io.File
import kotlin.math.absoluteValue

/** A single item in the transfer queue, tracking progress and formatting it for display.
 * [id] is the same id [TransferQueue.enqueue]/[TransferQueue.cancel] use — kept distinct from
 * [remotePath] (which is not guaranteed unique, e.g. re-enqueueing the same path from History)
 * so the Transfers screen's cancel action always cancels the right in-flight item. */
data class TransferItem(
    val remotePath: String,
    val totalBytes: Long,
    val id: String = remotePath,
    val state: State = State.Transferring,
) {
    /** Terminal/in-flight state for the status pill (C4.4). */
    enum class State { Transferring, Complete, Failed }

    var bytesTransferred: Long = 0L
        private set
    var rateBytes: Double = 0.0
        private set
    var lastUpdateTimeMs: Long = System.currentTimeMillis()
        private set
    var currentState: State = state
        private set

    /** Marks this item Complete, for the brief moment (C4.4) between the transfer finishing and
     * [TransferQueue] removing it from the active list. */
    fun markComplete() {
        currentState = State.Complete
    }

    /** Marks this item Failed, same lifecycle as [markComplete]. */
    fun markFailed() {
        currentState = State.Failed
    }

    /** Cumulative size display, e.g. "512 MB" or "1.0 / 4.0 GB" with matching units. */
    val sizeText: String
        get() {
            if (totalBytes <= 0) {
                return formatBytes(bytesTransferred)
            }

            // Determine the unit for the total
            val totalUnit = getUnit(totalBytes)
            val transferredValue = bytesTransferred.toDouble() / getUnitBytes(totalUnit)
            val totalValue = totalBytes.toDouble() / getUnitBytes(totalUnit)

            return String.format("%.1f / %.1f %s", transferredValue, totalValue, totalUnit)
        }

    /** Transfer rate display, e.g. "50.0 MB/s". */
    val rateText: String
        get() {
            val mbPerSec = rateBytes / (1024.0 * 1024.0)
            return if (mbPerSec >= 0) {
                String.format("%.1f MB/s", mbPerSec)
            } else {
                "– MB/s"
            }
        }

    /** Time remaining display, e.g. "1m 1s left" or "–" if unknown. */
    val etaText: String
        get() {
            if (totalBytes <= 0 || rateBytes <= 0) return "–"
            val bytesRemaining = totalBytes - bytesTransferred
            if (bytesRemaining <= 0) return "Done"
            val secondsRemaining = (bytesRemaining.toDouble() / rateBytes).toLong().coerceAtLeast(0)
            val minutes = secondsRemaining / 60
            val seconds = secondsRemaining % 60
            return when {
                minutes > 0 -> "${minutes}m ${seconds}s left"
                seconds > 0 -> "${seconds}s left"
                else -> "< 1s left"
            }
        }

    /** Updates progress from a TransferProgress event. */
    fun apply(progress: TransferProgress) {
        bytesTransferred = progress.bytesTransferred
    }

    /** Updates the measured transfer rate (bytes per second). */
    fun updateRate(rateBytes: Double) {
        this.rateBytes = rateBytes
        lastUpdateTimeMs = System.currentTimeMillis()
    }

    private fun formatBytes(bytes: Long): String {
        val gb = bytes.toDouble() / (1024 * 1024 * 1024)
        val mb = bytes.toDouble() / (1024 * 1024)
        val kb = bytes.toDouble() / 1024

        return when {
            gb >= 1.0 -> String.format("%.1f GB", gb)
            mb >= 1.0 -> String.format("%.1f MB", mb)
            kb >= 1.0 -> String.format("%.1f KB", kb)
            else -> "$bytes B"
        }
    }

    private fun getUnit(bytes: Long): String {
        val gb = bytes.toDouble() / (1024 * 1024 * 1024)
        val mb = bytes.toDouble() / (1024 * 1024)
        val kb = bytes.toDouble() / 1024

        return when {
            gb >= 1.0 -> "GB"
            mb >= 1.0 -> "MB"
            kb >= 1.0 -> "KB"
            else -> "B"
        }
    }

    private fun getUnitBytes(unit: String): Long = when (unit) {
        "GB" -> 1024L * 1024 * 1024
        "MB" -> 1024L * 1024
        "KB" -> 1024L
        else -> 1L
    }
}
