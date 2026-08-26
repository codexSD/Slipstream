package com.slipstream.app.ui.browse

import com.slipstream.app.peer.ListResult
import com.slipstream.app.peer.PairingProgress
import com.slipstream.app.peer.PeerConnectionState
import com.slipstream.app.peer.PeerController
import com.slipstream.app.peer.PeerStatus
import com.slipstream.app.peer.PlayRequest
import com.slipstream.app.peer.TransferProgress
import com.slipstream.core.files.FileEntry
import com.slipstream.meridian.component.MeridianUiState
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A test double for [PeerController] whose [list] behaviour is entirely configurable — enough
 * to drive every [BrowseViewModel] scenario without a real socket. */
private class FakeController(
    private val entries: List<FileEntry> = emptyList(),
    private val truncated: Boolean = false,
    private val failure: Throwable? = null,
    /** Lets a test observe the Loading state before [list] resolves. */
    private val gate: CompletableDeferred<Unit>? = null,
    private val streamOnPeerFailure: Throwable? = null,
    private val streamUrlForResult: Result<String> = Result.success("http://192.168.1.5:53324/media/tok-1"),
) : PeerController {
    override val status: StateFlow<PeerStatus> = MutableStateFlow(PeerStatus(PeerConnectionState.Connected))
    override val isPaired: StateFlow<Boolean> = MutableStateFlow(true)

    /** Every wire message type this fake's [streamOnPeer] "sent", in order - lets a test verify
     * [BrowseViewModel.playOnPeer] drives the real push-to-play sequence (design.md §8: this
     * device issues itself a stream token, then sends the peer one `play`) without needing a
     * real socket. See [RealPeerControllerTest] (a different module) for the wire-level proof
     * that the *production* [com.slipstream.app.peer.RealPeerController.streamOnPeer]
     * really emits both, in order, over a real connection. */
    val sentTypes = mutableListOf<String>()

    /** Any call to [pull]/[push] would append here - stays empty for a correct "Play on PC",
     * which per spec §8 must never copy the file anywhere. */
    val downloads = mutableListOf<String>()

    override suspend fun start() = Unit
    override suspend fun reconnect(): Boolean = true

    override suspend fun list(path: String): Result<ListResult> {
        gate?.await()
        failure?.let { return Result.failure(it) }
        return Result.success(ListResult(entries, truncated))
    }

    override fun thumbnailUrl(token: String): String? = "http://192.168.1.5:53323/thumb/$token"

    override fun pull(remotePath: String, destination: File): Flow<TransferProgress> {
        downloads.add(remotePath)
        return MutableSharedFlow()
    }

    override fun push(localPath: String, remoteName: String): Flow<TransferProgress> {
        downloads.add(localPath)
        return MutableSharedFlow()
    }

    override suspend fun streamOnPeer(localPath: String): Result<Unit> {
        sentTypes.add("stream.request")
        streamOnPeerFailure?.let { return Result.failure(it) }
        sentTypes.add("play")
        return Result.success(Unit)
    }

    override suspend fun streamUrlFor(remotePath: String) = streamUrlForResult
    override suspend fun sendClipboard(text: String) = Result.success(Unit)
    override val clipboardReceived: SharedFlow<String> = MutableSharedFlow()
    override val playRequests: SharedFlow<PlayRequest> = MutableSharedFlow()
    override fun openPairing(): Flow<PairingProgress> = MutableSharedFlow()
    override suspend fun confirmPairing(accept: Boolean) = Unit
    override suspend fun unpair() = Unit
}

private fun entry(
    name: String,
    isDirectory: Boolean = false,
    mime: String? = null,
    thumbnailToken: String? = null,
) = FileEntry(name = name, size = 10L, mtimeMs = 0L, isDirectory = isDirectory, mime = mime, thumbnailToken = thumbnailToken)

