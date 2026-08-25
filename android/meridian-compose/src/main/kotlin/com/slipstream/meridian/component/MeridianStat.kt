package com.slipstream.meridian.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.slipstream.meridian.MeridianSpacing
import com.slipstream.meridian.MeridianText
import com.slipstream.meridian.MeridianTheme

/** An icon tile, a number, and a caption — the compact three-up dashboard unit. */
@Composable
fun MeridianStat(
    icon: ImageVector,
    value: String,
    caption: String,
    modifier: Modifier = Modifier,
) {
    val colors = MeridianTheme.colors

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MeridianSpacing.md),
    ) {
        MeridianIconTile(icon = icon, contentDescription = caption, size = 40.dp)

        Column(verticalArrangement = Arrangement.spacedBy(MeridianSpacing.xs / 2)) {
            Text(text = value, style = MeridianText.itemTitle, color = colors.ink)
            Text(text = caption, style = MeridianText.label, color = colors.inkMuted)
        }
    }
}

@Preview(name = "Stat light")
@Composable
private fun MeridianStatLightPreview() {
    MeridianTheme(darkTheme = false) {
        MeridianStat(Icons.Filled.Download, value = "8", caption = "Queued")
    }
}

@Preview(name = "Stat dark")
@Composable
private fun MeridianStatDarkPreview() {
    MeridianTheme(darkTheme = true) {
        MeridianStat(Icons.Filled.Download, value = "8", caption = "Queued")
    }
}
