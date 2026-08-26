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
    val viewModel = HomeViewModel(peerController)
    val state by viewModel.state.collectAsStateWithLifecycle()

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(MeridianSpacing.md),
        verticalArrangement = Arrangement.spacedBy(MeridianSpacing.lg),
    ) {
        when (state.mode) {
            HomeMode.NeedsPairing -> {
                PairingPrompt(message = state.message)
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

                // Action tiles grid
                // Only "Browse PC" routes to an existing destination (Task 6).
                // "Send files" (Task 10), "Stream to PC" (Task 11), "Send clipboard" (Task 12)
                // navigate to routes that don't exist yet — guard them with null to prevent crashes.
                val actions = listOf(
                    Action(Icons.Filled.FileOpen, "Send files", null), // TODO: Task 10
                    Action(Icons.Filled.Folder, "Browse PC", "browse"), // Task 6 builds BrowseScreen
                    Action(Icons.Filled.Slideshow, "Stream to PC", null), // TODO: Task 11
                    Action(Icons.Filled.ContentCopy, "Send clipboard", null), // TODO: Task 12
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
private fun PairingPrompt(message: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(MeridianSpacing.lg),
        verticalArrangement = Arrangement.spacedBy(MeridianSpacing.lg),
    ) {
        Text(text = message)

        // TODO: Task 5 (pairing screen) — check if "pairing" route exists in SlipstreamNavHost;
        // if yes, navigate there; if no, this button is a no-op placeholder.
        Button(onClick = { /* TODO: navigate to pairing */ }) {
            Text("Start Pairing")
        }
    }
}
