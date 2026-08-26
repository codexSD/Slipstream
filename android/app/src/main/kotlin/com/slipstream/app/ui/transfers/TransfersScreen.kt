package com.slipstream.app.ui.transfers

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.StateFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.slipstream.app.peer.PeerController
import com.slipstream.app.peer.TransferItem
import com.slipstream.meridian.MeridianSpacing
import com.slipstream.meridian.MeridianText
import com.slipstream.meridian.MeridianTheme
import com.slipstream.meridian.component.MeridianStatus
import com.slipstream.meridian.component.MeridianStatusPill

/**
 * The Transfers screen: shows queued and in-progress transfers, with per-item progress,
 * cancel actions, and completion status. Transfers run one at a time; failed transfers
 * do not stop the queue.
 *
 * Each transfer row displays:
 * - File name (title) and path info (meta)
 * - Linear progress bar showing bytes transferred
 * - Status pill (Transferring/Complete/Failed)
 * - Tabular rate and ETA (updated live)
 * - Cancel action (close button)
 */
@Composable
fun TransfersScreen(
    peerController: PeerController,
    modifier: Modifier = Modifier,
    transfersState: StateFlow<List<TransferItem>>? = null,
    /** C4.3: cancels the transfer by id via the shared TransferQueue. Defaults to a no-op so
     * existing previews/tests that don't care about cancellation keep compiling unchanged. */
    onCancel: (String) -> Unit = {},
) {
    val colors = MeridianTheme.colors
    val transfers by transfersState?.collectAsStateWithLifecycle() ?: androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(emptyList()) }

    if (transfers.isEmpty()) {
        // Empty state: no active transfers
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(MeridianSpacing.md),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                "No active transfers",
                style = MeridianText.body,
                color = colors.inkMuted,
                modifier = Modifier.testTag("transfers-empty-state"),
            )
        }
    } else {
        // List of active transfers
        LazyColumn(
            modifier = modifier
                .fillMaxSize()
                .padding(MeridianSpacing.md),
            verticalArrangement = Arrangement.spacedBy(MeridianSpacing.md),
        ) {
            items(transfers, key = { it.id }) { item ->
                TransferRow(
                    item = item,
                    onCancel = { onCancel(item.id) },
                )
            }
        }
    }
}

/**
 * A single transfer row showing progress, rate, ETA, and cancel action.
 */
@Composable
private fun TransferRow(
    item: TransferItem,
    onCancel: () -> Unit,
) {
    val colors = MeridianTheme.colors
    val progress = if (item.totalBytes > 0) {
        (item.bytesTransferred.toFloat() / item.totalBytes).coerceIn(0f, 1f)
    } else {
        0f
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(MeridianSpacing.md)
            .testTag("transfer-row-${item.remotePath}"),
        verticalArrangement = Arrangement.spacedBy(MeridianSpacing.sm),
    ) {
        // Title row with cancel button
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = item.remotePath,
                style = MeridianText.itemTitle,
                color = colors.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )

            IconButton(
                onClick = onCancel,
                modifier = Modifier
                    .testTag("transfer-cancel-${item.remotePath}")
                    .padding(start = MeridianSpacing.sm),
            ) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = "Cancel transfer",
                    tint = colors.inkMuted,
                )
            }
        }

        // Progress bar
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier
                .fillMaxWidth()
                .height(4.dp)
                .testTag("transfer-progress-${item.remotePath}"),
            color = colors.info,
            trackColor = colors.surface,
        )

        // Status, size, rate, and ETA row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // C4.4: a real Complete/Failed/Transferring status, not a hardcoded "Transferring".
            val (pillStatus, pillLabel) = when (item.currentState) {
                TransferItem.State.Transferring -> MeridianStatus.Info to "Transferring"
                TransferItem.State.Complete -> MeridianStatus.Positive to "Complete"
                TransferItem.State.Failed -> MeridianStatus.Critical to "Failed"
            }
            MeridianStatusPill(
                status = pillStatus,
                label = pillLabel,
                modifier = Modifier.testTag("transfer-status-${item.remotePath}"),
            )

            // Tabular metrics: size / rate / ETA (all in tabular numeric style)
            Row(
                horizontalArrangement = Arrangement.spacedBy(MeridianSpacing.md),
                modifier = Modifier.weight(1f, fill = false),
            ) {
                Text(
                    text = item.sizeText,
                    style = MeridianText.label,
                    color = colors.ink,
                    modifier = Modifier.testTag("transfer-size-${item.remotePath}"),
                )
                Text(
                    text = item.rateText,
                    style = MeridianText.label,
                    color = colors.ink,
                    modifier = Modifier.testTag("transfer-rate-${item.remotePath}"),
                )
                Text(
                    text = item.etaText,
                    style = MeridianText.label,
                    color = colors.ink,
                    modifier = Modifier.testTag("transfer-eta-${item.remotePath}"),
                )
            }
        }
    }
}
