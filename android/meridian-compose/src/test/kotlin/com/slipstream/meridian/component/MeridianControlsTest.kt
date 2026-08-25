package com.slipstream.meridian.component

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.slipstream.meridian.MeridianTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MeridianControlsTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `search field shows its placeholder and reports typing`() {
        var typed = ""
        compose.setContent {
            MeridianTheme {
                MeridianSearchField(value = "", onValueChange = { typed = it }, placeholder = "Search files")
            }
        }

        compose.onNodeWithText("Search files").assertIsDisplayed()
        compose.onNodeWithText("Search files").performTextInput("holiday")
        assertEquals("holiday", typed)
    }

    @Test
    fun `filter chip reports selection`() {
        var clicks = 0
        compose.setContent {
            MeridianTheme { MeridianFilterChip("Video", selected = false, onClick = { clicks++ }) }
        }

        compose.onNodeWithText("Video").performClick()
        assertEquals(1, clicks)
    }

    @Test
    fun `filter chip renders selected and unselected in both modes`() {
        listOf(false, true).forEach { dark ->
            listOf(false, true).forEach { selected ->
                compose.setContent {
                    MeridianTheme(darkTheme = dark) {
                        MeridianFilterChip("Video", selected = selected, onClick = {})
                    }
                }
                compose.onNodeWithText("Video").assertIsDisplayed()
            }
        }
    }

    @Test
    fun `badge shows a positive count`() {
        compose.setContent { MeridianTheme { MeridianBadge(count = 7) } }
        compose.onNodeWithText("7").assertIsDisplayed()
    }

    @Test
    fun `badge is hidden at zero`() {
        compose.setContent { MeridianTheme { MeridianBadge(count = 0) } }
        compose.onNodeWithText("0").assertDoesNotExist()
    }

    @Test
    fun `badge caps very large counts`() {
        compose.setContent { MeridianTheme { MeridianBadge(count = 1234) } }
        compose.onNodeWithText("99+").assertIsDisplayed()
    }
}
