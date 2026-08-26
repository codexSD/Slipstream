package com.slipstream.app.ui.history

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.slipstream.meridian.MeridianSpacing
import com.slipstream.meridian.MeridianText
import com.slipstream.meridian.MeridianTheme

/**
 * The History screen displays completed and failed file transfers in a scrollable list.
 * Newest entries appear first. Users can open a file or re-run a transfer.
 */
@Composable
fun HistoryScreen(
    viewModel: HistoryViewModel,
    modifier: Modifier = Modifier,
) {
    val entries by viewModel.entries.collectAsState()

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(MeridianSpacing.md),
        verticalArrangement = Arrangement.spacedBy(MeridianSpacing.md),
    ) {
        Text(
            text = "Transfer history",
            style = MeridianText.screenTitle,
            color = MeridianTheme.colors.ink,
        )

        if (entries.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(MeridianSpacing.lg),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = "No transfers yet",
                    style = MeridianText.body,
                    color = MeridianTheme.colors.inkMuted,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(MeridianSpacing.sm),
            ) {
                items(entries, key = { it.id }) { entry ->
                    HistoryListItem(
                        entry = entry,
                        onOpen = {
                            // Open file action would be implemented here
                            // For now, this is a placeholder that the UI shell would wire up
                        },
                        onReEnqueue = {
                            viewModel.reEnqueueEntry(entry)
                        },
                    )
                }
            }
        }
    }
}
