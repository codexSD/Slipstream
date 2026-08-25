package com.slipstream.meridian.component

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.slipstream.meridian.MeridianRadius
import com.slipstream.meridian.MeridianText
import com.slipstream.meridian.MeridianTheme

/**
 * A bounded integer control. Minus is Ink-muted, plus is Brand, value is tabular.
 *
 * Reach for this only for a genuinely bounded integer — in Slipstream, the parallel
 * stream count. It is not the right control for a free-typed decimal.
 */
@Composable
fun MeridianStepper(
    value: Int,
    onValueChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    min: Int = 1,
    max: Int = 8,
) {
    val colors = MeridianTheme.colors

    Row(
        modifier = modifier
            .border(1.dp, colors.stroke, RoundedCornerShape(MeridianRadius.sm))
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        IconButton(
            onClick = { if (value > min) onValueChange(value - 1) },
            enabled = value > min,
            modifier = Modifier.size(44.dp), // tap target floor
        ) {
            Icon(
                Icons.Filled.Remove,
                contentDescription = "Decrease",
                tint = if (value > min) colors.inkMuted else colors.stroke,
            )
        }

        Text(
            text = value.toString(),
            style = MeridianText.itemTitle,
            color = colors.ink,
            textAlign = TextAlign.Center,
            modifier = Modifier.width(32.dp),
        )

        IconButton(
            onClick = { if (value < max) onValueChange(value + 1) },
            enabled = value < max,
            modifier = Modifier.size(44.dp),
        ) {
            Icon(
                Icons.Filled.Add,
                contentDescription = "Increase",
                tint = if (value < max) colors.brand else colors.stroke,
            )
        }
    }
}

@Preview(name = "Stepper light")
@Composable
private fun MeridianStepperLightPreview() {
    MeridianTheme(darkTheme = false) { MeridianStepper(value = 4, onValueChange = {}) }
}

@Preview(name = "Stepper dark")
@Composable
private fun MeridianStepperDarkPreview() {
    MeridianTheme(darkTheme = true) { MeridianStepper(value = 8, onValueChange = {}) }
}
