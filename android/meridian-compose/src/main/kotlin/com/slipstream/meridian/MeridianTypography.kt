package com.slipstream.meridian

import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Semantic roles, not pixel names. Every style that can carry a number requests
 * `tnum`: this app's content is rates, sizes, and percentages that update several
 * times a second, and proportional figures make the readout visibly jitter.
 */
object MeridianText {

    /** One big number per screen. In Slipstream, the live transfer rate. */
    val heroMetric = TextStyle(
        fontFamily = FontFamily.Default,
        fontSize = 40.sp,
        lineHeight = 46.sp,
        fontWeight = FontWeight.Bold,
        fontFeatureSettings = TABULAR,
    )

    val screenTitle = TextStyle(
        fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.Bold,
    )

    val itemTitle = TextStyle(
        fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.Bold,
        fontFeatureSettings = TABULAR,
    )

    val body = TextStyle(
        fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Normal,
    )

    val label = TextStyle(
        fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Normal,
        fontFeatureSettings = TABULAR,
    )

    val labelBold = TextStyle(
        fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Bold,
        fontFeatureSettings = TABULAR,
    )

    /** Dense secondary data only. Avoid. */
    val micro = TextStyle(
        fontSize = 11.sp, lineHeight = 14.sp, fontWeight = FontWeight.Normal,
    )

    val button = TextStyle(
        fontSize = 14.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium,
    )

    internal const val TABULAR = "tnum"
}

@Composable
internal fun meridianTypography(): Typography = Typography(
    displayLarge = MeridianText.heroMetric,
    displayMedium = MeridianText.heroMetric,
    headlineMedium = MeridianText.screenTitle,
    headlineSmall = MeridianText.screenTitle,
    titleLarge = MeridianText.screenTitle,
    titleMedium = MeridianText.itemTitle,
    titleSmall = MeridianText.itemTitle,
    bodyLarge = MeridianText.body,
    bodyMedium = MeridianText.body,
    bodySmall = MeridianText.label,
    labelLarge = MeridianText.button,
    labelMedium = MeridianText.label,
    labelSmall = MeridianText.micro,
)
