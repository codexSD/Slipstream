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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.slipstream.app.peer.HistoryStore
import com.slipstream.app.peer.PeerController
import com.slipstream.app.peer.SettingsStore
import com.slipstream.app.peer.TransferQueue
import com.slipstream.meridian.MeridianRadius
import com.slipstream.meridian.MeridianSpacing
import com.slipstream.meridian.MeridianText
import com.slipstream.meridian.MeridianTheme
import com.slipstream.meridian.component.MeridianFilterChip
import com.slipstream.meridian.component.MeridianListRow
import com.slipstream.meridian.component.MeridianStateView
import java.io.File
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
    /** C4.2: download destination folder — [SettingsStore.getDownloadFolder] (Task 9) when
     * supplied, else the row falls back to the platform cache dir via [BrowseViewModel]'s own
     * default. Optional so existing call sites/tests that predate the download action keep
     * compiling unchanged. */
    settingsStore: SettingsStore? = null,
    /** C4.2/C4.5: shared queue a download is enqueued into, so it shows up on the Transfers
     * screen exactly like a Send-screen push does. */
    transferQueue: TransferQueue? = null,
    /** C3: records a completed/failed download into History, same as a completed Send. */
    historyStore: HistoryStore? = null,
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

        val notice = state.notice
        if (notice != null) {
            Text(
                text = notice,
                style = MeridianText.label,
                color = MeridianTheme.colors.brand,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { viewModel.dismissNotice() }
                    .padding(horizontal = MeridianSpacing.md, vertical = MeridianSpacing.xs)
                    .testTag("browse-notice"),
            )
        }

        val playbackError = state.playbackError
        if (playbackError != null) {
            Text(
                text = playbackError,
                style = MeridianText.label,
                color = MeridianTheme.colors.critical,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { viewModel.dismissPlayback() }
                    .padding(horizontal = MeridianSpacing.md, vertical = MeridianSpacing.xs)
                    .testTag("browse-playback-error"),
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
                            onPlayHere = {
                                scope.launch { viewModel.playHere(currentFilePath(state.currentPath, entry)) }
                            },
                            onDownload = if (transferQueue != null && !entry.isDirectory) {
                                {
                                    val remotePath = currentFilePath(state.currentPath, entry)
                                    val folder = settingsStore?.getDownloadFolder()
                                    val destination = File(folder ?: System.getProperty("java.io.tmpdir").orEmpty(), entry.name)
                                    viewModel.download(
                                        remotePath = remotePath,
                                        destination = destination,
                                        size = entry.size,
                                        transferQueue = transferQueue,
                                        historyStore = historyStore,
                                    )
                                }
                            } else {
                                null
                            },
                        )
                    }
                }
            }
        }
    }

    val playbackUrl = state.playbackUrl
    if (playbackUrl != null) {
        VideoPlayerDialog(url = playbackUrl, onDismiss = { viewModel.dismissPlayback() })
    }
}

/** Joins [currentPath] and [entry]'s name into the full path [BrowseViewModel.playOnPeer]/
 * [BrowseViewModel.playHere] expect — the same join [BrowseViewModel.open] already does for
 * directories. */
private fun currentFilePath(currentPath: String, entry: BrowseEntry): String {
    // The peer's own name for the file, exactly as with directories (see BrowseViewModel.open):
    // joining with "/" cannot spell a path on a platform that does not use "/", so downloading
    // or streaming anything on a PC drive asked for a path Windows could not resolve. Falls
    // back to joining only for a peer that sends no path.
    entry.path?.let { return it }

    val base = currentPath.trimEnd('/')
    return "$base/${entry.name}"
}

/** "Play here" (design.md §8): a full-screen Media3 player for [url], closed by [onDismiss].
 * Owns exactly one [ExoPlayer] for the dialog's lifetime, released the moment it leaves
 * composition — never left running once the user backs out. */
@Composable
private fun VideoPlayerDialog(url: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val exoPlayer = remember(url) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(url))
            prepare()
            playWhenReady = true
        }
    }
    DisposableEffect(exoPlayer) {
        onDispose { exoPlayer.release() }
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
            AndroidView(
                factory = { ctx -> PlayerView(ctx).apply { player = exoPlayer } },
                modifier = Modifier.fillMaxSize(),
            )
            IconButton(
                onClick = onDismiss,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(MeridianSpacing.sm)
                    .size(44.dp)
                    .testTag("browse-player-close"),
            ) {
                Icon(imageVector = Icons.Filled.Close, contentDescription = "Close player", tint = Color.White)
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

/** True for any file entry Media3/the system player can plausibly open - the two categories
 * push-to-play and local playback exist for (design.md §8). Directories are never playable. */
private fun BrowseEntry.isPlayableMedia(): Boolean =
    !isDirectory && (mime?.startsWith("video/") == true || mime?.startsWith("audio/") == true)

@Composable
private fun BrowseRow(
    entry: BrowseEntry,
    onClick: () -> Unit,
    onPlayHere: () -> Unit = {},
    /** C4.2: null hides the action entirely (no shared [com.slipstream.app.peer.TransferQueue]
     * wired in, or this is a directory row). */
    onDownload: (() -> Unit)? = null,
) {
    Column {
        MeridianListRow(
            title = entry.name,
            meta = rowMeta(entry),
            leading = { BrowseThumbnail(entry) },
            onClick = onClick,
            modifier = Modifier.testTag("browse-row-${entry.name}"),
        )
        if (entry.isPlayableMedia() || onDownload != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = MeridianSpacing.md, bottom = MeridianSpacing.xs),
                horizontalArrangement = Arrangement.spacedBy(MeridianSpacing.md),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // C2: "Play on PC" is no longer offered here — a remote file the peer already
                // owns has no use case for asking the peer to play it back to itself (see this
                // task's report). Push-to-play's entry point moved to the Send screen, where a
                // *local* file is actually picked.
                if (entry.isPlayableMedia()) {
                    PlaybackActionButton(
                        label = "Play here",
                        onClick = onPlayHere,
                        modifier = Modifier.testTag("browse-play-here-${entry.name}"),
                    )
                }
                if (onDownload != null) {
                    IconButton(
                        onClick = onDownload,
                        modifier = Modifier
                            .size(44.dp)
                            .testTag("browse-download-${entry.name}"),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Download,
                            contentDescription = "Download",
                            tint = MeridianTheme.colors.brand,
                        )
                    }
                }
            }
        }
    }
}

/** A text-only action, sized to a full 44dp tap target (spec's minimum) even though its visible
 * label is much smaller — the whole row height counts, not just the glyph. */
@Composable
private fun PlaybackActionButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .heightIn(min = 44.dp)
            .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = label, style = MeridianText.label, color = MeridianTheme.colors.brand)
    }
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
