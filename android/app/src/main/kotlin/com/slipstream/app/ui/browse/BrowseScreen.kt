package com.slipstream.app.ui.browse

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.slipstream.app.peer.PeerController
import com.slipstream.meridian.MeridianRadius
import com.slipstream.meridian.MeridianSpacing
import com.slipstream.meridian.MeridianText
import com.slipstream.meridian.MeridianTheme
import com.slipstream.meridian.component.MeridianFilterChip
import com.slipstream.meridian.component.MeridianListRow
import com.slipstream.meridian.component.MeridianStateView
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch

/**
 * Task 6: the browse screen. Breadcrumb trail, filter chips, and a listing of the current
 * directory on the paired peer - directories first, then files, each with a lazily-loaded
 * thumbnail for image entries the peer supplied a `/thumb/<token>` for.
 */
@Composable
fun BrowseScreen(
    peerController: PeerController,
    modifier: Modifier = Modifier,
    initialPath: String = "/",
) {
    val viewModel = remember(peerController) { BrowseViewModel(peerController) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    LaunchedEffect(peerController) {
        viewModel.load(initialPath)
    }

    Column(modifier = modifier.fillMaxSize()) {
        if (state.breadcrumbs.isNotEmpty()) {
            BreadcrumbRow(
                breadcrumbs = state.breadcrumbs,
                onClick = { crumb -> scope.launch { viewModel.navigateTo(crumb) } },
            )
        }

        LazyRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = MeridianSpacing.md, vertical = MeridianSpacing.sm),
            horizontalArrangement = Arrangement.spacedBy(MeridianSpacing.sm),
        ) {
            items(BrowseFilter.entries.toList()) { filter ->
                MeridianFilterChip(
                    label = filter.label,
                    selected = state.filter == filter,
                    onClick = { viewModel.setFilter(filter) },
                    modifier = Modifier.testTag("browse-filter-${filter.name}"),
                )
            }
        }

        MeridianStateView(
            state = state.uiState,
            modifier = Modifier
                .fillMaxSize()
                .testTag("browse-state-view"),
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                val notice = state.truncationNotice
                if (notice != null) {
                    Text(
                        text = notice,
                        style = MeridianText.label,
                        color = MeridianTheme.colors.inkMuted,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = MeridianSpacing.md, vertical = MeridianSpacing.xs)
                            .testTag("browse-truncation-notice"),
                    )
                }

                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = MeridianSpacing.md),
                    verticalArrangement = Arrangement.spacedBy(MeridianSpacing.sm),
                ) {
                    items(state.entries, key = { it.name }) { entry ->
                        BrowseRow(
                            entry = entry,
                            onClick = {
                                if (entry.isDirectory) {
                                    scope.launch { viewModel.open(entry) }
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun BreadcrumbRow(breadcrumbs: List<Breadcrumb>, onClick: (Breadcrumb) -> Unit) {
    val colors = MeridianTheme.colors
    LazyRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = MeridianSpacing.md, vertical = MeridianSpacing.sm),
        horizontalArrangement = Arrangement.spacedBy(MeridianSpacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        itemsIndexed(breadcrumbs) { index, crumb ->
            val isCurrent = index == breadcrumbs.lastIndex
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = crumb.label,
                    style = MeridianText.label,
                    color = if (isCurrent) colors.ink else colors.inkMuted,
                    modifier = Modifier
                        .testTag("browse-breadcrumb-${crumb.label}")
                        .then(if (isCurrent) Modifier else Modifier.clickable { onClick(crumb) }),
                )
                if (!isCurrent) {
                    Text(text = " / ", style = MeridianText.label, color = colors.inkMuted)
                }
            }
        }
    }
}

@Composable
private fun BrowseRow(entry: BrowseEntry, onClick: () -> Unit) {
    MeridianListRow(
        title = entry.name,
        meta = rowMeta(entry),
        leading = { BrowseThumbnail(entry) },
        onClick = onClick,
        modifier = Modifier.testTag("browse-row-${entry.name}"),
    )
}

private fun rowMeta(entry: BrowseEntry): String? {
    if (entry.isDirectory) return null
    val date = DateFormat.getDateInstance(DateFormat.MEDIUM, Locale.getDefault()).format(Date(entry.mtimeMs))
    return "$date · ${formatSize(entry.size)}"
}

private fun formatSize(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = listOf("KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var unitIndex = -1
    while (value >= 1024 && unitIndex < units.lastIndex) {
        value /= 1024
        unitIndex++
    }
    return String.format(Locale.US, "%.1f %s", value, units[unitIndex.coerceAtLeast(0)])
}

/** A 48dp Tint-filled `sm` tile: the entry's thumbnail once [ThumbnailLoader] fetches it, a
 * folder/file glyph otherwise - never blocking the row's own composition on the network. */
@Composable
private fun BrowseThumbnail(entry: BrowseEntry) {
    val colors = MeridianTheme.colors
    val size = 48.dp

    Box(
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(MeridianRadius.sm))
            .background(colors.tint),
        contentAlignment = Alignment.Center,
    ) {
        val url = entry.thumbnailUrl
        val bitmap = if (url != null) {
            val bitmapState = produceState<Bitmap?>(initialValue = null, key1 = url) {
                value = ThumbnailLoader.load(url)
            }
            bitmapState.value
        } else {
            null
        }

        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = entry.name,
                modifier = Modifier
                    .size(size)
                    .clip(RoundedCornerShape(MeridianRadius.sm)),
            )
        } else {
            Icon(
                imageVector = if (entry.isDirectory) Icons.Filled.Folder else Icons.AutoMirrored.Filled.InsertDriveFile,
                contentDescription = null,
                tint = colors.brand,
                modifier = Modifier.size(26.dp),
            )
        }
    }
}
