package com.slipstream.meridian

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.dp

/** The 4pt grid, plus the semantic defaults that keep screens consistent. */
@Immutable
object MeridianSpacing {
    val xs = 4.dp
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
    val xl = 20.dp
    val xxl = 24.dp

    /** Outer padding of a scrolling screen. */
    val screen = 16.dp

    /** Padding inside a list-row card. */
    val cardInner = 12.dp

    /** Gap between titled sections. */
    val section = 20.dp

    /** Minimum tap target. */
    val touchTarget = 44.dp
}

val LocalMeridianSpacing = staticCompositionLocalOf { MeridianSpacing }
