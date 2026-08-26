package com.slipstream.app.ui.pairing

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.sp
import com.slipstream.meridian.MeridianSpacing
import com.slipstream.meridian.MeridianText

/**
 * The pairing screen (Task 5): shows the six-digit code both devices must agree on, at
 * hero-metric size with wide tabular spacing so it reads at a glance across a desk, alongside a
 * prompt naming which device to compare it against, a countdown on the 120s window, and
 * confirm/decline actions.
 *
 * This screen owns only itself -- Task 9 wires it into Settings' "Pair a device" entry, and any
 * home-screen prompt for pairing when unpaired, neither of which are this task's job.
 */
@Composable
fun PairingScreen(viewModel: PairingViewModel) {
    val state by viewModel.state.collectAsState()

    LaunchedEffect(Unit) { viewModel.open() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(MeridianSpacing.lg),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = "Pair a device",
            style = MeridianText.screenTitle,
            modifier = Modifier.testTag("pairing-title"),
        )

        Column(
            modifier = Modifier.padding(top = MeridianSpacing.lg),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = state.code ?: "------",
                style = MeridianText.heroMetric.copy(letterSpacing = 8.sp),
                modifier = Modifier.testTag("pairing-code"),
            )
        }

        if (state.prompt.isNotEmpty()) {
            Text(
                text = state.prompt,
                style = MeridianText.body,
                modifier = Modifier
                    .padding(top = MeridianSpacing.md)
                    .testTag("pairing-prompt"),
            )
        }

        if (state.timeRemaining.isNotEmpty()) {
            Text(
                text = state.timeRemaining,
                style = MeridianText.label,
                modifier = Modifier
                    .padding(top = MeridianSpacing.sm)
                    .testTag("pairing-countdown"),
            )
        }

        if (state.status.isNotEmpty()) {
            Text(
                text = state.status,
                style = MeridianText.body,
                modifier = Modifier
                    .padding(top = MeridianSpacing.md)
                    .testTag("pairing-status"),
            )
        }

        Row(
            modifier = Modifier
                .padding(top = MeridianSpacing.lg)
                .fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(MeridianSpacing.md),
        ) {
            OutlinedButton(
                onClick = viewModel::decline,
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = MeridianSpacing.touchTarget)
                    .testTag("pairing-decline"),
            ) {
                Text("Decline")
            }

            Button(
                onClick = viewModel::confirm,
                enabled = state.canConfirm,
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = MeridianSpacing.touchTarget)
                    .testTag("pairing-confirm"),
            ) {
                Text("Confirm")
            }
        }
    }
}
