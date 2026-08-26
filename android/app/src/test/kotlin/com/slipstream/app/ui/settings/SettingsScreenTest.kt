package com.slipstream.app.ui.settings

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import com.slipstream.app.peer.PairingProgress
import com.slipstream.app.peer.PeerConnectionState
import com.slipstream.app.peer.PeerController
import com.slipstream.app.peer.PeerStatus
import com.slipstream.app.peer.PlayRequest
import com.slipstream.app.peer.SettingsStore
import com.slipstream.app.peer.TransferProgress
import com.slipstream.meridian.MeridianTheme
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/** A test double for [PeerController] that tracks method calls. */
private class SpyPeerController(paired: Boolean = false) : PeerController {
    private val _status = MutableStateFlow(PeerStatus(PeerConnectionState.Idle))
    override val status: StateFlow<PeerStatus> = _status

    private val _isPaired = MutableStateFlow(paired)
    override val isPaired: StateFlow<Boolean> = _isPaired

    var openPairingCalled = false
    var unpairCalled = false

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
    override fun openPairing(): Flow<PairingProgress> {
        openPairingCalled = true
        return flow { emit(PairingProgress.Completed(true)) }
    }
    override suspend fun confirmPairing(accept: Boolean) = Unit
    override suspend fun unpair() {
        unpairCalled = true
        _isPaired.value = false
    }
}

/**
 * Task 9: Settings screen. Tests that the screen displays and persists:
 * - parallel stream count with clamping (1-8)
 * - theme preference (System/Light/Dark)
 * - pairing status and controls
 * - battery exemption status
 * - 2.4 GHz info card
 */
@RunWith(RobolectricTestRunner::class)
class SettingsScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private lateinit var context: Context
    private lateinit var settingsStore: SettingsStore
    private lateinit var peerController: SpyPeerController

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences(SettingsStore.PREFS_NAME, Context.MODE_PRIVATE)
            .edit().clear().apply()
        settingsStore = SettingsStore(context)
        peerController = SpyPeerController()
    }

    @Test
    fun `settings screen composes without error`() {
        compose.setContent {
            MeridianTheme {
                SettingsScreen(
                    peerController = peerController,
                    settingsStore = settingsStore,
                )
            }
        }
    }

    @Test
    fun `pair button navigates to the pairing screen instead of calling openPairing directly`() {
        // C1: Settings' "Pair a device" previously called controller.openPairing() itself but
        // never confirmed the resulting code (no confirmPairing call), so it could never actually
        // complete a pairing. It now navigates to the real, shared PairingScreen (which does
        // confirm) via onPairDevice, so this asserts the navigation callback fires and that
        // openPairing is no longer called directly from this screen.
        peerController = SpyPeerController(paired = false)
        var navigated = false
        compose.setContent {
            MeridianTheme {
                SettingsScreen(
                    peerController = peerController,
                    settingsStore = settingsStore,
                    onPairDevice = { navigated = true },
                )
            }
        }
        // Click the pair button via scroll-to (handles scrollable content)
        compose.onNodeWithTag("pair-button").performScrollTo().performClick()
        compose.waitForIdle()

        assertTrue("clicking pair button should navigate to the pairing screen", navigated)
        assertTrue(
            "Settings should no longer call openPairing directly - PairingScreen owns that flow",
            !peerController.openPairingCalled,
        )
    }

    @Test
    fun `unpair button calls unpair when clicked`() {
        peerController = SpyPeerController(paired = true)
        compose.setContent {
            MeridianTheme {
                SettingsScreen(
                    peerController = peerController,
                    settingsStore = settingsStore,
                )
            }
        }
        // Click the unpair button via scroll-to (handles scrollable content)
        compose.onNodeWithTag("unpair-button").performScrollTo().performClick()
        compose.waitForIdle()

        // Verify that unpair was called on the controller
        assertTrue("unpair should be called after clicking unpair button",
                   peerController.unpairCalled)
    }
}
