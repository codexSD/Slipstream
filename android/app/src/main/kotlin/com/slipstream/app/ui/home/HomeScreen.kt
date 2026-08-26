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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavController
import com.slipstream.app.peer.PeerController
import com.slipstream.meridian.MeridianSpacing
import com.slipstream.meridian.component.MeridianHeaderCard
import com.slipstream.meridian.component.MeridianHeroMetric
import com.slipstream.meridian.component.MeridianIconTile

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
    val viewModel = HomeViewModel(peerController)
    val state by viewModel.state.collectAsState()

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(MeridianSpacing.md),
        verticalArrangement = Arrangement.spacedBy(MeridianSpacing.lg),
    ) {
        when (state.mode) {
            HomeMode.NeedsPairing -> {
                PairingPrompt()
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
                    Pair("%.1f".format(state.transferRateMbps), "Transfer rate")
                } else {
                    Pair("—", "Ready")
                }
                MeridianHeroMetric(
                    value = value,
                    label = label,
                    unit = if (state.transferRateMbps != null) "MB/s" else null,
                )

                // Action tiles grid
                val actions = listOf(
                    Action(Icons.Filled.FileOpen, "Send files", "send-files"),
                    Action(Icons.Filled.Folder, "Browse PC", "browse"),
                    Action(Icons.Filled.Slideshow, "Stream to PC", "stream-to-pc"),
                    Action(Icons.Filled.ContentCopy, "Send clipboard", "send-clipboard"),
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
private fun PairingPrompt(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(MeridianSpacing.md),
        verticalArrangement = Arrangement.Center,
    ) {
        // TODO: Implement pairing prompt UI
        // For now, just a placeholder
    }
}
