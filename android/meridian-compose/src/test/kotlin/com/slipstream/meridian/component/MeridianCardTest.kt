package com.slipstream.meridian.component

import androidx.compose.material3.Text
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.slipstream.meridian.MeridianTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MeridianCardTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `renders its content`() {
        compose.setContent { MeridianTheme { MeridianCard { Text("Inside the card") } } }
        compose.onNodeWithText("Inside the card").assertIsDisplayed()
    }

    @Test
    fun `is not clickable without an onClick`() {
        compose.setContent { MeridianTheme { MeridianCard { Text("Static") } } }

        var threw = false
        try {
            compose.onNodeWithText("Static").assertHasClickAction()
        } catch (_: AssertionError) {
            threw = true
        }
        assertTrue("A card with no onClick must not advertise a click action", threw)
    }

    @Test
    fun `invokes onClick when given one`() {
        var clicks = 0
        compose.setContent {
            MeridianTheme { MeridianCard(onClick = { clicks++ }) { Text("Tap me") } }
        }

        compose.onNodeWithText("Tap me").performClick()
        assertTrue(clicks == 1)
    }

    @Test
    fun `renders in dark mode`() {
        compose.setContent {
            MeridianTheme(darkTheme = true) { MeridianCard { Text("Dark card") } }
        }
        compose.onNodeWithText("Dark card").assertIsDisplayed()
    }

    @Test
    fun `section header renders its title`() {
        compose.setContent { MeridianTheme { MeridianSectionHeader(title = "Transfers") } }
        compose.onNodeWithText("Transfers").assertIsDisplayed()
    }

    @Test
    fun `section header action fires`() {
        var clicked = false
        compose.setContent {
            MeridianTheme {
                MeridianSectionHeader(
                    title = "Transfers",
                    actionLabel = "See all",
                    onActionClick = { clicked = true },
                )
            }
        }

        compose.onNodeWithText("See all").performClick()
        assertTrue(clicked)
    }

    @Test
    fun `section header hides the action when no label is given`() {
        compose.setContent { MeridianTheme { MeridianSectionHeader(title = "Transfers") } }
        compose.onNodeWithText("See all").assertDoesNotExist()
    }
}
