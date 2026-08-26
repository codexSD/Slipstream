package com.slipstream.app.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.slipstream.app.peer.PeerConnectionState
import com.slipstream.app.peer.PeerController
import com.slipstream.app.peer.TransferProgress
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted

/** The two modes the home screen can be in: pairing setup or ready to use. */
enum class HomeMode {
    NeedsPairing,
    Ready,
}

/** What the home screen renders. */
data class HomeScreenState(
    val mode: HomeMode,
    val message: String,
    val peerName: String? = null,
    val connectionState: PeerConnectionState = PeerConnectionState.Idle,
    val band: String? = null,
    val transferRateMbps: Double? = null,
)

/** Computes the current home screen state based on peer controller state. */
internal fun computeHomeScreenState(peerController: PeerController): HomeScreenState {
    return if (!peerController.isPaired.value) {
        HomeScreenState(
            mode = HomeMode.NeedsPairing,
            message = "Pair a device to get started.",
        )
    } else {
        HomeScreenState(
            mode = HomeMode.Ready,
            message = "",
            peerName = peerController.status.value.peerName,
            connectionState = peerController.status.value.state,
            band = peerController.status.value.band,
        )
    }
}

/** Creates the home screen state flow from a peer controller, for use in tests or ViewModels. */
internal fun createHomeScreenState(
    peerController: PeerController,
    scope: CoroutineScope,
): StateFlow<HomeScreenState> {
    fun computeInitialState(): HomeScreenState {
        return if (!peerController.isPaired.value) {
            HomeScreenState(
                mode = HomeMode.NeedsPairing,
                message = "Pair a device to get started.",
            )
        } else {
            HomeScreenState(
                mode = HomeMode.Ready,
                message = "",
                peerName = peerController.status.value.peerName,
                connectionState = peerController.status.value.state,
                band = peerController.status.value.band,
            )
        }
    }

    return combine(
        peerController.isPaired,
        peerController.status,
    ) { isPaired, status ->
        if (!isPaired) {
            HomeScreenState(
                mode = HomeMode.NeedsPairing,
                message = "Pair a device to get started.",
            )
        } else {
            HomeScreenState(
                mode = HomeMode.Ready,
                message = "",
                peerName = status.peerName,
                connectionState = status.state,
                band = status.band,
            )
        }
    }.stateIn(
        scope,
        SharingStarted.WhileSubscribed(0),
        computeInitialState(),
    )
}

/**
 * Home screen view model. Watches peer status and pairing state, emitting screen state
 * for the UI to render. An unpaired device is offered pairing; a paired device gets the
 * action grid and live transfer metrics.
 */
class HomeViewModel(
    peerController: PeerController,
) : ViewModel() {
    val state: StateFlow<HomeScreenState> = createHomeScreenState(peerController, viewModelScope)
}
