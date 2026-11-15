package com.chencohensolution.journal.ui.library

import android.content.res.Configuration.UI_MODE_NIGHT_YES
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import com.chencohensolution.journal.connection.ServerHealth
import com.chencohensolution.journal.library.LibraryState
import com.chencohensolution.journal.library.PhotoAccess
import com.chencohensolution.journal.stories.FeedBlock
import com.chencohensolution.journal.stories.FeedEntry
import com.chencohensolution.journal.ui.JournalTheme

// Screens of the UX consistency review (Nov 2026) that had no preview yet: photo access, an empty Moments tab, Private
// settings, Models on this phone, the Make a moment sheet, the viewer's details and Copies in light.

@PreviewTest
@Preview(name = "light", device = PHONE, showBackground = true)
@Preview(name = "light-he", locale = "iw", device = PHONE, showBackground = true)
@Composable
fun PhotoAccess() = JournalTheme { PhotoAccessScreen(onAllow = {}, onNotNow = {}) }

@PreviewTest
@Preview(name = "light", device = PHONE, showBackground = true)
@Composable
fun MomentsEmpty() = Screen {
    JournalHomeScreen(LibraryState(access = PhotoAccess.FULL, complete = true), PreviewData.stories, ServerHealth.OK, HomeActions(), initialTab = HomeTab.MOMENTS)
}

@PreviewTest
@Preview(name = "light", device = "spec:width=390dp,height=1500dp,dpi=420", showBackground = true)
@Preview(name = "dark", device = "spec:width=390dp,height=1500dp,dpi=420", showBackground = true, uiMode = UI_MODE_NIGHT_YES)
@Composable
fun PrivateSettings() = Screen { PrivateSettingsLab() }

@PreviewTest
@Preview(name = "light", device = PHONE, showBackground = true)
@Composable
fun Models() = Screen { ModelsScreen(ModelsLab.usual, ModelsActions()) }

@PreviewTest
@Preview(name = "light", device = PHONE, showBackground = true)
@Composable
fun MakeMoment() = Screen {
    JournalHomeScreen(PreviewData.library, PreviewData.feedStories, ServerHealth.OK, HomeActions(), initialTab = HomeTab.MOMENTS)
    MakeMomentSheet(firstSet(), onMake = { _, _, _ -> }, onDismiss = {})
}

@PreviewTest
@Preview(name = "dark", device = "spec:width=390dp,height=1400dp,dpi=420", showBackground = true, uiMode = UI_MODE_NIGHT_YES)
@Composable
fun ViewerDetails() = Screen {
    LabInfoBackdrop { modifier ->
        InfoPanel(
            labInfoItem,
            note = labInfoNote,
            onAddTag = {},
            onRemoveTag = {},
            setInStoryline = {},
            location = labInfoLocation,
            setPrivate = { _, _ -> true },
            openMap = {},
            modifier = modifier,
        )
    }
}

@PreviewTest
@Preview(name = "light", device = "spec:width=390dp,height=1900dp,dpi=420", showBackground = true)
@Composable
fun CopiesLight() = Screen { CopiesScreen(labCopies, CopiesActions(), back = {}) }

/** The first set of the preview feed's photos. */
internal fun firstSet() = PreviewData.feedStories.feed.entries.asSequence()
    .flatMap { (it as FeedEntry.Section).section.moments }
    .flatMap { it.blocks }
    .first { it is FeedBlock.Similar }.items
