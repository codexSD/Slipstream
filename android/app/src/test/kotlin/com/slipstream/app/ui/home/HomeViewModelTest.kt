package com.slipstream.app.ui.home

import app.cash.turbine.test
import com.slipstream.app.peer.PeerConnectionState
import com.slipstream.app.peer.PeerController
import com.slipstream.app.peer.PeerStatus
import com.slipstream.app.peer.TransferProgress
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/** A test double for [PeerController] with configurable [paired] state. */
private class FakeController(paired: Boolean = true) : PeerController {
    private val _status = MutableStateFlow(PeerStatus(PeerConnectionState.Idle))
    override val status: StateFlow<PeerStatus> = _status

    private val _isPaired = MutableStateFlow(paired)
    override val isPaired: StateFlow<Boolean> = _isPaired

    fun setStatus(newStatus: PeerStatus) {
        _status.value = newStatus
    }

    override suspend fun start() = Unit
    override suspend fun reconnect(): Boolean = false
    override suspend fun list(path: String) = Result.success(com.slipstream.app.peer.ListResult(emptyList(), false))
    override fun thumbnailUrl(token: String): String? = null
    override fun pull(remotePath: String, destination: java.io.File): Flow<TransferProgress> = MutableSharedFlow()
    override fun push(localPath: String, remoteName: String): Flow<TransferProgress> = MutableSharedFlow()
    override suspend fun streamOnPeer(remotePath: String) = Result.success(Unit)
    override suspend fun streamUrlFor(remotePath: String) = Result.success("http://example.com")
    override suspend fun sendClipboard(text: String) = Result.success(Unit)
    override val clipboardReceived: kotlinx.coroutines.flow.SharedFlow<String> = MutableSharedFlow()
    override val playRequests: kotlinx.coroutines.flow.SharedFlow<com.slipstream.app.peer.PlayRequest> = MutableSharedFlow()
    override fun openPairing(): Flow<com.slipstream.app.peer.PairingProgress> = MutableSharedFlow()
    override suspend fun confirmPairing(accept: Boolean) = Unit
    override suspend fun unpair() = Unit
}

/**
 * Task 4: home screen. Header card shows peer name and link state; hero metric shows live MB/s
 * during a transfer and a resting label otherwise; four icon-tile actions route correctly; with
 * no paired peer the screen offers pairing instead of the action grid.
 */
class HomeViewModelTest {

    @Test
    fun `an unpaired device is offered pairing rather than dead actions`() = runTest {
        val controller = FakeController(paired = false)
        val vm = HomeViewModel(controller)
        vm.state.test {
            val state = awaitItem()
            assertEquals(HomeMode.NeedsPairing, state.mode)
            assertEquals("Pair a device to get started.", state.message)
        }
    }

    @Test
    fun `a paired device shows the Ready mode`() = runTest {
        val controller = FakeController(paired = true)
        val vm = HomeViewModel(controller)
        vm.state.test {
            val state = awaitItem()
            assertEquals(HomeMode.Ready, state.mode)
        }
    }

    @Test
    fun `a paired device's header card shows the peer name and connection state`() = runTest {
        val controller = FakeController(paired = true)
        controller.setStatus(
            PeerStatus(
                state = PeerConnectionState.Connected,
                peerName = "Pixel 9",
                band = "5 GHz",
            )
        )
        val vm = HomeViewModel(controller)
        vm.state.test {
            val state = awaitItem()
            assertEquals("Pixel 9", state.peerName)
            assertEquals(PeerConnectionState.Connected, state.connectionState)
            assertEquals("5 GHz", state.band)
        }
    }

    // Test that the helper function also works (used by the compose UI test integration)
    @Test
    fun `computeHomeScreenState helper computes correct state for unpaired device`() {
        val controller = FakeController(paired = false)
        val state = computeHomeScreenState(controller)
        assertEquals(HomeMode.NeedsPairing, state.mode)
        assertEquals("Pair a device to get started.", state.message)
    }
}
