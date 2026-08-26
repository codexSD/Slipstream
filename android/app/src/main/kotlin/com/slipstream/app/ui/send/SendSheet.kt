package com.slipstream.app.ui.send

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.slipstream.app.peer.HistoryStore
import com.slipstream.app.peer.PeerController
import com.slipstream.app.peer.TransferQueue
import com.slipstream.meridian.MeridianSpacing
import com.slipstream.meridian.MeridianText
import com.slipstream.meridian.MeridianTheme
import com.slipstream.meridian.component.MeridianPrimaryButton
import com.slipstream.meridian.component.MeridianSecondaryButton
import java.io.File
import kotlinx.coroutines.launch

/**
 * Task 10: the send screen. Lets the user pick one or more files, pick a folder (expanded via
 * [SendViewModel.onPathsSelected] -> `FolderExpander`), or arrive here already carrying items
 * from an incoming `ACTION_SEND`/`ACTION_SEND_MULTIPLE` share intent ([sharedUris]) — then pushes
 * the queue to the paired peer one item at a time.
 */
@Composable
fun SendSheet(
    peerController: PeerController,
    modifier: Modifier = Modifier,
    sharedUris: List<Uri> = emptyList(),
    uriResolver: UriResolver? = null,
    /** C4.1: routes pushes through the shared queue when supplied - see [SendViewModel]'s doc. */
    transferQueue: TransferQueue? = null,
    /** C3: records each push's outcome to History when supplied. */
    historyStore: HistoryStore? = null,
) {
    val context = LocalContext.current
    val resolver = remember(context, uriResolver) { uriResolver ?: ContentUriResolver(context) }
    val viewModel = remember(peerController, transferQueue, historyStore) {
        SendViewModel(peerController, resolver, transferQueue = transferQueue, historyStore = historyStore)
    }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    LaunchedEffect(sharedUris) {
        if (sharedUris.isNotEmpty()) viewModel.onShareIntent(sharedUris)
    }

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) scope.launch { viewModel.onShareIntent(uris) }
    }
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { treeUri ->
        if (treeUri == null) return@rememberLauncherForActivityResult
        val folder = TreePathResolver.resolve(treeUri)
        scope.launch {
            if (folder != null) {
                viewModel.onPathsSelected(listOf(folder))
            } else {
                viewModel.onShareIntent(listOf(treeUri))
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(MeridianSpacing.md),
        verticalArrangement = Arrangement.spacedBy(MeridianSpacing.md),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(MeridianSpacing.sm),
        ) {
            MeridianSecondaryButton(
                label = "Pick files",
                onClick = { filePicker.launch(arrayOf("*/*")) },
                modifier = Modifier.weight(1f).testTag("send-pick-files"),
            )
            MeridianSecondaryButton(
                label = "Pick folder",
                onClick = { folderPicker.launch(null) },
                modifier = Modifier.weight(1f).testTag("send-pick-folder"),
            )
        }

        state.message?.let { message ->
            Text(
                text = message,
                style = MeridianText.body,
                color = MeridianTheme.colors.critical,
                modifier = Modifier.testTag("send-message"),
            )
        }

        if (state.items.isEmpty()) {
            Text(
                text = "Nothing queued yet.",
                style = MeridianText.body,
                color = MeridianTheme.colors.inkMuted,
                modifier = Modifier.testTag("send-empty"),
            )
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(MeridianSpacing.sm),
            ) {
                items(state.items, key = { it.relativePath }) { item ->
                    SendItemRow(
                        item = item,
                        onRemove = { viewModel.remove(item) },
                        onPlayOnPeer = { scope.launch { viewModel.playOnPeer(item) } },
                    )
                }
            }

            MeridianPrimaryButton(
                label = if (state.sending) "Sending…" else "Send",
                onClick = { scope.launch { viewModel.send() } },
                enabled = !state.sending,
                fullWidth = true,
                modifier = Modifier.testTag("send-button"),
            )
        }
    }
}

@Composable
private fun SendItemRow(item: SendItem, onRemove: () -> Unit, onPlayOnPeer: () -> Unit = {}) {
    val colors = MeridianTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("send-item-${item.relativePath}"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MeridianSpacing.sm),
    ) {
        Icon(
            imageVector = Icons.AutoMirrored.Filled.InsertDriveFile,
            contentDescription = null,
            tint = colors.brand,
        )
        Text(
            text = item.relativePath,
            style = MeridianText.itemTitle,
            color = colors.ink,
            modifier = Modifier.weight(1f),
        )
        // C2/I2: push-to-play's real entry point (design.md §8) — this item is genuinely a
        // local file, unlike Browse's old "Play on PC" which fed a remote path into the same
        // controller call.
        androidx.compose.material3.TextButton(
            onClick = onPlayOnPeer,
            modifier = Modifier
                .heightIn(min = 44.dp)
                .testTag("send-play-on-peer-${item.relativePath}"),
        ) {
            Text(text = "Play on PC", style = MeridianText.label, color = colors.brand)
        }
        IconButton(onClick = onRemove, modifier = Modifier.testTag("send-remove-${item.relativePath}")) {
            Icon(imageVector = Icons.Filled.Close, contentDescription = "Remove", tint = colors.inkMuted)
        }
    }
}
