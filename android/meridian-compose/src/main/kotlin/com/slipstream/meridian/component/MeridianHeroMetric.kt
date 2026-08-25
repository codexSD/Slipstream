package com.slipstream.meridian.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.slipstream.meridian.MeridianSpacing
import com.slipstream.meridian.MeridianText
import com.slipstream.meridian.MeridianTheme

/**
 * One big tabular number per screen, in Brand, with a small muted label above it.
 * Rare by design — a screen with two hero metrics has no hero metric.
 */
@Composable
fun MeridianHeroMetric(
    value: String,
    label: String,
    modifier: Modifier = Modifier,
    unit: String? = null,
) {
    val colors = MeridianTheme.colors

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.Start,
        verticalArrangement = Arrangement.spacedBy(MeridianSpacing.xs),
    ) {
        Text(text = label, style = MeridianText.label, color = colors.inkMuted)

        Row(verticalAlignment = Alignment.Bottom) {
            Text(text = value, style = MeridianText.heroMetric, color = colors.brand)

            if (unit != null) {
                Text(
                    text = unit,
                    style = MeridianText.label,
                    color = colors.inkMuted,
                    modifier = Modifier.padding(start = MeridianSpacing.xs, bottom = MeridianSpacing.sm),
                )
            }
        }
    }
}

@Preview(name = "Hero metric light")
@Composable
private fun MeridianHeroMetricLightPreview() {
    MeridianTheme(darkTheme = false) {
        MeridianHeroMetric(value = "48.2", unit = "MB/s", label = "Transfer rate")
    }
}

@Preview(name = "Hero metric dark")
@Composable
private fun MeridianHeroMetricDarkPreview() {
    MeridianTheme(darkTheme = true) {
        MeridianHeroMetric(value = "4.6", unit = "MB/s", label = "Transfer rate")
    }
}
