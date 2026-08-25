package com.slipstream.meridian.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.slipstream.meridian.MeridianRadius
import com.slipstream.meridian.MeridianText
import com.slipstream.meridian.MeridianTheme

/** Pill count badge. Hidden at zero — an empty badge is visual noise. */
@Composable
fun MeridianBadge(
    count: Int,
    modifier: Modifier = Modifier,
    critical: Boolean = false,
) {
    if (count <= 0) return

    val colors = MeridianTheme.colors

    Box(
        modifier = modifier
            .defaultMinSize(minWidth = 20.dp, minHeight = 20.dp)
            .background(if (critical) colors.critical else colors.brand, MeridianRadius.pill)
            .padding(horizontal = 6.dp, vertical = 2.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = if (count > 99) "99+" else count.toString(),
            style = MeridianText.labelBold,
            color = colors.onBrand,
        )
    }
}

@Preview(name = "Badge light")
@Composable
private fun MeridianBadgeLightPreview() {
    MeridianTheme(darkTheme = false) { MeridianBadge(count = 7) }
}

@Preview(name = "Badge dark")
@Composable
private fun MeridianBadgeDarkPreview() {
    MeridianTheme(darkTheme = true) { MeridianBadge(count = 128, critical = true) }
}
