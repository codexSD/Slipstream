package com.slipstream.meridian.gallery

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.slipstream.meridian.MeridianSpacing
import com.slipstream.meridian.MeridianTheme
import com.slipstream.meridian.component.*

/**
 * Every token and component against the live theme. Use this for a fast visual check
 * of a token change before wiring it into a real screen — and as the source of the
 * screenshot baselines that make an accidental change visible in review.
 *
 * This composable lives in `src/main` (not `src/debug`) so the `screenshotTest`
 * source set can reach it for baseline generation. It is not part of the module's
 * public API surface — it exists for design-system review, not for app screens — but
 * a source-set boundary would force duplicating it for screenshot tests, so the
 * boundary is documentation, not enforcement: do not call this from app code.
 */
@Composable
fun MeridianGallery() {
    val colors = MeridianTheme.colors

    var search by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf("All") }
    var streams by remember { mutableIntStateOf(4) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.canvas)
            .verticalScroll(rememberScrollState())
            .padding(MeridianSpacing.screen),
        verticalArrangement = Arrangement.spacedBy(MeridianSpacing.section),
    ) {
        MeridianSectionHeader("Colour roles")
        Row(horizontalArrangement = Arrangement.spacedBy(MeridianSpacing.sm)) {
            listOf(
                colors.brand, colors.brandStrong, colors.positive,
                colors.warning, colors.critical, colors.tint, colors.stroke,
            ).forEach { Swatch(it) }
        }

        MeridianSectionHeader("Hero metric")
        MeridianHeroMetric(value = "48.2", unit = "MB/s", label = "Transfer rate")

        MeridianSectionHeader("Header card")
        MeridianHeaderCard(title = "Pixel 9", subtitle = "Connected over Wi-Fi · 5 GHz")

        MeridianSectionHeader("Status pills")
        Column(verticalArrangement = Arrangement.spacedBy(MeridianSpacing.sm)) {
            MeridianStatusPill(MeridianStatus.Positive, "Connected", icon = Icons.Filled.CheckCircle)
            MeridianStatusPill(MeridianStatus.Info, "Transferring")
            MeridianStatusPill(MeridianStatus.Warning, "2.4 GHz — slower link", icon = Icons.Filled.Wifi)
            MeridianStatusPill(MeridianStatus.Critical, "Transfer failed")
            MeridianStatusPill(MeridianStatus.Neutral, "Idle")
        }

        MeridianSectionHeader("Icon tiles and stats")
        Row(horizontalArrangement = Arrangement.spacedBy(MeridianSpacing.md)) {
            MeridianIconTile(Icons.AutoMirrored.Filled.Send, "Send files")
            MeridianIconTile(Icons.Filled.Folder, "Browse")
            MeridianIconTile(Icons.Filled.Movie, "Stream")
        }
        MeridianStat(Icons.Filled.Download, value = "8", caption = "Queued")

        MeridianSectionHeader("List rows", actionLabel = "See all", onActionClick = {})
        MeridianListRow(
            title = "holiday-2026.mkv",
            meta = "24 Aug 2026",
            trailingValue = "4.2 GB",
            status = MeridianStatus.Info to "Transferring",
            leading = { MeridianIconTile(Icons.Filled.Movie, "Video", size = 40.dp) },
            onClick = {},
        )
        MeridianListRow(
            title = "backup.zip",
            meta = "22 Aug 2026",
            trailingValue = "1.1 GB",
            status = MeridianStatus.Positive to "Complete",
        )

        MeridianSectionHeader("Controls")
        MeridianSearchField(value = search, onValueChange = { search = it }, placeholder = "Search files")
        Row(horizontalArrangement = Arrangement.spacedBy(MeridianSpacing.sm)) {
            listOf("All", "Video", "Audio", "Images").forEach {
                MeridianFilterChip(it, selected = filter == it, onClick = { filter = it })
            }
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(MeridianSpacing.md),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        ) {
            MeridianStepper(value = streams, onValueChange = { streams = it })
            MeridianBadge(count = 7)
            MeridianBadge(count = 128, critical = true)
        }

        MeridianSectionHeader("Buttons")
        Row(horizontalArrangement = Arrangement.spacedBy(MeridianSpacing.sm)) {
            MeridianPrimaryButton("Send files", onClick = {}, icon = Icons.AutoMirrored.Filled.Send)
            MeridianSecondaryButton("Browse PC", onClick = {})
        }
        MeridianPrimaryButton("Start transfer", onClick = {}, fullWidth = true)

        MeridianSectionHeader("States")
        MeridianCard(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(MeridianSpacing.lg).size(width = 300.dp, height = 140.dp)) {
                MeridianStateView(
                    MeridianUiState.Empty("Nothing transferred yet. Pick a file to send.", "Send a file") {},
                ) {}
            }
        }
        MeridianCard(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(MeridianSpacing.lg).size(width = 300.dp, height = 140.dp)) {
                MeridianStateView(
                    MeridianUiState.Error("Phone not on this network. Searching…", onRetry = {}),
                ) {}
            }
        }
    }
}

@Composable
private fun Swatch(color: Color) {
    Column(
        modifier = Modifier
            .size(40.dp)
            .background(color, androidx.compose.foundation.shape.RoundedCornerShape(12.dp)),
    ) {}
}
