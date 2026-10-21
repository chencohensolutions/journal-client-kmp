package com.chencohensolution.journal.ui.library

import android.content.res.Configuration.UI_MODE_NIGHT_YES
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.android.tools.screenshot.PreviewTest
import com.chencohensolution.journal.connection.ServerHealth
import com.chencohensolution.journal.library.MediaSource
import com.chencohensolution.journal.settings.SettingsState
import com.chencohensolution.journal.settings.ShownSources
import com.chencohensolution.journal.stories.FeedReveal

// Hiding WhatsApp, screenshots and other sources everywhere (Settings › Show in the app): what Moments says it
// leaves out, a day's hidden photos shown in a band, Settings, and Explore's line, year counts and "Showing" sheet.

@PreviewTest
@Preview(name = "light", device = PHONE, showBackground = true)
@Preview(name = "dark", device = PHONE, showBackground = true, uiMode = UI_MODE_NIGHT_YES)
@Composable
fun MomentsHiddenChips() = Screen {
    JournalHomeScreen(PreviewData.library, PreviewData.hiddenStories, ServerHealth.OK, HomeActions(), initialTab = HomeTab.MOMENTS)
}

@PreviewTest
@Preview(name = "light", device = PHONE, showBackground = true)
@Preview(name = "whole scroll", device = "spec:width=390dp,height=4400dp,dpi=420", showBackground = true, uiMode = UI_MODE_NIGHT_YES)
@Composable
fun MomentsRevealedBand() = Screen {
    JournalHomeScreen(
        PreviewData.library,
        PreviewData.revealedStories,
        ServerHealth.OK,
        HomeActions(),
        initialTab = HomeTab.MOMENTS,
        initialReveal = FeedReveal(PreviewData.todayKey, MediaSource.WHATSAPP),
    )
}

@PreviewTest
@Preview(name = "light", device = PHONE, showBackground = true)
@Preview(name = "dark", device = PHONE, showBackground = true, uiMode = UI_MODE_NIGHT_YES)
@Composable
fun SettingsShownSources() = Screen {
    SettingsScreen(
        SettingsState(shownSources = ShownSources()),
        SettingsActions(),
        sourceCounts = PreviewData.hiddenFeed.counts,
    )
}

@PreviewTest
@Preview(name = "light", device = PHONE, showBackground = true)
@Preview(name = "whole scroll", device = "spec:width=390dp,height=1300dp,dpi=420", showBackground = true, uiMode = UI_MODE_NIGHT_YES)
@Composable
fun ExploreYearsHidden() = Screen {
    JournalHomeScreen(
        PreviewData.exploreLibrary,
        PreviewData.exploreStories,
        ServerHealth.OK,
        HomeActions(),
        initialTab = HomeTab.EXPLORE,
        initialExploreView = ExploreView.YEARS,
    )
}

@PreviewTest
@Preview(name = "light", device = PHONE, showBackground = true)
@Preview(name = "dark", device = PHONE, showBackground = true, uiMode = UI_MODE_NIGHT_YES)
@Composable
fun ExploreShowingSheet() = Screen {
    Box(Modifier.fillMaxSize().background(LibraryColors.Background)) {
        JournalHomeScreen(
            PreviewData.exploreLibrary,
            PreviewData.exploreStories,
            ServerHealth.OK,
            HomeActions(),
            initialTab = HomeTab.EXPLORE,
            initialExploreView = ExploreView.YEARS,
        )
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.4f)))
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(LibraryColors.Background, RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
                .padding(top = 28.dp),
        ) {
            ShowingSheetContent(
                ShownSources.DEFAULT_SOURCES,
                mapOf(MediaSource.CAMERA to 922, MediaSource.WHATSAPP to 31, MediaSource.OTHER to 0),
                onToggle = {},
            )
        }
    }
}
