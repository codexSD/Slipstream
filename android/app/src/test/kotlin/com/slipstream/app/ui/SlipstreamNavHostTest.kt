package com.slipstream.app.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.slipstream.app.peer.PeerConnectionState
import com.slipstream.app.peer.PeerController
import com.slipstream.app.peer.PeerStatus
import com.slipstream.app.peer.PlayRequest
import com.slipstream.app.peer.SettingsStore
import com.slipstream.app.peer.TransferProgress
import com.slipstream.meridian.component.MeridianStatus
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/** A test double for [PeerController]. */
private class FakePeerController : PeerController {
    private val _status = MutableStateFlow(PeerStatus(PeerConnectionState.Idle))
    override val status: StateFlow<PeerStatus> = _status

    private val _isPaired = MutableStateFlow(true)
    override val isPaired: StateFlow<Boolean> = _isPaired

    fun setStatus(newStatus: PeerStatus) {
        _status.value = newStatus
    }

    override suspend fun start() = Unit
    override suspend fun reconnect(): Boolean = false
    override suspend fun list(path: String) = Result.success(com.slipstream.app.peer.ListResult(emptyList(), false))
    override fun thumbnailUrl(token: String): String? = null
    override fun pull(remotePath: String, destination: File): Flow<TransferProgress> = MutableSharedFlow()
    override fun push(localPath: String, remoteName: String): Flow<TransferProgress> = MutableSharedFlow()
    override suspend fun streamOnPeer(remotePath: String) = Result.success(Unit)
    override suspend fun streamUrlFor(remotePath: String) = Result.success("http://example.com")
    override suspend fun sendClipboard(text: String) = Result.success(Unit)
    override val clipboardReceived: SharedFlow<String> = MutableSharedFlow()
    override val playRequests: SharedFlow<PlayRequest> = MutableSharedFlow()
    override fun openPairing(): Flow<com.slipstream.app.peer.PairingProgress> = MutableSharedFlow()
    override suspend fun confirmPairing(accept: Boolean) = Unit
    override suspend fun unpair() = Unit
}

/**
 * Task 3: the nav shell. Five destinations, Home first; a top-bar connection pill whose colour
 * follows [PeerConnectionState] exactly as Plan 5's shell specifies.
 */
@RunWith(RobolectricTestRunner::class)
class SlipstreamNavHostTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `the five destinations are declared in order, Home first`() {
        assertEquals(
            listOf("Home", "Browse", "Transfers", "History", "Settings"),
            SlipstreamDestination.entries.map { it.label },
        )
        assertEquals(SlipstreamDestination.Home, SlipstreamDestination.entries.first())
    }

    @Test
    fun `Home is shown on launch`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val settingsStore = SettingsStore(context)
        compose.setContent {
            SlipstreamNavHost(peerController = FakePeerController(), settingsStore = settingsStore)
        }
        compose.onNodeWithTag("screen-content").assertIsDisplayed()
    }

    @Test
    fun `bottom navigation shows all five destinations and switches screens`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val settingsStore = SettingsStore(context)
        compose.setContent {
            SlipstreamNavHost(peerController = FakePeerController(), settingsStore = settingsStore)
        }

        SlipstreamDestination.entries.forEach { destination ->
            compose.onNodeWithTag("navitem-${destination.route}").assertTextEquals(destination.label)
        }

        compose.onNodeWithTag("navitem-browse").performClick()
        // Task 6 replaced Browse's placeholder Text with the real BrowseScreen - switching to it
        // now renders the browse state view (an empty-folder message, for this fake's empty
        // listing) rather than the literal word "Browse".
        compose.onNodeWithTag("browse-state-view").assertIsDisplayed()
    }

    @Test
    fun `connection state maps to MeridianStatus exactly as Plan 5's shell does`() {
        assertEquals(MeridianStatus.Positive, PeerConnectionState.Connected.toMeridianStatus())
        assertEquals(MeridianStatus.Info, PeerConnectionState.Searching.toMeridianStatus())
        assertEquals(MeridianStatus.Warning, PeerConnectionState.Degraded.toMeridianStatus())
        assertEquals(MeridianStatus.Critical, PeerConnectionState.Lost.toMeridianStatus())
        assertEquals(MeridianStatus.Neutral, PeerConnectionState.Idle.toMeridianStatus())
    }

    @Test
    fun `the degraded pill names the band, per spec §15's worked example`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val settingsStore = SettingsStore(context)
        val controller = FakePeerController()
        controller.setStatus(PeerStatus(PeerConnectionState.Degraded, band = "2.4 GHz"))
        compose.setContent {
            SlipstreamNavHost(peerController = controller, settingsStore = settingsStore)
        }
        compose.onNodeWithText("2.4 GHz — slower link").assertIsDisplayed()
    }
}
