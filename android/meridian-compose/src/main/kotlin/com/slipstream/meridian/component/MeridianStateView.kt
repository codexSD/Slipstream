package com.slipstream.meridian.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import com.slipstream.meridian.MeridianSpacing
import com.slipstream.meridian.MeridianText
import com.slipstream.meridian.MeridianTheme

/**
 * The four mutually exclusive states every data-backed region has.
 *
 * Empty states invite action — "No data" is a floor, not a ceiling. Error messages
 * are direct and name the next step; they never apologise.
 */
sealed interface MeridianUiState {
    data object Loading : MeridianUiState

    data object Content : MeridianUiState

    data class Empty(
        val message: String,
        val actionLabel: String? = null,
        val onAction: (() -> Unit)? = null,
    ) : MeridianUiState

    data class Error(
        val message: String,
        val retryLabel: String = "Retry",
        val onRetry: (() -> Unit)? = null,
    ) : MeridianUiState
}

/**
 * One view driving all four states over the same bounds as the content it covers.
 * Using this instead of hand-toggled sibling views is what stops the "spinner and
 * empty text visible at once" class of bug.
 */
@Composable
fun MeridianStateView(
    state: MeridianUiState,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val colors = MeridianTheme.colors

    Box(modifier = modifier) {
        when (state) {
            MeridianUiState.Content -> content()

            MeridianUiState.Loading -> Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(
                    color = colors.brand,
                    modifier = Modifier.testTag("meridian-loading"),
                )
            }

            is MeridianUiState.Empty -> Message(
                modifier = Modifier,
                message = state.message,
                messageColor = colors.inkMuted,
                actionLabel = state.actionLabel,
                onAction = state.onAction,
            )

            is MeridianUiState.Error -> Message(
                modifier = Modifier,
                message = state.message,
                messageColor = colors.critical,
                actionLabel = if (state.onRetry != null) state.retryLabel else null,
                onAction = state.onRetry,
            )
        }
    }
}

@Composable
private fun Message(
    modifier: Modifier,
    message: String,
    messageColor: androidx.compose.ui.graphics.Color,
    actionLabel: String?,
    onAction: (() -> Unit)?,
) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(MeridianSpacing.sm),
            modifier = Modifier.padding(MeridianSpacing.xxl),
        ) {
            Text(
                text = message,
                style = MeridianText.body,
                color = messageColor,
                textAlign = TextAlign.Center,
            )

            if (actionLabel != null && onAction != null) {
                MeridianTextButton(label = actionLabel, onClick = onAction)
            }
        }
    }
}

@Preview(name = "Loading state light")
@Composable
private fun MeridianStateViewLoadingLightPreview() {
    MeridianTheme(darkTheme = false) {
        MeridianStateView(MeridianUiState.Loading) {}
    }
}

@Preview(name = "Loading state dark")
@Composable
private fun MeridianStateViewLoadingDarkPreview() {
    MeridianTheme(darkTheme = true) {
        MeridianStateView(MeridianUiState.Loading) {}
    }
}

@Preview(name = "Empty state light")
@Composable
private fun MeridianStateViewEmptyLightPreview() {
    MeridianTheme(darkTheme = false) {
        MeridianStateView(
            MeridianUiState.Empty("Nothing transferred yet. Pick a file to send.", "Send a file") {},
        ) {}
    }
}

@Preview(name = "Empty state dark")
@Composable
private fun MeridianStateViewEmptyDarkPreview() {
    MeridianTheme(darkTheme = true) {
        MeridianStateView(
            MeridianUiState.Empty("Nothing transferred yet. Pick a file to send.", "Send a file") {},
        ) {}
    }
}

@Preview(name = "Error state light")
@Composable
private fun MeridianStateViewErrorLightPreview() {
    MeridianTheme(darkTheme = false) {
        MeridianStateView(
            MeridianUiState.Error("Phone not on this network. Searching…", onRetry = {}),
        ) {}
    }
}

@Preview(name = "Error state dark")
@Composable
private fun MeridianStateViewErrorDarkPreview() {
    MeridianTheme(darkTheme = true) {
        MeridianStateView(
            MeridianUiState.Error("Phone not on this network. Searching…", onRetry = {}),
        ) {}
    }
}
