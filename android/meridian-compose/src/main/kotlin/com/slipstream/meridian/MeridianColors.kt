package com.slipstream.meridian

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Meridian's role set. Material 3 has no slot for canvas, tint, ink-muted, or the
 * three status signals, so they live here rather than being forced into M3 roles
 * that mean something else.
 */
@Immutable
data class MeridianColors(
    val canvas: Color,
    val surface: Color,
    val stroke: Color,
    val tint: Color,
    val ink: Color,
    val inkMuted: Color,
    val brand: Color,
    val brandStrong: Color,
    val onBrand: Color,
    val onBrandMuted: Color,
    val strong: Color,
    val positive: Color,
    val warning: Color,
    val critical: Color,
    val info: Color,
    val isDark: Boolean,
)

internal val LightMeridianColors = with(MeridianTokens.Light) {
    MeridianColors(
        canvas = canvas, surface = surface, stroke = stroke, tint = tint,
        ink = ink, inkMuted = inkMuted,
        brand = brand, brandStrong = brandStrong, onBrand = onBrand, onBrandMuted = onBrandMuted,
        strong = strong,
        positive = positive, warning = warning, critical = critical, info = info,
        isDark = false,
    )
}

internal val DarkMeridianColors = with(MeridianTokens.Dark) {
    MeridianColors(
        canvas = canvas, surface = surface, stroke = stroke, tint = tint,
        ink = ink, inkMuted = inkMuted,
        brand = brand, brandStrong = brandStrong, onBrand = onBrand, onBrandMuted = onBrandMuted,
        strong = strong,
        positive = positive, warning = warning, critical = critical, info = info,
        isDark = true,
    )
}

val LocalMeridianColors = staticCompositionLocalOf { LightMeridianColors }

/**
 * EVERY role is mapped, deliberately and exhaustively.
 *
 * Leaving one out does not crash, does not warn, and looks fine in the IDE preview —
 * it simply renders Material's baseline lavender on one stock control, in production.
 * Do not delete a line here because "nothing uses it": a future stock component will.
 */
internal fun MeridianColors.toColorScheme(): ColorScheme {
    val base = if (isDark) darkColorScheme() else lightColorScheme()

    return base.copy(
        primary = brand,
        onPrimary = onBrand,
        primaryContainer = tint,
        onPrimaryContainer = if (isDark) ink else brandStrong,
        inversePrimary = brandStrong,

        secondary = brandStrong,
        onSecondary = onBrand,
        secondaryContainer = tint,
        onSecondaryContainer = if (isDark) ink else brandStrong,

        // Meridian has no third accent. Tertiary mirrors brand so a stock component
        // reaching for it cannot introduce a colour the system does not own.
        tertiary = brand,
        onTertiary = onBrand,
        tertiaryContainer = tint,
        onTertiaryContainer = if (isDark) ink else brandStrong,

        background = canvas,
        onBackground = ink,

        surface = surface,
        onSurface = ink,
        surfaceVariant = tint,
        onSurfaceVariant = inkMuted,
        surfaceTint = brand,
        inverseSurface = strong,
        inverseOnSurface = if (isDark) canvas else surface,

        surfaceBright = surface,
        surfaceDim = canvas,
        surfaceContainerLowest = surface,
        surfaceContainerLow = surface,
        surfaceContainer = canvas,
        surfaceContainerHigh = canvas,
        surfaceContainerHighest = canvas,

        error = critical,
        onError = onBrand,
        errorContainer = critical.copy(alpha = 0.12f),
        onErrorContainer = critical,

        outline = stroke,
        outlineVariant = stroke,
        scrim = Color.Black.copy(alpha = 0.4f),
    )
}
