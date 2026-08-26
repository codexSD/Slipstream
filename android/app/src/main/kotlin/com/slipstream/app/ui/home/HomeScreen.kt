package com.slipstream.app.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Slideshow
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.slipstream.app.peer.PeerController
import com.slipstream.meridian.MeridianSpacing
import com.slipstream.meridian.component.MeridianHeaderCard
import com.slipstream.meridian.component.MeridianHeroMetric
import com.slipstream.meridian.component.MeridianIconTile
import java.util.Locale

/** The four actions on the ready-to-use home screen. */
private data class Action(
    val icon: ImageVector,
    val label: String,
    val routeOrNull: String?,
)

/**
 * The home screen. Shows pairing prompt for unpaired devices; for paired devices, shows
 * the peer name/connection state and four action tiles.
 */
@Composable
fun HomeScreen(
    peerController: PeerController,
    navController: NavController,
    modifier: Modifier = Modifier,
) {
    // I4: wrapped in remember (matching Browse/Send/Settings' own pattern) so a recomposition
    // doesn't construct a fresh HomeViewModel - and the StateFlow it starts collecting from -
    // every single frame.
    val viewModel = remember(peerController) { HomeViewModel(peerController) }
    val state by viewModel.state.collectAsStateWithLifecycle()

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(MeridianSpacing.md),
        verticalArrangement = Arrangement.spacedBy(MeridianSpacing.lg),
    ) {
        when (state.mode) {
            HomeMode.NeedsPairing -> {
                PairingPrompt(message = state.message, onStartPairing = { navController.navigate("pairing") })
            }

            HomeMode.Ready -> {
                // Header card with peer name and connection state
                val subtitle = buildString {
                    append("Connected")
                    state.band?.let { append(" over Wi-Fi · $it") }
                }
                MeridianHeaderCard(
                    title = state.peerName ?: "Unknown Device",
                    subtitle = subtitle,
                )

                // Hero metric: transfer rate or resting label
                val (value, label) = if (state.transferRateMbps != null) {
                    Pair(String.format(Locale.US, "%.1f", state.transferRateMbps), "Transfer rate")
                } else {
                    Pair("—", "Ready")
                }
                MeridianHeroMetric(
                    value = value,
                    label = label,
                    unit = if (state.transferRateMbps != null) "MB/s" else null,
                )

                // Action tiles grid.
                // I2: "Stream to PC" (push-to-play, design.md §8) starts with picking a *local*
                // file to push, not a remote one already on the PC (see this task's C2/I2 report
                // for why) — the Send screen already owns exactly that "pick a local file" flow
                // and now carries its own "Play on PC" action per queued item, so this tile
                // simply opens Send rather than duplicating a picker here. "Send clipboard" opens
                // the minimal text-field-and-send flow built for this task.
                val actions = listOf(
                    Action(Icons.Filled.FileOpen, "Send files", "send"), // Task 10 builds SendSheet
                    Action(Icons.Filled.Folder, "Browse PC", "browse"), // Task 6 builds BrowseScreen
                    Action(Icons.Filled.Slideshow, "Stream to PC", "send"),
                    Action(Icons.Filled.ContentCopy, "Send clipboard", "clipboard"),
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(MeridianSpacing.md),
                ) {
                    actions.forEach { action ->
                        MeridianIconTile(
                            icon = action.icon,
                            contentDescription = action.label,
                            onClick = action.routeOrNull?.let {
                                {
                                    navController.navigate(it)
                                }
                            },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PairingPrompt(message: String, onStartPairing: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(MeridianSpacing.lg),
        verticalArrangement = Arrangement.spacedBy(MeridianSpacing.lg),
    ) {
        Text(text = message)

        // C1: the "pairing" route now exists (SlipstreamNavHost) — routes to the real,
        // fully-tested PairingScreen instead of doing nothing.
        Button(onClick = onStartPairing) {
            Text("Start Pairing")
        }
    }
}
