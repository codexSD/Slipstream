package com.slipstream.meridian.component

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.material3.Text
import com.slipstream.meridian.MeridianRadius
import com.slipstream.meridian.MeridianTheme

/**
 * The atom of every screen: Surface fill, `lg` radius, 1px stroke, elevation 0.
 *
 * TRAP (spec §13): Material 3's `Surface`/`Card` apply *tonal* elevation by default,
 * which tints the surface colour rather than casting a shadow. Meridian's structure
 * comes from the stroke, so both elevations are pinned to 0.dp. Omitting either one
 * drifts every card off-token with nothing to catch it — no crash, no warning, and
 * the IDE preview looks fine.
 *
 * The card carries no content padding: inset belongs to the caller's content, so one
 * card style can wrap a padded Column or a Constraint-style layout without fighting it.
 */
@Composable
fun MeridianCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = MeridianTheme.colors

    val cardColors = CardDefaults.cardColors(
        containerColor = colors.surface,
        contentColor = colors.ink,
    )
    val elevation = CardDefaults.cardElevation(
        defaultElevation = 0.dp,
        pressedElevation = 0.dp,
        focusedElevation = 0.dp,
        hoveredElevation = 0.dp,
    )
    val border = BorderStroke(1.dp, colors.stroke)
    val shape = RoundedCornerShape(MeridianRadius.lg)

    if (onClick == null) {
        Card(
            modifier = modifier,
            shape = shape,
            colors = cardColors,
            elevation = elevation,
            border = border,
            content = content,
        )
    } else {
        Card(
            onClick = onClick,
            modifier = modifier,
            shape = shape,
            colors = cardColors,
            elevation = elevation,
            border = border,
            content = content,
        )
    }
}

@Preview(name = "Card light")
@Composable
private fun MeridianCardLightPreview() {
    MeridianTheme(darkTheme = false) {
        MeridianCard { Text("Card content", modifier = Modifier.padding(16.dp)) }
    }
}

@Preview(name = "Card dark")
@Composable
private fun MeridianCardDarkPreview() {
    MeridianTheme(darkTheme = true) {
        MeridianCard { Text("Card content", modifier = Modifier.padding(16.dp)) }
    }
}
