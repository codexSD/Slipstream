package com.slipstream.meridian.component

import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.slipstream.meridian.MeridianRadius
import com.slipstream.meridian.MeridianText
import com.slipstream.meridian.MeridianTheme

/** Single-select category filter. Pill radius. Never mixed with assist chips in one group. */
@Composable
fun MeridianFilterChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MeridianTheme.colors

    FilterChip(
        selected = selected,
        onClick = onClick,
        modifier = modifier,
        shape = MeridianRadius.pill,
        label = { Text(text = label, style = MeridianText.label) },
        colors = FilterChipDefaults.filterChipColors(
            containerColor = colors.surface,
            labelColor = colors.inkMuted,
            selectedContainerColor = colors.brand,
            selectedLabelColor = colors.onBrand,
        ),
        border = BorderStroke(1.dp, if (selected) colors.brand else colors.stroke),
    )
}

@Preview(name = "Filter chip light")
@Composable
private fun MeridianFilterChipLightPreview() {
    MeridianTheme(darkTheme = false) { MeridianFilterChip("Video", selected = true, onClick = {}) }
}

@Preview(name = "Filter chip dark")
@Composable
private fun MeridianFilterChipDarkPreview() {
    MeridianTheme(darkTheme = true) { MeridianFilterChip("Video", selected = false, onClick = {}) }
}
