package com.slipstream.meridian.component

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Movie
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import com.slipstream.meridian.MeridianTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MeridianListRowTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `shows title meta and trailing value`() {
        compose.setContent {
            MeridianTheme {
                MeridianListRow(title = "holiday.mkv", meta = "24 Aug 2026", trailingValue = "4.2 GB")
            }
        }

        compose.onNodeWithText("holiday.mkv").assertIsDisplayed()
        compose.onNodeWithText("24 Aug 2026").assertIsDisplayed()
        compose.onNodeWithText("4.2 GB").assertIsDisplayed()
    }

    @Test
    fun `shows a status when supplied`() {
        compose.setContent {
            MeridianTheme {
                MeridianListRow(
                    title = "holiday.mkv",
                    status = MeridianStatus.Info to "Transferring",
                )
            }
        }
        compose.onNodeWithText("Transferring").assertIsDisplayed()
    }

    @Test
    fun `renders a leading slot`() {
        compose.setContent {
            MeridianTheme {
                MeridianListRow(
                    title = "holiday.mkv",
                    leading = { MeridianIconTile(Icons.Filled.Movie, "Video") },
                )
            }
        }
        compose.onNodeWithContentDescription("Video").assertIsDisplayed()
    }

    @Test
    fun `invokes onClick`() {
        var clicks = 0
        compose.setContent {
            MeridianTheme { MeridianListRow(title = "Tap row", onClick = { clicks++ }) }
        }

        compose.onNodeWithText("Tap row").performClick()
        assertEquals(1, clicks)
    }

    @Test
    fun `omits optional slots cleanly`() {
        compose.setContent { MeridianTheme { MeridianListRow(title = "Minimal") } }
        compose.onNodeWithText("Minimal").assertIsDisplayed()
    }

    @Test
    fun `renders in dark mode`() {
        compose.setContent {
            MeridianTheme(darkTheme = true) {
                MeridianListRow(title = "Dark row", meta = "meta", trailingValue = "1.0 GB")
            }
        }
        compose.onNodeWithText("Dark row").assertIsDisplayed()
    }
}
