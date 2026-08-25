package com.slipstream.meridian.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import com.slipstream.meridian.MeridianRadius
import com.slipstream.meridian.MeridianSpacing
import com.slipstream.meridian.MeridianText
import com.slipstream.meridian.MeridianTheme

/**
 * The one place Brand fills an area. Everything else in Meridian is ink on a calm
 * surface — a second filled panel would spend the system's one bold move twice.
 */
@Composable
fun MeridianHeaderCard(
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    trailing: @Composable (() -> Unit)? = null,
) {
    val colors = MeridianTheme.colors

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(colors.brand, RoundedCornerShape(MeridianRadius.lg))
            .padding(MeridianSpacing.xl),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MeridianSpacing.md),
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(MeridianSpacing.xs),
        ) {
            Text(
                text = title,
                style = MeridianText.screenTitle,
                color = colors.onBrand,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = subtitle,
                style = MeridianText.label,
                color = colors.onBrandMuted,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }

        trailing?.invoke()
    }
}

@Preview(name = "Header card light")
@Composable
private fun MeridianHeaderCardLightPreview() {
    MeridianTheme(darkTheme = false) {
        MeridianHeaderCard(title = "Pixel 9", subtitle = "Connected over Wi-Fi · 5 GHz")
    }
}

@Preview(name = "Header card dark")
@Composable
private fun MeridianHeaderCardDarkPreview() {
    MeridianTheme(darkTheme = true) {
        MeridianHeaderCard(title = "Pixel 9", subtitle = "Connected over Wi-Fi · 5 GHz")
    }
}
