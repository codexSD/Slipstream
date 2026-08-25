package com.slipstream.meridian.component

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.slipstream.meridian.MeridianRadius
import com.slipstream.meridian.MeridianText
import com.slipstream.meridian.MeridianTheme

/** Outlined, `md` radius, Surface fill, muted leading search icon. */
@Composable
fun MeridianSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "Search",
) {
    val colors = MeridianTheme.colors

    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth(),
        singleLine = true,
        shape = RoundedCornerShape(MeridianRadius.md),
        textStyle = MeridianText.body,
        placeholder = { Text(text = placeholder, style = MeridianText.body, color = colors.inkMuted) },
        leadingIcon = {
            Icon(Icons.Filled.Search, contentDescription = null, tint = colors.inkMuted)
        },
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = colors.surface,
            unfocusedContainerColor = colors.surface,
            focusedBorderColor = colors.brand,
            unfocusedBorderColor = colors.stroke,
            focusedTextColor = colors.ink,
            unfocusedTextColor = colors.ink,
            cursorColor = colors.brand,
        ),
    )
}

@Preview(name = "Search field light")
@Composable
private fun MeridianSearchFieldLightPreview() {
    MeridianTheme(darkTheme = false) {
        MeridianSearchField(value = "", onValueChange = {}, placeholder = "Search files")
    }
}

@Preview(name = "Search field dark")
@Composable
private fun MeridianSearchFieldDarkPreview() {
    MeridianTheme(darkTheme = true) {
        MeridianSearchField(value = "holiday", onValueChange = {})
    }
}
