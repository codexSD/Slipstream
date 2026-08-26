package com.slipstream.app.peer

import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

private val jsonFormat = Json

/**
 * Persists transfer history to a JSON file in app storage, capped at 500 entries.
 * Newest entries are listed first. Oldest entries are evicted when the cap is exceeded.
 *
 * All file I/O is performed on [Dispatchers.IO] to avoid blocking the main thread.
 * The [entries] flow can be observed by UI to react to history changes.
 */
class HistoryStore(private val storageFile: File) {

    private val _entries = MutableStateFlow<List<HistoryEntry>>(emptyList())

    /**
     * Observable flow of current history entries, newest first.
     * Exposed to UI/ViewModels so they can react to additions and state changes.
     */
    val entries: StateFlow<List<HistoryEntry>> = _entries.asStateFlow()

    /**
     * Adds an entry to the history, maintaining newest-first order and the 500-entry cap.
     * Blocking call (I/O); intended for use on Dispatchers.IO.
     */
    fun addEntry(entry: HistoryEntry) {
        val current = _entries.value.toMutableList()
        current.add(0, entry) // Add to front (newest first)

        // Cap at 500 entries
        if (current.size > MAX_ENTRIES) {
            current.removeAt(current.size - 1) // Remove oldest
        }

        _entries.value = current
    }

    /**
     * Loads the history from disk into memory. Should be called once at startup.
     * Non-blocking; internally dispatches to [Dispatchers.IO].
     */
    suspend fun load() = withContext(Dispatchers.IO) {
        loadSync()
    }

    /**
     * Synchronous load, for use by tests or if already on Dispatchers.IO.
     */
    internal fun loadSync() {
        if (!storageFile.exists()) {
            _entries.value = emptyList()
            return
        }

        try {
            val text = storageFile.readText()
            val container = jsonFormat.decodeFromString<HistoryContainer>(text)
            val loaded = container.entries
                .sortedByDescending { it.timestamp } // Ensure newest first
                .take(MAX_ENTRIES) // Enforce cap on load
            _entries.value = loaded
        } catch (e: Exception) {
            // If the file is corrupt or can't be parsed, start fresh
            _entries.value = emptyList()
        }
    }

    /**
     * Persists the current history to disk.
     * Non-blocking; internally dispatches to [Dispatchers.IO].
     */
    suspend fun save() = withContext(Dispatchers.IO) {
        saveSync()
    }

    /**
     * Synchronous save, for use by tests or if already on Dispatchers.IO.
     */
    internal fun saveSync() {
        try {
            val container = HistoryContainer(entries = _entries.value)
            val json = jsonFormat.encodeToString(HistoryContainer.serializer(), container)
            storageFile.writeText(json)
        } catch (e: Exception) {
            // Silently fail; we don't want a corrupted disk file to crash the app.
            // The in-memory state remains valid for this session.
        }
    }

    companion object {
        private const val MAX_ENTRIES = 500
    }
}

/**
 * Wrapper for serialization of the history list.
 */
@Serializable
internal data class HistoryContainer(
    val entries: List<HistoryEntry>,
)
