package com.slipstream.app.ui.browse

import androidx.lifecycle.ViewModel
import com.slipstream.app.peer.PeerController
import com.slipstream.meridian.component.MeridianUiState
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The mutually-exclusive category filters shown as chips above the listing (Task 6 brief).
 * [matches] is the MIME-prefix predicate each chip filters by — directories are never subject
 * to it (see [BrowseViewModel.applyFilter]), since hiding a folder because its own MIME type
 * isn't a video would make it impossible to navigate into it to find the videos inside. */
enum class BrowseFilter(val label: String) {
    All("All") {
        override fun matches(mime: String?) = true
    },
    Video("Video") {
        override fun matches(mime: String?) = mime?.startsWith("video/") == true
    },
    Audio("Audio") {
        override fun matches(mime: String?) = mime?.startsWith("audio/") == true
    },
    Images("Images") {
        override fun matches(mime: String?) = mime?.startsWith("image/") == true
    },
    Docs("Docs") {
        override fun matches(mime: String?) =
            mime != null && (mime.startsWith("application/") || mime.startsWith("text/"))
    },
    ;

    abstract fun matches(mime: String?): Boolean
}

/** One row in the browse listing — a `:core` [com.slipstream.core.files.FileEntry] plus a
 * ready-to-load `/thumb/<token>` URL (already resolved against the peer's control endpoint by
 * [PeerController.thumbnailUrl], never built here) when the peer supplied one. */
data class BrowseEntry(
    val name: String,
    val isDirectory: Boolean,
    val size: Long,
    val mtimeMs: Long,
    val mime: String?,
    val thumbnailUrl: String?,
)

/** One link in the path trail above the listing. [path] is what gets sent back to
 * [PeerController.list] when the user taps it. */
data class Breadcrumb(val label: String, val path: String)

/** What the browse screen renders. */
data class BrowseState(
    val uiState: MeridianUiState = MeridianUiState.Loading,
    val currentPath: String = "",
    val breadcrumbs: List<Breadcrumb> = emptyList(),
    val filter: BrowseFilter = BrowseFilter.All,
    val entries: List<BrowseEntry> = emptyList(),
    /** All entries for [currentPath], before [filter] narrows them — kept so switching filters
     * never needs a fresh round trip to the peer. */
    val allEntries: List<BrowseEntry> = emptyList(),
    /** Set only when the peer's listing was capped (design.md's directory-entry cap) — an
     * honest "this isn't everything" notice rather than silently pretending it's complete. */
    val truncationNotice: String? = null,
    /** Set once [BrowseViewModel.playHere] has a URL to hand a local player — the screen shows
     * a Media3 player for exactly as long as this is non-null (design.md §8, "Play here"). */
    val playbackUrl: String? = null,
    /** Set when either [BrowseViewModel.playOnPeer] or [BrowseViewModel.playHere] failed — the
     * direct, no-apology message (spec §15), never a stack trace. */
    val playbackError: String? = null,
)

/**
 * Task 6: the browse screen's view model. Lists a directory on the paired peer, sorts
 * directories before files, filters by MIME-prefix chip, and tracks a breadcrumb trail so the
 * user can step back out — all driven purely through [PeerController], `:app`'s one boundary
 * onto `:core`.
 */
