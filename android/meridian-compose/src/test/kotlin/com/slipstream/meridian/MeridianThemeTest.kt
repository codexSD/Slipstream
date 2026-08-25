package com.slipstream.meridian

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.reflect.full.memberProperties

@RunWith(RobolectricTestRunner::class)
class MeridianThemeTest {

    @get:Rule
    val compose = createComposeRule()

    /** Material's baseline palette. Any of these appearing means a role went unmapped. */
    private val materialBaselineColors = setOf(
        Color(0xFF6650a4), Color(0xFFD0BCFF), Color(0xFF625b71), Color(0xFFCCC2DC),
        Color(0xFF7D5260), Color(0xFFEFB8C8), Color(0xFFFFFBFE), Color(0xFF1C1B1F),
        Color(0xFFE7E0EC), Color(0xFF49454F),
    )

    private fun captureScheme(dark: Boolean): ColorScheme {
        lateinit var scheme: ColorScheme
        compose.setContent {
            MeridianTheme(darkTheme = dark) { scheme = MaterialTheme.colorScheme }
        }
        return scheme
    }

    private fun captureColors(dark: Boolean): MeridianColors {
        lateinit var colors: MeridianColors
        compose.setContent {
            MeridianTheme(darkTheme = dark) { colors = MeridianTheme.colors }
        }
        return colors
    }

    /**
     * compose-ui-test forbids calling `setContent` twice within one test, so any
     * assertion needing both light and dark schemes captures them from a single
     * composition instead of two sequential `captureScheme` calls.
     */
    private fun captureBothSchemes(): Pair<ColorScheme, ColorScheme> {
        lateinit var light: ColorScheme
        lateinit var dark: ColorScheme
        compose.setContent {
            MeridianTheme(darkTheme = false) { light = MaterialTheme.colorScheme }
            MeridianTheme(darkTheme = true) { dark = MaterialTheme.colorScheme }
        }
        return light to dark
    }

    private fun captureBothColors(): Pair<MeridianColors, MeridianColors> {
        lateinit var light: MeridianColors
        lateinit var dark: MeridianColors
        compose.setContent {
            MeridianTheme(darkTheme = false) { light = MeridianTheme.colors }
            MeridianTheme(darkTheme = true) { dark = MeridianTheme.colors }
        }
        return light to dark
    }

    @Test
    fun `no Material 3 role retains its baseline lavender in light mode`() {
        assertNoBaselineColors(captureScheme(dark = false))
    }

    @Test
    fun `no Material 3 role retains its baseline lavender in dark mode`() {
        assertNoBaselineColors(captureScheme(dark = true))
    }

    @Test
    fun `primary maps to brand`() {
        val (light, dark) = captureBothSchemes()
        assertEquals(MeridianTokens.Light.brand, light.primary)
        assertEquals(MeridianTokens.Dark.brand, dark.primary)
    }

    @Test
    fun `background maps to canvas and surface maps to surface`() {
        val scheme = captureScheme(false)
        assertEquals(MeridianTokens.Light.canvas, scheme.background)
        assertEquals(MeridianTokens.Light.surface, scheme.surface)
    }

    @Test
    fun `error maps to critical`() {
        assertEquals(MeridianTokens.Light.critical, captureScheme(false).error)
    }

    @Test
    fun `outline maps to stroke`() {
        assertEquals(MeridianTokens.Light.stroke, captureScheme(false).outline)
    }

    @Test
    fun `onSurfaceVariant maps to ink muted`() {
        assertEquals(MeridianTokens.Light.inkMuted, captureScheme(false).onSurfaceVariant)
    }

    @Test
    fun `the Meridian role set is exposed and mode aware`() {
        val (light, dark) = captureBothColors()

        assertEquals(MeridianTokens.Light.canvas, light.canvas)
        assertEquals(MeridianTokens.Dark.canvas, dark.canvas)
        assertTrue(dark.isDark)
        assertTrue(!light.isDark)
        assertNotEquals(light.brand, dark.brand)
    }

    @Test
    fun `status roles are available and distinct`() {
        val colors = captureColors(dark = false)

        assertEquals(MeridianTokens.Light.positive, colors.positive)
        assertEquals(MeridianTokens.Light.warning, colors.warning)
        assertEquals(MeridianTokens.Light.critical, colors.critical)
        assertEquals(colors.brand, colors.info)
    }

    private fun assertNoBaselineColors(scheme: ColorScheme) {
        val unmapped = ColorScheme::class.memberProperties
            .filter { it.returnType.classifier == Color::class }
            .mapNotNull { property ->
                @Suppress("UNCHECKED_CAST")
                val value = (property as kotlin.reflect.KProperty1<ColorScheme, Color>).get(scheme)
                if (value in materialBaselineColors) "${property.name} = $value" else null
            }

        assertTrue(
            "These Material roles are unmapped and will render Material's baseline palette: $unmapped",
            unmapped.isEmpty(),
        )
    }
}
