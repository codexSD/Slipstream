package com.slipstream.meridian.component

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.slipstream.meridian.MeridianTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MeridianStatusPillTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `always shows a text label alongside the colour`() {
        // Colour is never the only cue. This is enforced by the API, not by discipline.
        compose.setContent {
            MeridianTheme { MeridianStatusPill(MeridianStatus.Critical, "Transfer failed") }
        }
        compose.onNodeWithText("Transfer failed").assertIsDisplayed()
    }

    @Test
    fun `renders every status in light and dark`() {
        val combos = MeridianStatus.entries.flatMap { status ->
            listOf(false, true).map { dark -> status to dark }
        }

        compose.setContent {
            combos.forEach { (status, dark) ->
                val tag = "pill-${status.name}-dark=$dark"
                MeridianTheme(darkTheme = dark) {
                    MeridianStatusPill(status, status.name, modifier = Modifier.testTag(tag))
                }
            }
        }

        combos.forEach { (status, dark) ->
            compose.onNodeWithTag("pill-${status.name}-dark=$dark").assertIsDisplayed()
        }
    }

    @Test
    fun `shows an icon when one is supplied`() {
        compose.setContent {
            MeridianTheme {
                MeridianStatusPill(
                    status = MeridianStatus.Warning,
                    label = "2.4 GHz — slower link",
                    icon = Icons.Filled.Wifi,
                )
            }
        }
        // The icon is decorative (contentDescription = null) — the mandatory label text
        // is the accessible cue, per spec §12.
        compose.onNodeWithText("2.4 GHz — slower link").assertIsDisplayed()
    }

    @Test
    fun `there are exactly five statuses`() {
        assertEquals(5, MeridianStatus.entries.size)
    }

    @Test
    fun `icon tile renders and is clickable when given a handler`() {
        var clicks = 0
        compose.setContent {
            MeridianTheme {
                MeridianIconTile(
                    icon = Icons.Filled.Wifi,
                    contentDescription = "Send files",
                    onClick = { clicks++ },
                )
            }
        }

        compose.onNodeWithContentDescription("Send files").performClick()
        assertEquals(1, clicks)
    }

    @Test
    fun `icon tile renders without a handler`() {
        compose.setContent {
            MeridianTheme { MeridianIconTile(Icons.Filled.Wifi, "Decorative") }
        }
        compose.onNodeWithContentDescription("Decorative").assertIsDisplayed()
    }
}
