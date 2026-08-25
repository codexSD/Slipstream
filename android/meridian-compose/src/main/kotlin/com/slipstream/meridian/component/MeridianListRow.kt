package com.slipstream.meridian.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import com.slipstream.meridian.MeridianSpacing
import com.slipstream.meridian.MeridianText
import com.slipstream.meridian.MeridianTheme

/**
 * The workhorse row: optional leading slot, a bold title with muted meta beneath,
 * and a trailing value or status. Titles cap at two lines then ellipsize.
 */
@Composable
fun MeridianListRow(
    title: String,
    modifier: Modifier = Modifier,
    meta: String? = null,
    trailingValue: String? = null,
    status: Pair<MeridianStatus, String>? = null,
    leading: @Composable (() -> Unit)? = null,
    onClick: (() -> Unit)? = null,
) {
    val colors = MeridianTheme.colors

    MeridianCard(modifier = modifier.fillMaxWidth(), onClick = onClick) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(MeridianSpacing.cardInner),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MeridianSpacing.md),
        ) {
            leading?.invoke()

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(MeridianSpacing.xs / 2),
            ) {
                Text(
                    text = title,
                    style = MeridianText.itemTitle,
                    color = colors.ink,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )

                if (meta != null) {
                    Text(
                        text = meta,
                        style = MeridianText.label,
                        color = colors.inkMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            if (trailingValue != null || status != null) {
                Column(
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(MeridianSpacing.xs / 2),
                ) {
                    if (trailingValue != null) {
                        Text(
                            text = trailingValue,
                            style = MeridianText.label,
                            color = colors.ink,
                            textAlign = TextAlign.End,
                            maxLines = 1,
                        )
                    }

                    if (status != null) {
                        MeridianStatusPill(status = status.first, label = status.second)
                    }
                }
            }
        }
    }
}

@Preview(name = "List row light")
@Composable
private fun MeridianListRowLightPreview() {
    MeridianTheme(darkTheme = false) {
        MeridianListRow(
            title = "holiday-2026.mkv",
            meta = "24 Aug 2026",
            trailingValue = "4.2 GB",
            status = MeridianStatus.Info to "Transferring",
        )
    }
}

@Preview(name = "List row dark")
@Composable
private fun MeridianListRowDarkPreview() {
    MeridianTheme(darkTheme = true) {
        MeridianListRow(
            title = "holiday-2026.mkv",
            meta = "24 Aug 2026",
            trailingValue = "4.2 GB",
            status = MeridianStatus.Positive to "Complete",
        )
    }
}
