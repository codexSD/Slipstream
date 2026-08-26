package com.slipstream.app.ui.history

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.slipstream.app.peer.HistoryEntry
import com.slipstream.meridian.MeridianSpacing
import com.slipstream.meridian.component.MeridianListRow
import com.slipstream.meridian.component.MeridianStatus
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A row displaying a single history entry with actions.
 */
@Composable
fun HistoryListItem(
    entry: HistoryEntry,
    modifier: Modifier = Modifier,
    onOpen: (() -> Unit)? = null,
    onReEnqueue: (() -> Unit)? = null,
) {
    val expanded = remember { mutableStateOf(false) }

    MeridianListRow(
        title = File(entry.path).name,
        modifier = modifier,
        meta = formatTimestamp(entry.timestamp),
        trailingValue = formatSize(entry.size),
        status = getStatusPill(entry.state),
        onClick = if (entry.canOpen) onOpen else null,
        leading = {
            Row(
                horizontalArrangement = Arrangement.spacedBy(MeridianSpacing.xs),
                modifier = Modifier.padding(end = MeridianSpacing.xs),
            ) {
                Text(
                    text = when (entry.direction) {
                        HistoryEntry.Direction.Push -> "Upload"
                        HistoryEntry.Direction.Pull -> "Download"
                    },
                    modifier = Modifier.padding(vertical = MeridianSpacing.xs),
                )
            }
        },
    )

    // Menu for actions
    if (onReEnqueue != null && entry.state == HistoryEntry.State.Completed) {
        Row {
            IconButton(
                onClick = { expanded.value = !expanded.value },
                modifier = Modifier.padding(MeridianSpacing.xs),
            ) {
                Icon(
                    imageVector = Icons.Filled.MoreVert,
                    contentDescription = "Actions",
                )
            }

            DropdownMenu(
                expanded = expanded.value,
                onDismissRequest = { expanded.value = false },
            ) {
                DropdownMenuItem(
                    text = { Text("Run again") },
                    onClick = {
                        expanded.value = false
                        onReEnqueue()
                    },
                )
            }
        }
    }
}

/**
 * Formats a file size in bytes to a human-readable string.
 */
private fun formatSize(bytes: Long): String {
    return when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
        bytes < 1024 * 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024))
        else -> "%.1f GB".format(bytes / (1024.0 * 1024 * 1024))
    }
}

/**
 * Formats a timestamp to a readable date string.
 */
private fun formatTimestamp(timestamp: Long): String {
    val format = SimpleDateFormat("MMM d, yyyy", Locale.getDefault())
    return format.format(Date(timestamp))
}

/**
 * Gets the appropriate status pill (color + label) for a transfer state.
 */
private fun getStatusPill(state: HistoryEntry.State): Pair<MeridianStatus, String> {
    return when (state) {
        HistoryEntry.State.Completed -> MeridianStatus.Positive to "Complete"
        HistoryEntry.State.Failed -> MeridianStatus.Critical to "Failed"
    }
}
