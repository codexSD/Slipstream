package com.slipstream.app.ui.send

import android.net.Uri
import androidx.lifecycle.ViewModel
import com.slipstream.app.peer.PeerController
import com.slipstream.app.peer.TransferProgress
import com.slipstream.core.transfer.FolderExpander
import java.io.File
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext

/** The direct, no-apology (spec §15) message shown when the user tries to send with no paired
 * peer to send to. */
internal const val NO_PEER_MESSAGE = "Pair a device before sending."

/** One queued item — either a single picked/shared file, or one file out of an expanded folder
 * (see [SendViewModel.onPathsSelected]). [relativePath] is what [PeerController.push] is called
 * with as `remoteName`; for a folder's contents it carries the `/`-separated path under the
 * folder root, which is how nested structure gets recreated on the receiver. */
data class SendItem(
    val relativePath: String,
    val localPath: String,
    val size: Long,
)

data class SendState(
    val items: List<SendItem> = emptyList(),
    val sending: Boolean = false,
    val progress: Map<String, TransferProgress> = emptyMap(),
    val message: String? = null,
)

/**
 * Task 10: the send screen's view model. Turns a picked file/folder or an incoming share-sheet
 * intent into a flat queue of [SendItem]s, then pushes each one through [PeerController] — the
 * only place in `:app` outside `:core` itself that calls [FolderExpander], closing the Plan 3
 * deviation noted in its class doc (it previously had no production caller).
 *
 * A folder's empty-directory entries ([FolderExpander.Entry.isDirectory] `== true`) are dropped
 * here — an `:app`-side scope call made for this task, not something the plan or spec asked
 * for. Spec §7 (quoted in [FolderExpander]'s own class doc) says empty directories should be
 * preserved on the receiver, but `:core`'s wire protocol has no message for "create this empty
 * directory" — [PeerController.push] only ever sends file bytes to a `remoteName`. Recreating an
 * empty directory would need a new `:core` protocol addition (in the spirit of what Tasks
 * 1.5/2.5 added for `pushOffer`/`ThumbnailProvider`), which is out of proportion to add in this
 * task for a case as narrow as "an empty folder nested inside a shared folder". Disclosed here
 * as a known, deliberately-unsolved `:core` protocol gap for a future task (e.g. Task 13's
 * whole-branch review) to pick up if it matters.
 */
class SendViewModel(
    private val controller: PeerController,
    private val uriResolver: UriResolver = FileUriResolver,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {

    private val _state = MutableStateFlow(SendState())
    val state: StateFlow<SendState> = _state.asStateFlow()

    /** Queues [paths]: a plain file is queued as-is; a directory is expanded via
     * [FolderExpander] so every file inside it is queued with its `/`-separated path relative to
     * the folder root preserved, exactly as [FolderExpander]'s own contract promises. */
    suspend fun onPathsSelected(paths: List<File>) = withContext(dispatcher) {
        val newItems = paths.flatMap { path -> expand(path) }
        enqueue(newItems)
    }

    /** Queues every [uris] entry from an `ACTION_SEND`/`ACTION_SEND_MULTIPLE` share intent,
     * resolving each through [uriResolver] first since a `content://` URI isn't necessarily a
     * usable filesystem path (see [ContentUriResolver]'s class doc). */
    suspend fun onShareIntent(uris: List<Uri>) = withContext(dispatcher) {
        val newItems = uris.map { uri ->
            val resolved = uriResolver.resolve(uri)
            SendItem(relativePath = resolved.displayName, localPath = resolved.localPath, size = resolved.size)
        }
        enqueue(newItems)
    }

    /** Removes a not-yet-sent item from the queue. */
    fun remove(item: SendItem) {
        _state.update { it.copy(items = it.items - item) }
    }

    fun clear() {
        _state.value = SendState()
    }

    /** Pushes every queued item to the paired peer, one at a time. Surfaces the exact spec §15
     * message rather than failing silently when there is no paired peer at all — checked both
     * up front and again per-item, since pairing can be lost mid-queue. */
    suspend fun send() = withContext(dispatcher) {
        if (!controller.isPaired.value) {
            _state.update { it.copy(message = NO_PEER_MESSAGE) }
            return@withContext
        }

        _state.update { it.copy(sending = true, message = null) }
        val items = _state.value.items
        for (item in items) {
            if (!controller.isPaired.value) {
                _state.update { it.copy(message = NO_PEER_MESSAGE) }
                break
            }
            try {
                controller.push(item.localPath, item.relativePath).collect { progress ->
                    _state.update { it.copy(progress = it.progress + (item.relativePath to progress)) }
                }
                _state.update { it.copy(items = it.items - item) }
            } catch (e: Exception) {
                _state.update { it.copy(message = e.message ?: "Send failed.") }
            }
        }
        _state.update { it.copy(sending = false) }
    }

    private fun enqueue(newItems: List<SendItem>) {
        _state.update { it.copy(items = it.items + newItems) }
        if (!controller.isPaired.value) {
            _state.update { it.copy(message = NO_PEER_MESSAGE) }
        }
    }

    private fun expand(path: File): List<SendItem> =
        if (path.isDirectory) {
            FolderExpander.expand(path)
                .filterNot { it.isDirectory }
                .map { entry ->
                    SendItem(
                        relativePath = entry.relativePath,
                        localPath = File(path, entry.relativePath).path,
                        size = entry.size,
                    )
                }
        } else {
            listOf(SendItem(relativePath = path.name, localPath = path.path, size = path.length()))
        }
}
