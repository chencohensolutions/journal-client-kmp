package com.chencohensolution.journal.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import com.chencohensolution.journal.stories.TimelineEntry

// Story cards on the journal: a titled story, an untitled one, a two-photo one. See docs/previews.md.

@PreviewTest
@Preview(name = "all stories", device = "spec:width=411dp,height=2600dp", showBackground = true)
@Composable
fun StoryCards() = Screen {
    Column(Modifier.background(LibraryColors.Background)) {
        PreviewData.moreStories.timeline.filterIsInstance<TimelineEntry.StoryEntry>().forEach {
            StoryCard(cardState(it.story), onOpen = {})
        }
    }
}