class BrowseViewModel(
    private val controller: PeerController,
) : ViewModel() {
    private val _state = MutableStateFlow(BrowseState())
    val state: StateFlow<BrowseState> = _state.asStateFlow()

    /** Lists [path] on the peer and replaces the current screen state with the result — used
     * both for the initial load and for every breadcrumb/directory navigation, since each is
     * just "list a (possibly different) path". */
    suspend fun load(path: String) {
        _state.value = _state.value.copy(uiState = MeridianUiState.Loading)
        val result = controller.list(path)
        result.fold(
            onSuccess = { listing ->
                val entries = listing.entries.map { e ->
                    BrowseEntry(
                        name = e.name,
                        isDirectory = e.isDirectory,
                        size = e.size,
                        mtimeMs = e.mtimeMs,
                        mime = e.mime,
                        thumbnailUrl = e.thumbnailToken?.let { controller.thumbnailUrl(it) },
                    )
                }.sortedWith(compareByDescending<BrowseEntry> { it.isDirectory }.thenBy { it.name.lowercase() })

                val truncationNotice = if (listing.truncated) {
                    String.format(Locale.US, "Showing the first %,d items in this folder.", listing.entries.size)
                } else {
                    null
                }

                _state.value = _state.value.copy(
                    uiState = if (entries.isEmpty()) {
                        MeridianUiState.Empty("This folder is empty.")
                    } else {
                        MeridianUiState.Content
                    },
                    currentPath = path,
                    breadcrumbs = breadcrumbsFor(path),
                    allEntries = entries,
                    entries = applyFilter(entries, _state.value.filter),
                    truncationNotice = truncationNotice,
                )
            },
            onFailure = { error ->
                _state.value = _state.value.copy(
                    uiState = MeridianUiState.Error(
                        message = error.message ?: "Something went wrong.",
                        onRetry = null,
                    ),
                )
            },
        )
    }

    /** Descends into a directory entry, appending it to [BrowseState.currentPath]. */
    suspend fun open(entry: BrowseEntry) {
        require(entry.isDirectory) { "open() is only for directory entries" }
        val base = _state.value.currentPath.trimEnd('/')
        load("$base/${entry.name}")
    }

    /** Jumps back to an earlier breadcrumb, discarding everything below it. */
    suspend fun navigateTo(breadcrumb: Breadcrumb) {
        load(breadcrumb.path)
    }

    /** Narrows (or widens) the visible listing by MIME-prefix category. No round trip to the
     * peer — [BrowseState.allEntries] already holds everything for [BrowseState.currentPath]. */
    fun setFilter(filter: BrowseFilter) {
        _state.value = _state.value.copy(
            filter = filter,
            entries = applyFilter(_state.value.allEntries, filter),
        )
    }

    /**
     * "Play on PC" (design.md §8, push-to-play): [localPath] is a file on *this* device (an
     * absolute path, never resolved against any root — see [PeerController.streamOnPeer]'s
     * doc). Delegates entirely to the controller, which issues this device's own stream token
     * and sends the peer a single `play` message carrying a URL to this device's own media
     * server — nothing is ever copied to the peer.
     */
    suspend fun playOnPeer(localPath: String) {
        val result = controller.streamOnPeer(localPath)
        result.fold(
            onSuccess = {
                _state.value = _state.value.copy(playbackError = null)
            },
            onFailure = { error ->
                _state.value = _state.value.copy(playbackError = error.message ?: "Couldn't start playback.")
            },
        )
    }

    /** "Play here" (design.md §8): [remotePath] is a file on the peer. Fetches a
     * `/media/{token}` URL to hand to a local Media3 player — never downloads the file. */
    suspend fun playHere(remotePath: String) {
        val result = controller.streamUrlFor(remotePath)
        result.fold(
            onSuccess = { url -> _state.value = _state.value.copy(playbackUrl = url, playbackError = null) },
            onFailure = { error ->
                _state.value = _state.value.copy(
                    playbackUrl = null,
                    playbackError = error.message ?: "Couldn't start playback.",
                )
            },
        )
    }

    /** Closes the local player (or clears a playback error banner) shown for [playHere]. */
    fun dismissPlayback() {
        _state.value = _state.value.copy(playbackUrl = null, playbackError = null)
    }

    private fun applyFilter(entries: List<BrowseEntry>, filter: BrowseFilter): List<BrowseEntry> =
        if (filter == BrowseFilter.All) {
            entries
        } else {
            entries.filter { it.isDirectory || filter.matches(it.mime) }
        }

    /** Splits [path] into a breadcrumb per segment, each carrying the full path up to and
     * including itself, e.g. `/root/Photos` -> `[("root", "/root"), ("Photos", "/root/Photos")]`. */
    private fun breadcrumbsFor(path: String): List<Breadcrumb> {
        val segments = path.trim('/').split('/').filter { it.isNotBlank() }
        if (segments.isEmpty()) return listOf(Breadcrumb(label = "Root", path = "/"))
        var accumulated = ""
        return segments.map { segment ->
            accumulated += "/$segment"
            Breadcrumb(label = segment, path = accumulated)
        }
    }
}
