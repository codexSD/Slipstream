package com.slipstream.meridian

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.slipstream.meridian.gallery.MeridianGallery

/**
 * Baselines for the whole system in both modes. A token change that alters any
 * component becomes a reviewable image diff rather than something noticed on a
 * device three weeks later.
 */
@Preview(name = "Gallery light", showBackground = true, heightDp = 2400, widthDp = 400)
@Composable
fun GalleryLightScreenshot() {
    MeridianTheme(darkTheme = false) { MeridianGallery() }
}

@Preview(name = "Gallery dark", showBackground = true, heightDp = 2400, widthDp = 400)
@Composable
fun GalleryDarkScreenshot() {
    MeridianTheme(darkTheme = true) { MeridianGallery() }
}
