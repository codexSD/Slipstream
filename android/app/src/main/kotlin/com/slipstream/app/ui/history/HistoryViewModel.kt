package com.slipstream.app.ui.history

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.slipstream.app.peer.HistoryEntry
import com.slipstream.app.peer.HistoryStore
import java.io.File
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * ViewModel for the History screen. Manages loading, observing, and re-enqueueing history entries.
 */
class HistoryViewModel(application: Application) : AndroidViewModel(application) {

    private val historyFile = File(application.filesDir, "history.json")
    private val historyStore = HistoryStore(historyFile)

    /**
     * Observable flow of history entries, newest first.
     */
    val entries: StateFlow<List<HistoryEntry>> = historyStore.entries

    init {
        viewModelScope.launch {
            historyStore.load()
        }
    }

    /**
     * Re-enqueues a history entry (would integrate with TransferQueue in a future task).
     * This is where the callback from HistoryEntry.reEnqueue would be linked to actual transfer logic.
     */
    fun reEnqueueEntry(entry: HistoryEntry) {
        entry.reEnqueue { path ->
            // This callback will be wired to TransferQueue.enqueue in a future task
            // For now, this is a placeholder that the queue implementation will use
        }
    }
}
