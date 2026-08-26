package com.slipstream.app.ui.clipboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.slipstream.app.peer.PeerController
import com.slipstream.meridian.MeridianSpacing
import com.slipstream.meridian.MeridianText
import com.slipstream.meridian.MeridianTheme
import com.slipstream.meridian.component.MeridianPrimaryButton
import kotlinx.coroutines.launch

/**
 * I2: Home's "Send clipboard" tile. A minimal flow — a text field and a send button calling
 * [PeerController.sendClipboard] — modelled on Settings' plain-`Button`-plus-`scope.launch`
 * pattern for one-shot [PeerController] calls, since there's no dedicated ViewModel needed for a
 * single fire-and-forget action with no ongoing state to own.
 */
@Composable
fun ClipboardScreen(
    peerController: PeerController,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    var sending by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(MeridianSpacing.md),
        verticalArrangement = Arrangement.spacedBy(MeridianSpacing.md),
    ) {
        Text(text = "Send clipboard", style = MeridianText.screenTitle)

        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            modifier = Modifier
                .fillMaxWidth()
                .testTag("clipboard-text-field"),
            label = { Text("Text to send") },
        )

        message?.let {
            Text(
                text = it,
                style = MeridianText.body,
                color = MeridianTheme.colors.critical,
                modifier = Modifier.testTag("clipboard-message"),
            )
        }

        MeridianPrimaryButton(
            label = if (sending) "Sending…" else "Send",
            enabled = text.isNotBlank() && !sending,
            fullWidth = true,
            onClick = {
                scope.launch {
                    sending = true
                    peerController.sendClipboard(text).fold(
                        onSuccess = {
                            message = null
                            text = ""
                        },
                        onFailure = { error -> message = error.message ?: "Couldn't send clipboard text." },
                    )
                    sending = false
                }
            },
            modifier = Modifier.testTag("clipboard-send"),
        )
    }
}
