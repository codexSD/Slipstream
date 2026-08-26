package com.slipstream.app.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.slipstream.app.peer.PeerConnectionState
import com.slipstream.app.peer.PeerController
import com.slipstream.app.peer.PeerStatus
import com.slipstream.app.peer.SettingsStore
import com.slipstream.app.ui.home.HomeScreen
import com.slipstream.app.ui.settings.SettingsScreen
import com.slipstream.meridian.MeridianTheme
import com.slipstream.meridian.component.MeridianStatus
import com.slipstream.meridian.component.MeridianStatusPill
import kotlinx.coroutines.flow.StateFlow

/**
 * The app's five top-level screens (Plan 5's shell). Order here is both the bottom navigation's
 * visual order and, via [entries], the order asserted by tests — Home first, as the launch
 * destination.
 *
 * Screens themselves are placeholders; Tasks 4-9 build the real content behind each route.
 */
enum class SlipstreamDestination(val route: String, val label: String, val icon: ImageVector) {
    Home("home", "Home", Icons.Filled.Home),
    Browse("browse", "Browse", Icons.Filled.Folder),
    Transfers("transfers", "Transfers", Icons.Filled.SwapVert),
    History("history", "History", Icons.Filled.History),
    Settings("settings", "Settings", Icons.Filled.Settings),
}

/**
 * Maps the peer's connection state to the signal colour the top-bar pill renders, per Plan 5's
 * shell: Connected is good news, Searching/transferring is merely in flight (not an alarm),
 * Degraded still works but is worth flagging, and Lost is the one truly bad state. [Idle] isn't
 * called out in the plan; Neutral is the obvious fit for "nothing to report yet".
 */
internal fun PeerConnectionState.toMeridianStatus(): MeridianStatus = when (this) {
    PeerConnectionState.Connected -> MeridianStatus.Positive
    PeerConnectionState.Searching -> MeridianStatus.Info
    PeerConnectionState.Degraded -> MeridianStatus.Warning
    PeerConnectionState.Lost -> MeridianStatus.Critical
    PeerConnectionState.Idle -> MeridianStatus.Neutral
}

/** The pill's required text label (spec §12: colour is never the only cue). Wording for
 * [PeerConnectionState.Searching] and [PeerConnectionState.Degraded] follows spec §15's worked
 * examples exactly ("Phone not on this network. Searching…" and "2.4 GHz — slower link" — band
 * first, so the observed speed is explained rather than mysterious). Connected/Lost/Idle have no
 * worked example in §15, so their wording here is this task's own choice. */
internal fun pillLabel(status: PeerStatus): String = when (status.state) {
    PeerConnectionState.Connected -> status.peerName ?: "Connected"
    PeerConnectionState.Searching -> "Phone not on this network. Searching…"
    PeerConnectionState.Degraded -> status.band?.let { "$it — slower link" } ?: "Degraded"
    PeerConnectionState.Lost -> "Disconnected"
    PeerConnectionState.Idle -> "Not connected"
}

/**
 * The app's navigation shell: bottom navigation (a phone, not the desktop's sidebar) plus a top
 * bar carrying the current screen's title and the connection pill inline-end. Wraps the whole
 * [NavHost] in exactly one [MeridianTheme] call, per the design system's constraint that
 * `isSystemInDarkTheme()` has a single call site (inside [MeridianTheme] itself).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SlipstreamNavHost(peerController: PeerController, settingsStore: SettingsStore) {
    val peerStatus = peerController.status
    MeridianTheme {
        val navController = rememberNavController()
        val backStackEntry by navController.currentBackStackEntryAsState()
        val currentDestination = SlipstreamDestination.entries.firstOrNull {
            it.route == backStackEntry?.destination?.route
        } ?: SlipstreamDestination.Home
        val status by peerStatus.collectAsState()

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(currentDestination.label) },
                    actions = {
                        MeridianStatusPill(
                            status = status.state.toMeridianStatus(),
                            label = pillLabel(status),
                            modifier = Modifier.padding(end = 16.dp),
                        )
                    },
                )
            },
            bottomBar = {
                NavigationBar {
                    SlipstreamDestination.entries.forEach { destination ->
                        NavigationBarItem(
                            modifier = Modifier.testTag("navitem-${destination.route}"),
                            selected = currentDestination == destination,
                            onClick = {
                                navController.navigate(destination.route) {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(destination.icon, contentDescription = destination.label) },
                            label = { Text(destination.label) },
                        )
                    }
                }
            },
        ) { contentPadding ->
            NavHost(
                navController = navController,
                startDestination = SlipstreamDestination.Home.route,
                modifier = Modifier.padding(contentPadding),
            ) {
                composable(SlipstreamDestination.Home.route) {
                    HomeScreen(
                        peerController = peerController,
                        navController = navController,
                        modifier = Modifier.testTag("screen-content"),
                    )
                }
                composable(SlipstreamDestination.Settings.route) {
                    SettingsScreen(
                        peerController = peerController,
                        settingsStore = settingsStore,
                        modifier = Modifier.testTag("screen-content"),
                    )
                }
                SlipstreamDestination.entries.filterNot { it == SlipstreamDestination.Home || it == SlipstreamDestination.Settings }.forEach { destination ->
                    composable(destination.route) {
                        Text(destination.label, modifier = Modifier.testTag("screen-content"))
                    }
                }
            }
        }
    }
}
