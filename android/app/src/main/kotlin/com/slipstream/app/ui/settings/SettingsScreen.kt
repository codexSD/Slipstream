package com.slipstream.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.slipstream.app.peer.PeerController
import com.slipstream.app.peer.SettingsStore
import com.slipstream.meridian.MeridianText
import com.slipstream.meridian.MeridianTheme
import com.slipstream.meridian.component.MeridianCard
import com.slipstream.meridian.component.MeridianStepper
import kotlinx.coroutines.launch

/**
 * The Settings screen: parallel stream count, download folder, theme, pairing controls,
 * battery exemption status, and 2.4 GHz link info.
 */
@Composable
fun SettingsScreen(
    peerController: PeerController,
    settingsStore: SettingsStore,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val isPaired by peerController.isPaired.collectAsState()

    val parallelStreamCount = remember { mutableStateOf(settingsStore.getParallelStreamCount()) }
    val theme = remember { mutableStateOf(settingsStore.getTheme()) }
    val batteryExempted = remember { mutableStateOf(settingsStore.isBatteryExemptionGranted()) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        // Parallel stream count
        MeridianCard {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = "Parallel streams",
                    style = MeridianText.itemTitle,
                    modifier = Modifier.testTag("parallel-streams-label"),
                )
                Text(
                    text = "More streams transfer files faster but use more bandwidth.",
                    style = MeridianText.body,
                    color = MeridianTheme.colors.inkMuted,
                )
                MeridianStepper(
                    value = parallelStreamCount.value,
                    onValueChange = { newValue ->
                        parallelStreamCount.value = newValue
                        settingsStore.setParallelStreamCount(newValue)
                    },
                    modifier = Modifier.testTag("parallel-streams-stepper"),
                )
            }
        }

        // Theme preference
        MeridianCard {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = "Appearance",
                    style = MeridianText.itemTitle,
                    modifier = Modifier.testTag("theme-label"),
                )
                SettingsStore.Theme.entries.forEach { themeOption ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = themeOption.name.replaceFirstChar { it.uppercase() },
                            style = MeridianText.body,
                        )
                        Switch(
                            checked = theme.value == themeOption,
                            onCheckedChange = { isChecked ->
                                if (isChecked) {
                                    theme.value = themeOption
                                    settingsStore.setTheme(themeOption)
                                }
                            },
                            modifier = Modifier.testTag("theme-switch-${themeOption.name}"),
                        )
                    }
                }
            }
        }

        // Pairing controls
        MeridianCard {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = "Pairing",
                    style = MeridianText.itemTitle,
                    modifier = Modifier.testTag("pairing-label"),
                )
                if (isPaired) {
                    Text(
                        text = "Device is paired.",
                        style = MeridianText.body,
                        color = MeridianTheme.colors.positive,
                        modifier = Modifier.testTag("paired-status"),
                    )
                    Button(
                        onClick = {
                            scope.launch {
                                peerController.unpair()
                            }
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MeridianTheme.colors.critical,
                        ),
                        modifier = Modifier.testTag("unpair-button"),
                    ) {
                        Text("Unpair")
                    }
                } else {
                    Text(
                        text = "Device is not paired.",
                        style = MeridianText.body,
                        color = MeridianTheme.colors.inkMuted,
                        modifier = Modifier.testTag("unpaired-status"),
                    )
                    Button(
                        onClick = {
                            scope.launch {
                                peerController.openPairing().collect { progress ->
                                    // Handle pairing progress inline
                                    when (progress) {
                                        is com.slipstream.app.peer.PairingProgress.CodeReceived -> {
                                            // Code received; in a full implementation, show it to the user
                                            // For now, we just acknowledge the flow started
                                        }
                                        is com.slipstream.app.peer.PairingProgress.Completed -> {
                                            // Pairing completed; UI already reflects via isPaired state
                                        }
                                    }
                                }
                            }
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MeridianTheme.colors.brand,
                        ),
                        modifier = Modifier.testTag("pair-button"),
                    ) {
                        Text("Pair a device")
                    }
                }
            }
        }

        // Battery exemption status
        MeridianCard {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = "Battery",
                    style = MeridianText.itemTitle,
                    modifier = Modifier.testTag("battery-label"),
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = "Battery optimization exemption",
                        style = MeridianText.body,
                    )
                    Text(
                        text = if (batteryExempted.value) "Granted" else "Not granted",
                        style = MeridianText.body,
                        color = if (batteryExempted.value)
                            MeridianTheme.colors.positive
                        else
                            MeridianTheme.colors.inkMuted,
                        modifier = Modifier.testTag("battery-status"),
                    )
                }
            }
        }

        // 2.4 GHz info card
        MeridianCard {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = "Network",
                    style = MeridianText.itemTitle,
                    modifier = Modifier.testTag("network-label"),
                )
                Text(
                    text = "Having your PC host the hotspot is usually faster when the link is 2.4 GHz.",
                    style = MeridianText.body,
                    color = MeridianTheme.colors.inkMuted,
                    modifier = Modifier.testTag("network-info"),
                )
            }
        }
    }
}
