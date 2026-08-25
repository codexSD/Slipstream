package com.slipstream.meridian.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.slipstream.meridian.MeridianRadius
import com.slipstream.meridian.MeridianTheme

/** 48dp square, Tint fill, `sm` radius, Brand-tinted line icon at ~26dp. */
@Composable
fun MeridianIconTile(
    icon: ImageVector,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    size: Dp = 48.dp,
    onClick: (() -> Unit)? = null,
) {
    val colors = MeridianTheme.colors

    Box(
        modifier = modifier
            .then(if (onClick != null) Modifier.sizeIn(minWidth = 44.dp, minHeight = 44.dp) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(size)
                .clip(RoundedCornerShape(MeridianRadius.sm))
                .background(colors.tint, RoundedCornerShape(MeridianRadius.sm))
                .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = colors.brand,
                modifier = Modifier.size(size * 0.54f),
            )
        }
    }
}

@Preview(name = "Icon tile light")
@Composable
private fun MeridianIconTileLightPreview() {
    MeridianTheme(darkTheme = false) { MeridianIconTile(Icons.Filled.Folder, "Folder") }
}

@Preview(name = "Icon tile dark")
@Composable
private fun MeridianIconTileDarkPreview() {
    MeridianTheme(darkTheme = true) { MeridianIconTile(Icons.Filled.Folder, "Folder") }
}
