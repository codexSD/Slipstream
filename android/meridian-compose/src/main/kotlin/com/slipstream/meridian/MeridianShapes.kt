package com.slipstream.meridian

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

/**
 * STUB for Task 3 so `MeridianTheme.kt` compiles. Task 4 replaces this with
 * Meridian's radius scale (sm 12dp, md 14dp, lg 16dp, pill 50%) per the
 * Global Constraints — zero radius is never used.
 */
@Composable
internal fun meridianShapes(): Shapes = Shapes(
    extraSmall = RoundedCornerShape(12.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(16.dp),
)