/**
 * Task 6: browse screen. Directories sort before files; filter chips narrow by MIME prefix;
 * breadcrumbs push into subfolders and pop back out; a truncated listing says so rather than
 * pretending it's complete; loading/content/empty/error drive one MeridianStateView; and every
 * image entry with a thumbnail token gets a working `/thumb/<token>` URL — closing the Plan 3
 * deviation where ThumbnailProvider had no production caller.
 */
class BrowseViewModelTest {

    @Test
    fun `directories sort before files regardless of wire order`() = runTest {
        val vm = BrowseViewModel(
            FakeController(entries = listOf(entry("z.txt"), entry("Photos", isDirectory = true), entry("a.txt"))),
        )
        vm.load("/root")

        val names = vm.state.value.entries.map { it.name }
        assertEquals(listOf("Photos", "a.txt", "z.txt"), names)
    }

    @Test
    fun `a truncated listing says so rather than pretending it is complete`() = runTest {
        val vm = BrowseViewModel(FakeController(entries = List(5000) { entry("f$it.txt") }, truncated = true))
        vm.load("/storage/emulated/0")
        assertEquals("Showing the first 5,000 items in this folder.", vm.state.value.truncationNotice)
    }

    @Test
    fun `a non-truncated listing carries no truncation notice`() = runTest {
        val vm = BrowseViewModel(FakeController(entries = listOf(entry("a.txt")), truncated = false))
        vm.load("/root")
        assertNull(vm.state.value.truncationNotice)
    }

    @Test
    fun `entries carry thumbnail urls when the peer supplied a token`() = runTest {
        val vm = BrowseViewModel(FakeController(entries = listOf(entry("photo.jpg", mime = "image/jpeg", thumbnailToken = "abc123"))))
        vm.load("/DCIM")
        assertTrue(vm.state.value.entries.first().thumbnailUrl!!.endsWith("/thumb/abc123"))
    }

    @Test
    fun `an entry with no thumbnail token has no thumbnail url`() = runTest {
        val vm = BrowseViewModel(FakeController(entries = listOf(entry("notes.txt", mime = "text/plain"))))
        vm.load("/root")
        assertNull(vm.state.value.entries.first().thumbnailUrl)
    }

    @Test
    fun `selecting the Video filter shows only video-mime entries, directories always included`() = runTest {
        val vm = BrowseViewModel(
            FakeController(
                entries = listOf(
                    entry("clip.mp4", mime = "video/mp4"),
                    entry("song.mp3", mime = "audio/mpeg"),
                    entry("Movies", isDirectory = true),
                ),
            ),
        )
        vm.load("/root")

        vm.setFilter(BrowseFilter.Video)

        val names = vm.state.value.entries.map { it.name }.toSet()
        assertEquals(setOf("clip.mp4", "Movies"), names)
    }

    @Test
    fun `the All filter shows every entry`() = runTest {
        val vm = BrowseViewModel(
            FakeController(entries = listOf(entry("clip.mp4", mime = "video/mp4"), entry("song.mp3", mime = "audio/mpeg"))),
        )
        vm.load("/root")
        vm.setFilter(BrowseFilter.Video)
        vm.setFilter(BrowseFilter.All)

        assertEquals(2, vm.state.value.entries.size)
    }

    @Test
    fun `opening a directory pushes a breadcrumb and lists the subfolder`() = runTest {
        val vm = BrowseViewModel(FakeController(entries = listOf(entry("Photos", isDirectory = true))))
        vm.load("/root")

        vm.open(vm.state.value.entries.single())

        assertEquals("/root/Photos", vm.state.value.currentPath)
        assertEquals(listOf("root", "Photos"), vm.state.value.breadcrumbs.map { it.label })
    }

    @Test
    fun `navigating to an earlier breadcrumb pops back out`() = runTest {
        val vm = BrowseViewModel(FakeController(entries = listOf(entry("Photos", isDirectory = true))))
        vm.load("/root")
        vm.open(vm.state.value.entries.single())
        assertEquals(2, vm.state.value.breadcrumbs.size)

        vm.navigateTo(vm.state.value.breadcrumbs.first())

        assertEquals("/root", vm.state.value.currentPath)
        assertEquals(listOf("root"), vm.state.value.breadcrumbs.map { it.label })
    }

