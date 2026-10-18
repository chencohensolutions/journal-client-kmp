package com.chencohensolution.journal.ui.library

import android.content.res.Configuration.UI_MODE_NIGHT_YES
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import com.chencohensolution.journal.connection.ServerHealth
import com.chencohensolution.journal.library.PhotoAccess

// The Journal home screen with made-up data (PreviewData). Render with ./gradlew :androidApp:updateDebugScreenshotTest;
// the images land in androidApp/src/screenshotTestDebug/reference/. See docs/previews.md.

@PreviewTest
@Preview(name = "light", device = PHONE, showBackground = true)
@Preview(name = "dark", device = PHONE, showBackground = true, uiMode = UI_MODE_NIGHT_YES)
@Composable
fun StoriesTab() = Screen {
    JournalHomeScreen(PreviewData.library, PreviewData.stories, ServerHealth.OK, HomeActions())
}

@PreviewTest
@Preview(name = "light", device = PHONE, showBackground = true)
@Preview(name = "dark", device = PHONE, showBackground = true, uiMode = UI_MODE_NIGHT_YES)
@Composable
fun MomentsTab() = Screen {
    JournalHomeScreen(PreviewData.library, PreviewData.feedStories, ServerHealth.OK, HomeActions(), initialTab = HomeTab.MOMENTS)
}

@PreviewTest
@Preview(name = "whole scroll", device = "spec:width=390dp,height=4400dp,dpi=420", showBackground = true)
@Composable
fun MomentsTabLong() = Screen {
    JournalHomeScreen(PreviewData.library, PreviewData.feedStories, ServerHealth.OK, HomeActions(), initialTab = HomeTab.MOMENTS)
}

@PreviewTest
@Preview(name = "light", device = PHONE, showBackground = true)
@Preview(name = "dark", device = PHONE, showBackground = true, uiMode = UI_MODE_NIGHT_YES)
@Composable
fun MomentsItemMenu() = Screen {
    JournalHomeScreen(
        PreviewData.library,
        PreviewData.feedStories,
        ServerHealth.OK,
        HomeActions(),
        initialTab = HomeTab.MOMENTS,
        initialMenuItem = PreviewData.notedPhoto.id,
    )
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

@PreviewTest
@Preview(name = "light", device = PHONE, showBackground = true)
@Composable
fun MomentsSelecting() = Screen {
    JournalHomeScreen(
        PreviewData.library,
        PreviewData.feedStories,
        ServerHealth.OK,
        HomeActions(),
        initialTab = HomeTab.MOMENTS,
        initialSelection = setOf(PreviewData.notedPhoto.id),
    )
}
