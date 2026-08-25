package com.slipstream.meridian.component

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.slipstream.meridian.MeridianTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MeridianActionsTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `header card shows title and subtitle`() {
        compose.setContent {
            MeridianTheme { MeridianHeaderCard(title = "Pixel 9", subtitle = "Connected over Wi-Fi") }
        }

        compose.onNodeWithText("Pixel 9").assertIsDisplayed()
        compose.onNodeWithText("Connected over Wi-Fi").assertIsDisplayed()
    }

    @Test
    fun `primary button fires`() {
        var clicks = 0
        compose.setContent {
            MeridianTheme { MeridianPrimaryButton("Send files", onClick = { clicks++ }) }
        }

        compose.onNodeWithText("Send files").performClick()
        assertEquals(1, clicks)
    }

    @Test
    fun `a disabled primary button does not fire`() {
        var clicks = 0
        compose.setContent {
            MeridianTheme {
                MeridianPrimaryButton("Send files", onClick = { clicks++ }, enabled = false)
            }
        }

        compose.onNodeWithText("Send files").performClick()
        assertEquals(0, clicks)
    }

    @Test
    fun `secondary button fires`() {
        var clicks = 0
        compose.setContent {
            MeridianTheme { MeridianSecondaryButton("Browse PC", onClick = { clicks++ }) }
        }

        compose.onNodeWithText("Browse PC").performClick()
        assertEquals(1, clicks)
    }

    @Test
    fun `stepper increments and decrements within bounds`() {
        var value = 4
        compose.setContent {
            var current by remember { mutableIntStateOf(4) }
            value = current
            MeridianTheme {
                MeridianStepper(
                    value = current,
                    onValueChange = {
                        current = it
                        value = it
                    },
                    min = 1,
                    max = 8,
                )
            }
        }

        compose.onNodeWithContentDescription("Increase").performClick()
        assertEquals(5, value)

        compose.onNodeWithContentDescription("Decrease").performClick()
        assertEquals(4, value)
    }

    @Test
    fun `stepper will not exceed its maximum`() {
        var value = 8
        compose.setContent {
            MeridianTheme {
                MeridianStepper(value = value, onValueChange = { value = it }, min = 1, max = 8)
            }
        }

        compose.onNodeWithContentDescription("Increase").performClick()
        assertEquals(8, value)
    }

    @Test
    fun `stepper will not fall below its minimum`() {
        var value = 1
        compose.setContent {
            MeridianTheme {
                MeridianStepper(value = value, onValueChange = { value = it }, min = 1, max = 8)
            }
        }

        compose.onNodeWithContentDescription("Decrease").performClick()
        assertEquals(1, value)
    }

    @Test
    fun `stepper shows its current value`() {
        compose.setContent {
            MeridianTheme { MeridianStepper(value = 4, onValueChange = {}) }
        }
        compose.onNodeWithText("4").assertIsDisplayed()
    }
}

