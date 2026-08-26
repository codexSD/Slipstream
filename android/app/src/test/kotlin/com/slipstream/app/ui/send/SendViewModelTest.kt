package com.slipstream.app.ui.send

import android.net.Uri
import com.slipstream.app.peer.ListResult
import com.slipstream.app.peer.PairingProgress
import com.slipstream.app.peer.PeerConnectionState
import com.slipstream.app.peer.PeerController
import com.slipstream.app.peer.PeerStatus
import com.slipstream.app.peer.PlayRequest
import com.slipstream.app.peer.TransferProgress
import java.io.File
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** A test double for [PeerController] configurable enough to drive every [SendViewModel]
 * scenario without a real socket. [pushed] records every [push] call actually made, so a test
 * can assert the queue's real end state drove real pushes rather than merely asserting on state
 * left untouched by a no-op fake. */
private class FakeController(
    paired: Boolean = true,
    private val pushFailure: Throwable? = null,
) : PeerController {
    val pushed = mutableListOf<Pair<String, String>>()

    override val status: StateFlow<PeerStatus> = MutableStateFlow(PeerStatus(PeerConnectionState.Connected))
    override val isPaired: StateFlow<Boolean> = MutableStateFlow(paired)

    override suspend fun start() = Unit
    override suspend fun reconnect(): Boolean = true
    override suspend fun list(path: String): Result<ListResult> = Result.success(ListResult(emptyList(), false))
    override fun thumbnailUrl(token: String): String? = null
    override fun pull(remotePath: String, destination: File): Flow<TransferProgress> = MutableSharedFlow()

    override fun push(localPath: String, remoteName: String): Flow<TransferProgress> {
        pushed += localPath to remoteName
        pushFailure?.let { return flow { throw it } }
        val size = File(localPath).length()
        return flow { emit(TransferProgress(size, size)) }
    }

    override suspend fun streamOnPeer(remotePath: String) = Result.success(Unit)
    override suspend fun streamUrlFor(remotePath: String) = Result.success("http://example.com")
    override suspend fun sendClipboard(text: String) = Result.success(Unit)
    override val clipboardReceived: SharedFlow<String> = MutableSharedFlow()
    override val playRequests: SharedFlow<PlayRequest> = MutableSharedFlow()
    override fun openPairing(): Flow<PairingProgress> = MutableSharedFlow()
    override suspend fun confirmPairing(accept: Boolean) = Unit
    override suspend fun unpair() = Unit
}

/**
 * Task 10: send screen. `FolderExpander`'s first production caller — a picked folder's contents
 * are queued with their relative paths preserved, exactly as [PeerController.push]'s `remoteName`
 * needs them to recreate nested structure on the receiver. A multi-item share intent queues every
 * item. Sending with no paired peer explains itself (spec §15) rather than failing silently or
 * queueing a push nobody can complete.
 */
@RunWith(RobolectricTestRunner::class)
class SendViewModelTest {

    private fun tempDir(): File = File.createTempFile("send-test", "").apply {
        delete()
        mkdirs()
    }

    private fun folderWith(vararg relativePaths: String): File {
        val root = tempDir()
        for (rel in relativePaths) {
            val file = File(root, rel)
            file.parentFile?.mkdirs()
            file.writeText("content")
        }
        return root
    }

    private fun fileAt(name: String): File {
        val file = File(tempDir(), name)
        file.writeText("content")
        return file
    }

    private fun uriFor(name: String): Uri = Uri.fromFile(fileAt(name))

    @Test
    fun `a shared folder expands to its files with relative paths preserved`() = runTest {
        val vm = SendViewModel(FakeController())
        vm.onPathsSelected(listOf(folderWith("a.txt", "sub/b.txt")))

        assertEquals(listOf("a.txt", "sub/b.txt"), vm.state.value.items.map { it.relativePath })
    }

    @Test
    fun `a share intent with multiple items queues all of them`() = runTest {
        val vm = SendViewModel(FakeController())
        vm.onShareIntent(listOf(uriFor("one.jpg"), uriFor("two.jpg")))
        assertEquals(listOf("one.jpg", "two.jpg"), vm.state.value.items.map { it.relativePath })
    }

    @Test
    fun `sending with no paired peer explains rather than failing silently`() = runTest {
        val vm = SendViewModel(FakeController(paired = false))
        vm.onPathsSelected(listOf(fileAt("a.txt")))
        assertEquals("Pair a device before sending.", vm.state.value.message)
    }

    @Test
    fun `sending with no paired peer never calls push`() = runTest {
        val controller = FakeController(paired = false)
        val vm = SendViewModel(controller)
        vm.onPathsSelected(listOf(fileAt("a.txt")))

        vm.send()

        assertTrue(controller.pushed.isEmpty())
    }

    @Test
    fun `sending a queued item actually pushes it and then clears the queue`() = runTest {
        val controller = FakeController()
        val vm = SendViewModel(controller)
        val folder = folderWith("a.txt", "sub/b.txt")
        vm.onPathsSelected(listOf(folder))

        vm.send()

        assertEquals(
            setOf(
                File(folder, "a.txt").path to "a.txt",
                File(folder, "sub/b.txt").path to "sub/b.txt",
            ),
            controller.pushed.toSet(),
        )
        assertTrue(vm.state.value.items.isEmpty())
    }

    @Test
    fun `a push failure surfaces its message without losing the rest of the queue silently`() = runTest {
        val controller = FakeController(pushFailure = IllegalStateException("The peer never accepted the file."))
        val vm = SendViewModel(controller)
        vm.onPathsSelected(listOf(fileAt("a.txt")))

        vm.send()

        assertEquals("The peer never accepted the file.", vm.state.value.message)
    }
}
