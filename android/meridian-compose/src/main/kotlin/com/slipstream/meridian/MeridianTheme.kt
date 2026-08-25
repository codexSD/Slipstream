package com.slipstream.meridian

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable

/**
 * The one and only call site of [isSystemInDarkTheme]. Reading it anywhere else lets
 * two screens disagree about which mode they are in, with nothing to catch it —
 * `scripts/check-meridian-tokens.sh` fails the build if another call appears.
 */
@Composable
fun MeridianTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colors = if (darkTheme) DarkMeridianColors else LightMeridianColors

    CompositionLocalProvider(
        LocalMeridianColors provides colors,
        LocalMeridianSpacing provides MeridianSpacing,
    ) {
        MaterialTheme(
            colorScheme = colors.toColorScheme(),
            typography = meridianTypography(),
            shapes = meridianShapes(),
            content = content,
        )
    }
}

/** Accessors, so call sites read `MeridianTheme.colors.critical`. */
object MeridianTheme {
    val colors: MeridianColors
        @Composable @ReadOnlyComposable get() = LocalMeridianColors.current

    val spacing: MeridianSpacing
        @Composable @ReadOnlyComposable get() = LocalMeridianSpacing.current
}
