package com.slipstream.app.ui.transfers

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.slipstream.app.peer.PeerController
import com.slipstream.app.peer.TransferProgress
import com.slipstream.meridian.MeridianSpacing
import com.slipstream.meridian.MeridianText
import com.slipstream.meridian.component.MeridianListRow
import com.slipstream.meridian.component.MeridianStatus
import kotlinx.coroutines.flow.Flow

/**
 * The Transfers screen: shows queued and in-progress transfers, with per-item progress,
 * cancel actions, and completion status. Transfers run one at a time; failed transfers
 * do not stop the queue.
 */
@Composable
fun TransfersScreen(
    peerController: PeerController,
    modifier: Modifier = Modifier,
) {
    // Placeholder implementation: shows an empty state message
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(MeridianSpacing.md),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            "No active transfers",
            style = MeridianText.body,
            modifier = Modifier.testTag("transfers-empty-state"),
        )
    }
}
