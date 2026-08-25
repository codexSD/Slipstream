package com.slipstream.meridian.component

import androidx.compose.material3.Text
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
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
class MeridianStateViewTest {

    @get:Rule
    val compose = createComposeRule()

    private fun show(state: MeridianUiState) {
        compose.setContent {
            MeridianTheme { MeridianStateView(state) { Text("The real content") } }
        }
    }

    @Test
    fun `content state shows the content`() {
        show(MeridianUiState.Content)
        compose.onNodeWithText("The real content").assertIsDisplayed()
    }

    @Test
    fun `loading state hides the content and shows a spinner`() {
        show(MeridianUiState.Loading)

        compose.onNodeWithText("The real content").assertDoesNotExist()
        compose.onNodeWithTag("meridian-loading").assertIsDisplayed()
    }

    @Test
    fun `empty state shows its message instead of the content`() {
        show(MeridianUiState.Empty("Nothing here yet. Send a file to get started."))

        compose.onNodeWithText("The real content").assertDoesNotExist()
        compose.onNodeWithText("Nothing here yet. Send a file to get started.").assertIsDisplayed()
    }

    @Test
    fun `empty state can offer an action`() {
        var clicked = false
        compose.setContent {
            MeridianTheme {
                MeridianStateView(
                    MeridianUiState.Empty("No transfers yet.", "Send a file") { clicked = true },
                ) { Text("The real content") }
            }
        }

        compose.onNodeWithText("Send a file").performClick()
        assertEquals(true, clicked)
    }

    @Test
    fun `error state shows the message and a retry`() {
        var retried = 0
        compose.setContent {
            MeridianTheme {
                MeridianStateView(
                    MeridianUiState.Error("Phone not on this network.", onRetry = { retried++ }),
                ) { Text("The real content") }
            }
        }

        compose.onNodeWithText("Phone not on this network.").assertIsDisplayed()
        compose.onNodeWithText("Retry").performClick()
        assertEquals(1, retried)
    }

    @Test
    fun `error state omits retry when no handler is given`() {
        show(MeridianUiState.Error("Something went wrong."))
        compose.onNodeWithText("Retry").assertDoesNotExist()
    }

    @Test
    fun `every state renders in dark mode`() {
        listOf(
            MeridianUiState.Loading,
            MeridianUiState.Content,
            MeridianUiState.Empty("Empty"),
            MeridianUiState.Error("Error"),
        ).forEach { state ->
            compose.setContent {
                MeridianTheme(darkTheme = true) { MeridianStateView(state) { Text("Content") } }
            }
        }
    }
}
