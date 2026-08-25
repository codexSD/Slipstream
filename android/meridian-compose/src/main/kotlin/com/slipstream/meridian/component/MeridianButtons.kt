package com.slipstream.meridian.component

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.slipstream.meridian.MeridianRadius
import com.slipstream.meridian.MeridianSpacing
import com.slipstream.meridian.MeridianText
import com.slipstream.meridian.MeridianTheme

/** Brand fill, On-brand text, `md` radius. One primary per view. */
@Composable
fun MeridianPrimaryButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    fullWidth: Boolean = false,
) {
    val colors = MeridianTheme.colors

    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier
            .then(if (fullWidth) Modifier.fillMaxWidth() else Modifier)
            .height(if (fullWidth) 52.dp else 44.dp),
        shape = RoundedCornerShape(MeridianRadius.md),
        colors = ButtonDefaults.buttonColors(
            containerColor = colors.brand,
            contentColor = colors.onBrand,
            disabledContainerColor = colors.stroke,
            disabledContentColor = colors.inkMuted,
        ),
        elevation = ButtonDefaults.buttonElevation(0.dp, 0.dp, 0.dp, 0.dp, 0.dp),
        contentPadding = ButtonDefaults.ContentPadding,
    ) {
        ButtonContent(label = label, icon = icon)
    }
}

/** Surface fill, 1px stroke, Brand text. For the alternative action. */
@Composable
fun MeridianSecondaryButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
) {
    val colors = MeridianTheme.colors

    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.height(44.dp),
        shape = RoundedCornerShape(MeridianRadius.md),
        border = BorderStroke(1.dp, colors.stroke),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = colors.surface,
            contentColor = colors.brand,
            disabledContentColor = colors.inkMuted,
        ),
    ) {
        ButtonContent(label = label, icon = icon)
    }
}

@Composable
private fun ButtonContent(label: String, icon: ImageVector?) {
    if (icon != null) {
        Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(18.dp))
        androidx.compose.foundation.layout.Spacer(Modifier.size(MeridianSpacing.sm))
    }

    // Sentence case, always. Meridian never uses ALL CAPS.
    Text(text = label, style = MeridianText.button)
}

@Preview(name = "Buttons light")
@Composable
private fun MeridianButtonsLightPreview() {
    MeridianTheme(darkTheme = false) {
        MeridianPrimaryButton("Send files", onClick = {}, icon = Icons.AutoMirrored.Filled.Send)
    }
}

@Preview(name = "Buttons dark")
@Composable
private fun MeridianButtonsDarkPreview() {
    MeridianTheme(darkTheme = true) {
        MeridianSecondaryButton("Browse PC", onClick = {})
    }
}
