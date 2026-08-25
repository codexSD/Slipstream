package com.slipstream.meridian

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/** Three steps plus pill. Zero radius is never used; nothing is sharper than [sm]. */
object MeridianRadius {
    /** Controls, chips, icon tiles, inner panels, thumbnails. */
    val sm = 12.dp

    /** Buttons, search and input fields. */
    val md = 14.dp

    /** Cards, sheets, feature surfaces. */
    val lg = 16.dp

    /** Avatars, count badges, filter chips. */
    val pill = RoundedCornerShape(50)
}

internal fun meridianShapes(): Shapes = Shapes(
    extraSmall = RoundedCornerShape(MeridianRadius.sm),
    small = RoundedCornerShape(MeridianRadius.sm),
    medium = RoundedCornerShape(MeridianRadius.md),
    large = RoundedCornerShape(MeridianRadius.lg),
    extraLarge = RoundedCornerShape(MeridianRadius.lg),
)
