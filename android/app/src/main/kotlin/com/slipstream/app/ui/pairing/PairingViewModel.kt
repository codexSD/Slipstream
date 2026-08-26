package com.slipstream.app.ui.pairing

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.slipstream.app.peer.PairingProgress
import com.slipstream.app.peer.PeerController
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** The pairing window PeerController.openPairing() promises (pairing.md §5). Once a code has
 * been shown, the peer has this long to confirm before the attempt lapses. */
internal const val PAIRING_WINDOW_MILLIS = 120_000L

/**
 * Everything [PairingScreen] needs to render: the six-digit code (shown at hero-metric size, per
 * Task 5's brief), the prompt naming the other device to compare it against, whether confirming
 * is currently possible, a countdown on the pairing window, and a status line for terminal
 * states (declined, expired, cancelled).
 */
data class PairingUiState(
    val code: String? = null,
    val prompt: String = "",
    val canConfirm: Boolean = false,
    val status: String = "",
    val timeRemaining: String = "",
)

/**
 * Drives the pairing screen against [PeerController]'s `openPairing()`/`confirmPairing()` pair
 * (Task 2). [onCodeReceived] and [onWindowOpened] are this view model's own public surface, not
 * [PeerController] methods -- they're how the flow collected from [PeerController.openPairing]
 * feeds this class's state, and they double as the seams the plan's tests drive directly.
 */
class PairingViewModel(
    private val controller: PeerController,
    private val clock: () -> Long = System::currentTimeMillis,
) : ViewModel() {

    private val _state = MutableStateFlow(PairingUiState())
    val state: StateFlow<PairingUiState> = _state.asStateFlow()

    /** Opens a pairing window against a discovered, not-yet-paired peer and starts collecting
     * [PeerController.openPairing]'s progress. */
    fun open() {
        _state.value = PairingUiState(status = "Waiting for the other device…")
        viewModelScope.launch {
            controller.openPairing().collect { progress ->
                when (progress) {
                    is PairingProgress.CodeReceived -> {
                        val peerName = controller.status.value.peerName ?: "the other device"
                        onCodeReceived(progress.code, peerName)
                        onWindowOpened(closesAt = clock() + PAIRING_WINDOW_MILLIS, now = clock())
                    }

                    is PairingProgress.Completed -> {
                        _state.value = _state.value.copy(
                            canConfirm = false,
                            status = if (progress.paired) "Paired." else "Pairing declined.",
                        )
                    }
                }
            }
        }
    }

    /** Records the code the peer's TLS-verified handshake derived, and who to compare it
     * against. Public so the flow collected in [open] can drive it, and so tests can drive it
     * directly without a real [PeerController]. */
    fun onCodeReceived(code: String, peerName: String) {
        _state.value = _state.value.copy(
            code = code,
            prompt = "Does $peerName show this same code?",
            canConfirm = true,
            status = "",
        )
    }

    /** Updates the countdown against the window [PeerController.openPairing] opened. An already
     * expired window reports itself directly (spec §15: direct, no apology) and withdraws
     * [PairingUiState.canConfirm] rather than leaving a stale, unconfirmable code on screen. */
    fun onWindowOpened(closesAt: Long, now: Long) {
        val remainingMillis = closesAt - now
        if (remainingMillis <= 0) {
            _state.value = _state.value.copy(
                canConfirm = false,
                status = "Pairing window closed. Open it again to retry.",
                timeRemaining = "",
            )
            return
        }

        val totalSeconds = remainingMillis / 1000
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        _state.value = _state.value.copy(
            timeRemaining = "%d:%02d".format(minutes, seconds),
        )
    }

    /** The user confirms the code matches. */
    fun confirm() {
        viewModelScope.launch {
            controller.confirmPairing(accept = true)
        }
        _state.value = _state.value.copy(canConfirm = false)
    }

    /** The user declines -- the codes don't match, or they don't recognise the peer. Cancels
     * without pairing (spec §15: no apology, just what happened). */
    fun decline() {
        viewModelScope.launch {
            controller.confirmPairing(accept = false)
        }
        _state.value = _state.value.copy(
            canConfirm = false,
            status = "Pairing cancelled.",
        )
    }
}
