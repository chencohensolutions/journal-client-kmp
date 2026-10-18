package com.chencohensolution.journal.ui.library

import android.content.res.Configuration.UI_MODE_NIGHT_YES
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import com.chencohensolution.journal.connection.ServerHealth
import com.chencohensolution.journal.library.PhotoAccess

// The Journal home screen with made-up data (PreviewData). Render with ./gradlew :androidApp:updateDebugScreenshotTest;
// the images land in androidApp/src/screenshotTestDebug/reference/. See docs/previews.md.

private const val PHONE = "spec:width=390dp,height=844dp,dpi=420"

@Composable
private fun Screen(content: @Composable () -> Unit) = MaterialTheme { LibraryTheme(content = content) }

@PreviewTest
@Preview(name = "light", device = PHONE, showBackground = true)
@Preview(name = "dark", device = PHONE, showBackground = true, uiMode = UI_MODE_NIGHT_YES)
@Composable
fun JournalTab() = Screen {
    JournalHomeScreen(PreviewData.library, PreviewData.stories, ServerHealth.OK, HomeActions())
}

@PreviewTest
@Preview(name = "light", device = PHONE, showBackground = true)
@Preview(name = "dark", device = PHONE, showBackground = true, uiMode = UI_MODE_NIGHT_YES)
@Composable
fun UnsortedTab() = Screen {
    JournalHomeScreen(PreviewData.library, PreviewData.stories, ServerHealth.OK, HomeActions(), initialTab = HomeTab.UNSORTED)
}

@PreviewTest
@Preview(name = "offline, limited access", device = PHONE, showBackground = true)
@Composable
fun OfflineLimited() = Screen {
    JournalHomeScreen(PreviewData.library.copy(access = PhotoAccess.LIMITED), PreviewData.stories, ServerHealth.OFFLINE, HomeActions())
}

@PreviewTest
@Preview(name = "not registered, no stories yet", device = PHONE, showBackground = true)
@Composable
fun RejectedNoStories() = Screen {
    JournalHomeScreen(PreviewData.library, PreviewData.noStories, ServerHealth.REJECTED, HomeActions())
}
