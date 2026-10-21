package com.chencohensolution.journal.ui.library

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest

// The viewer with made-up data (PreviewData): a photo with its portrait crop, and one without. See docs/previews.md.

@PreviewTest
@Preview(name = "portrait crop", device = PHONE, showBackground = true)
@Composable
fun ViewerCropped() = Screen {
    LibraryTheme(dark = true) {
        ViewerScreen(listOf(PreviewData.tripPhoto), PreviewData.tripPhoto.id, PreviewData.feedStories, null, null, ViewerActions(), chromeAtStart = true)
    }
}

@PreviewTest
@Preview(name = "not cropped", device = PHONE, showBackground = true)
@Composable
fun ViewerWhole() = Screen {
    LibraryTheme(dark = true) {
        ViewerScreen(listOf(PreviewData.notedPhoto), PreviewData.notedPhoto.id, PreviewData.feedStories, null, null, ViewerActions(), chromeAtStart = true)
    }
}
