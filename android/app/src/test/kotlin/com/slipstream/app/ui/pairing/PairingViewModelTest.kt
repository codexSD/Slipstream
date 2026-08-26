package com.slipstream.app.ui.pairing

import com.slipstream.app.peer.ListResult
import com.slipstream.app.peer.PairingProgress
import com.slipstream.app.peer.PeerController
import com.slipstream.app.peer.PeerConnectionState
import com.slipstream.app.peer.PeerStatus
import com.slipstream.app.peer.TransferProgress
import java.io.File
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** A [PeerController] test double: no real sockets, just enough surface for
 * [PairingViewModel] to drive against and for tests to assert on ([paired]). */
private class FakeController : PeerController {
    override val status: StateFlow<PeerStatus> = MutableStateFlow(PeerStatus(PeerConnectionState.Idle))

    override val isPaired: StateFlow<Boolean> get() = _isPaired
    private val _isPaired = MutableStateFlow(false)

    var paired: Boolean
        get() = _isPaired.value
        private set(value) { _isPaired.value = value }

    override suspend fun start() = Unit

    override suspend fun reconnect(): Boolean = false

    override suspend fun list(path: String): Result<ListResult> =
        Result.success(ListResult(emptyList(), truncated = false))

    override fun pull(remotePath: String, destination: File): Flow<TransferProgress> = emptyFlow()

    override fun push(localPath: String, remoteName: String): Flow<TransferProgress> = emptyFlow()

    override suspend fun streamOnPeer(remotePath: String): Result<Unit> = Result.success(Unit)

    override suspend fun streamUrlFor(remotePath: String): Result<String> = Result.success("")

    override suspend fun sendClipboard(text: String): Result<Unit> = Result.success(Unit)

    override val clipboardReceived: SharedFlow<String> = MutableSharedFlow()

    override fun openPairing(): Flow<PairingProgress> = emptyFlow()

    override suspend fun confirmPairing(accept: Boolean) {
        paired = accept
    }
}

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class PairingViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private val epoch = 0L

    @Test
    fun `shows the code and names the device to compare against`() = runTest {
        val vm = PairingViewModel(FakeController())
        vm.onCodeReceived("482915", peerName = "Desktop-PC")

        assertEquals("482915", vm.state.value.code)
        assertEquals("Does Desktop-PC show this same code?", vm.state.value.prompt)
        assertTrue(vm.state.value.canConfirm)
    }

    @Test
    fun `confirm is unavailable before a code arrives`() {
        assertFalse(PairingViewModel(FakeController()).state.value.canConfirm)
    }

    @Test
    fun `declining cancels without pairing`() = runTest {
        val controller = FakeController()
        val vm = PairingViewModel(controller)
        vm.onCodeReceived("482915", "Desktop-PC")

        vm.decline()

        assertFalse(controller.paired)
        assertEquals("Pairing cancelled.", vm.state.value.status)
    }

    @Test
    fun `counts the 120 second window down`() = runTest {
        val vm = PairingViewModel(FakeController())

        vm.onWindowOpened(closesAt = epoch + 120.seconds.inWholeMilliseconds, now = epoch)

        assertEquals("2:00", vm.state.value.timeRemaining)
    }

    @Test
    fun `an expired window says so and offers to reopen`() = runTest {
        val vm = PairingViewModel(FakeController())

        vm.onWindowOpened(closesAt = epoch, now = epoch + 1.seconds.inWholeMilliseconds)

        assertEquals("Pairing window closed. Open it again to retry.", vm.state.value.status)
    }
}
