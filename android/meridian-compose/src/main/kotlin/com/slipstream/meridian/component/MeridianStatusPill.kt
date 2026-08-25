package com.slipstream.meridian.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.slipstream.meridian.MeridianSpacing
import com.slipstream.meridian.MeridianText
import com.slipstream.meridian.MeridianTheme

/** The three signals, plus in-flight and a neutral. */
enum class MeridianStatus {
    /** Connected, synced, complete. */
    Positive,

    /** Degraded but working — a slow link, a partial result. */
    Warning,

    /** Failed, lost, rejected. */
    Critical,

    /** In flight. Shares Brand: an in-progress item is not an alarm. */
    Info,

    /** Idle or not applicable. */
    Neutral,
}

/**
 * A status word in its signal colour, optionally with an icon.
 *
 * The `label` is required by design: spec §12 mandates that a status never relies on
 * colour alone, so the API makes the non-colour cue impossible to omit rather than
 * leaving it to reviewer discipline.
 *
 * Status colours are for text and small marks — never large fills. A screen full of
 * red is noise, not signal.
 */
@Composable
fun MeridianStatusPill(
    status: MeridianStatus,
    label: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
) {
    val colors = MeridianTheme.colors

    val signal: Color = when (status) {
        MeridianStatus.Positive -> colors.positive
        MeridianStatus.Warning -> colors.warning
        MeridianStatus.Critical -> colors.critical
        MeridianStatus.Info -> colors.info
        MeridianStatus.Neutral -> colors.inkMuted
    }

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MeridianSpacing.xs),
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = signal,
                modifier = Modifier.size(14.dp),
            )
        }

        Text(text = label, style = MeridianText.labelBold, color = signal)
    }
}

@Preview(name = "Status pills light")
@Composable
private fun MeridianStatusPillLightPreview() {
    MeridianTheme(darkTheme = false) {
        MeridianStatusPill(MeridianStatus.Positive, "Connected", icon = Icons.Filled.CheckCircle)
    }
}

@Preview(name = "Status pills dark")
@Composable
private fun MeridianStatusPillDarkPreview() {
    MeridianTheme(darkTheme = true) {
        MeridianStatusPill(MeridianStatus.Critical, "Transfer failed")
    }
}
