package com.slipstream.app.peer

import java.io.File
import java.util.UUID
import kotlinx.serialization.Serializable

/**
 * A record of a completed or failed file transfer (push or pull).
 * Immutable once created; all state is frozen at creation time.
 */
@Serializable
data class HistoryEntry(
    val id: String, // UUID as string for serialization
    val path: String,
    val size: Long,
    val timestamp: Long,
    val direction: Direction,
    val state: State,
) {
    /**
     * Helper to get the UUID object from the serialized string.
     */
    fun getUUID(): UUID = UUID.fromString(id)
    /**
     * The direction of the transfer.
     */
    @Serializable
    enum class Direction {
        Push,
        Pull,
    }

    /**
     * Terminal state of the transfer.
     */
    @Serializable
    enum class State {
        Completed,
        Failed,
    }

    /**
     * Whether the file at [path] still exists on disk. Used to disable "Open" in the UI.
     */
    val canOpen: Boolean
        get() = File(path).exists()

    /**
     * Enqueues this transfer for re-run with the provided callback.
     * The callback receives the path and is responsible for re-enqueuing
     * the transfer in whatever transfer queue implementation exists.
     */
    fun reEnqueue(callback: (String) -> Unit) {
        callback(path)
    }
}
