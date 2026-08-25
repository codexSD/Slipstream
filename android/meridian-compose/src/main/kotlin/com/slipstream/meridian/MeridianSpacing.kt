package com.slipstream.meridian

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * STUB for Task 3 so `MeridianTheme.kt` compiles. Task 4 replaces this with the
 * full 4pt-grid spacing scale (4, 8, 12, 16, 20, 24) per the Global Constraints.
 */
object MeridianSpacing {
    val xs: Dp = 4.dp
    val sm: Dp = 8.dp
    val md: Dp = 12.dp
    val lg: Dp = 16.dp
    val xl: Dp = 20.dp
    val xxl: Dp = 24.dp
}

val LocalMeridianSpacing = staticCompositionLocalOf { MeridianSpacing }
