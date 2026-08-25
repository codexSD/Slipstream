package com.slipstream.meridian.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import com.slipstream.meridian.MeridianSpacing
import com.slipstream.meridian.MeridianText
import com.slipstream.meridian.MeridianTheme

/** A section title, optionally with a trailing tertiary action. */
@Composable
fun MeridianSectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onActionClick: (() -> Unit)? = null,
) {
    val colors = MeridianTheme.colors

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = MeridianSpacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = title,
            style = MeridianText.itemTitle,
            color = colors.ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )

        if (actionLabel != null && onActionClick != null) {
            MeridianTextButton(label = actionLabel, onClick = onActionClick)
        }
    }
}

@Preview(name = "Section header light")
@Composable
private fun MeridianSectionHeaderLightPreview() {
    MeridianTheme(darkTheme = false) {
        MeridianSectionHeader(title = "Transfers", actionLabel = "See all", onActionClick = {})
    }
}

@Preview(name = "Section header dark")
@Composable
private fun MeridianSectionHeaderDarkPreview() {
    MeridianTheme(darkTheme = true) {
        MeridianSectionHeader(title = "Transfers", actionLabel = "See all", onActionClick = {})
    }
}
