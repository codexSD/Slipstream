package com.slipstream.meridian.component

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.slipstream.meridian.MeridianText
import com.slipstream.meridian.MeridianTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MeridianHeroMetricTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `shows the value the unit and the label`() {
        compose.setContent {
            MeridianTheme { MeridianHeroMetric(value = "48.2", unit = "MB/s", label = "Transfer rate") }
        }

        compose.onNodeWithText("48.2").assertIsDisplayed()
        compose.onNodeWithText("MB/s").assertIsDisplayed()
        compose.onNodeWithText("Transfer rate").assertIsDisplayed()
    }

    @Test
    fun `works without a unit`() {
        compose.setContent { MeridianTheme { MeridianHeroMetric(value = "12", label = "Queued") } }
        compose.onNodeWithText("12").assertIsDisplayed()
    }

    @Test
    fun `uses the tabular hero style`() {
        // A rate updating four times a second must not jitter.
        assertEquals("tnum", MeridianText.heroMetric.fontFeatureSettings)
    }

    @Test
    fun `renders in dark mode`() {
        compose.setContent {
            MeridianTheme(darkTheme = true) {
                MeridianHeroMetric(value = "48.2", unit = "MB/s", label = "Transfer rate")
            }
        }
        compose.onNodeWithText("48.2").assertIsDisplayed()
    }

    @Test
    fun `stat shows value and caption`() {
        compose.setContent {
            MeridianTheme {
                MeridianStat(Icons.Filled.Download, value = "8", caption = "Queued")
            }
        }

        compose.onNodeWithText("8").assertIsDisplayed()
        compose.onNodeWithText("Queued").assertIsDisplayed()
    }
}