    @Test
    fun `an empty folder shows the empty state, not content`() = runTest {
        val vm = BrowseViewModel(FakeController(entries = emptyList()))
        vm.load("/root")
        assertTrue(vm.state.value.uiState is MeridianUiState.Empty)
    }

    @Test
    fun `a non-empty folder shows the content state`() = runTest {
        val vm = BrowseViewModel(FakeController(entries = listOf(entry("a.txt"))))
        vm.load("/root")
        assertTrue(vm.state.value.uiState is MeridianUiState.Content)
    }

    @Test
    fun `a failed list surfaces the direct, no-apology message rather than a stack trace`() = runTest {
        val vm = BrowseViewModel(FakeController(failure = IllegalStateException("That folder is no longer there.")))
        vm.load("/gone")
        val state = vm.state.value.uiState
        assertTrue(state is MeridianUiState.Error)
        assertEquals("That folder is no longer there.", (state as MeridianUiState.Error).message)
    }

    @Test
    fun `state is Loading before the list call resolves`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val vm = BrowseViewModel(FakeController(entries = listOf(entry("a.txt")), gate = gate))

        val job = async(Dispatchers.Unconfined) { vm.load("/root") }
        assertTrue(vm.state.value.uiState is MeridianUiState.Loading)

        gate.complete(Unit)
        job.await()
        assertTrue(vm.state.value.uiState is MeridianUiState.Content)
    }

    // --- Task 11: push-to-play and local playback ---

    @Test
    fun `play on PC sends stream request then play, and does not download`() = runTest {
        val controller = FakeController()
        val vm = BrowseViewModel(controller)

        vm.playOnPeer("/DCIM/holiday.mp4")

        assertEquals(listOf("stream.request", "play"), controller.sentTypes)
        assertTrue(controller.downloads.isEmpty())
    }

    @Test
    fun `a failed playOnPeer surfaces a message rather than throwing`() = runTest {
        val controller = FakeController(streamOnPeerFailure = IllegalStateException("Not connected"))
        val vm = BrowseViewModel(controller)

        vm.playOnPeer("/DCIM/holiday.mp4")

        assertEquals("Not connected", vm.state.value.playbackError)
        assertNull(vm.state.value.playbackUrl)
    }

    @Test
    fun `play here fetches the peer's stream url and stores it for the player to open`() = runTest {
        val controller = FakeController(streamUrlForResult = Result.success("http://192.168.1.5:53324/media/tok-9"))
        val vm = BrowseViewModel(controller)

        vm.playHere("/DCIM/holiday.mp4")

        assertEquals("http://192.168.1.5:53324/media/tok-9", vm.state.value.playbackUrl)
        assertTrue(controller.downloads.isEmpty())
    }

    @Test
    fun `a failed playHere surfaces a message instead of a url`() = runTest {
        val controller = FakeController(streamUrlForResult = Result.failure(IllegalStateException("The peer refused to stream that file.")))
        val vm = BrowseViewModel(controller)

        vm.playHere("/DCIM/holiday.mp4")

        assertNull(vm.state.value.playbackUrl)
        assertEquals("The peer refused to stream that file.", vm.state.value.playbackError)
    }

    @Test
    fun `dismissPlayback clears both the url and any error`() = runTest {
        val controller = FakeController(streamUrlForResult = Result.success("http://192.168.1.5:53324/media/tok-9"))
        val vm = BrowseViewModel(controller)
        vm.playHere("/DCIM/holiday.mp4")
        assertEquals("http://192.168.1.5:53324/media/tok-9", vm.state.value.playbackUrl)

        vm.dismissPlayback()

        assertNull(vm.state.value.playbackUrl)
        assertNull(vm.state.value.playbackError)
    }
}
