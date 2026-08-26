package com.slipstream.app.ui.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.slipstream.app.peer.HistoryEntry
import com.slipstream.app.peer.HistoryStore
import com.slipstream.app.peer.PeerController
import com.slipstream.app.peer.TransferQueue
import java.io.File
import java.util.UUID
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * ViewModel for the History screen (C3). Takes its collaborators directly - [HistoryStore],
 * [TransferQueue], [PeerController] - the same construction pattern [com.slipstream.app.ui.browse.BrowseViewModel]
 * and [com.slipstream.app.ui.pairing.PairingViewModel] already use, rather than the previous
 * [androidx.lifecycle.AndroidViewModel] that built its own private, never-shared [HistoryStore]
 * instance (so it could never see entries a real transfer wrote) and had no way to actually
 * re-enqueue a "Run again".
 */
class HistoryViewModel(
    private val historyStore: HistoryStore,
    private val transferQueue: TransferQueue,
    private val peerController: PeerController,
) : ViewModel() {

    /** Observable flow of history entries, newest first. */
    val entries: StateFlow<List<HistoryEntry>> = historyStore.entries

    init {
        viewModelScope.launch { historyStore.load() }
    }

    /**
     * Re-enqueues [entry] through the real, shared [TransferQueue] (previously a no-op - see
     * this class's old doc). A [HistoryEntry.Direction.Push] entry's [HistoryEntry.path] is a
     * path on *this* device (it was pushed from here), so it re-runs as another
     * [PeerController.push] using the file's own name as the remote name. A
     * [HistoryEntry.Direction.Pull] entry's path is the remote path that was pulled, so it
     * re-runs as another [PeerController.pull] into the same local destination it landed at the
     * first time.
     */
    fun reEnqueueEntry(entry: HistoryEntry) {
        entry.reEnqueue { path ->
            val id = UUID.randomUUID().toString()
            when (entry.direction) {
                HistoryEntry.Direction.Push -> {
                    val localFile = File(path)
                    transferQueue.enqueue(
                        id = id,
                        remotePath = localFile.name,
                        destination = localFile,
                        onProgress = {},
                        onComplete = { recordOutcome(entry, HistoryEntry.State.Completed) },
                        onError = { _, _ -> recordOutcome(entry, HistoryEntry.State.Failed) },
                    ) { peerController.push(path, localFile.name) }
                }

                HistoryEntry.Direction.Pull -> {
                    val destination = File(path)
                    transferQueue.enqueue(
                        id = id,
                        remotePath = path,
                        destination = destination,
                        onProgress = {},
                        onComplete = { recordOutcome(entry, HistoryEntry.State.Completed) },
                        onError = { _, _ -> recordOutcome(entry, HistoryEntry.State.Failed) },
                    ) { peerController.pull(path, destination) }
                }
            }
        }
    }

    /** Saved synchronously (see [com.slipstream.app.ui.browse.BrowseViewModel]'s equivalent for
     * why this must not hop onto a ViewModel scope's Main dispatcher). */
    private fun recordOutcome(entry: HistoryEntry, state: HistoryEntry.State) {
        historyStore.addEntry(entry.copy(id = UUID.randomUUID().toString(), timestamp = System.currentTimeMillis(), state = state))
        historyStore.saveSync()
    }
}
