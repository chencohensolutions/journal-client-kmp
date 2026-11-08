package com.chencohensolution.journal.ui.library

import android.content.res.Configuration.UI_MODE_NIGHT_YES
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import com.chencohensolution.journal.connection.ServerHealth

// Tucked away (Nov 8, 2026): the picking bar a long press starts, and a day's "3 tucked away" pill, hidden and
// revealed, in English and Hebrew. See docs/screens/moments.md.

private val canTuck = TuckedActions(canTuck = true)

@Composable
private fun Tucking(content: @Composable () -> Unit) = Screen { CompositionLocalProvider(LocalTucked provides canTuck) { content() } }

@PreviewTest
@Preview(name = "one photo", device = PHONE, showBackground = true)
@Preview(name = "one photo-he", locale = "iw", device = PHONE, showBackground = true)
@Composable
fun PickingOnePhoto() = Tucking {
    JournalHomeScreen(
        PreviewData.library, PreviewData.feedStories, ServerHealth.OK, HomeActions(),
        initialTab = HomeTab.MOMENTS,
        initialSelection = setOf(PreviewData.croppedPhoto.id),
    )
}

@PreviewTest
@Preview(name = "a set and a photo", device = PHONE, showBackground = true, uiMode = UI_MODE_NIGHT_YES)
@Preview(name = "a set and a photo-he", locale = "iw", device = PHONE, showBackground = true, uiMode = UI_MODE_NIGHT_YES)
@Composable
fun PickingMany() = Tucking {
    JournalHomeScreen(
        PreviewData.library, PreviewData.feedStories, ServerHealth.OK, HomeActions(),
        initialTab = HomeTab.MOMENTS,
        initialSelection = setOf(PreviewData.notedPhoto.id, PreviewData.croppedPhoto.id),
    )
}

@PreviewTest
@Preview(name = "hidden", device = PHONE, showBackground = true)
@Preview(name = "hidden-he", locale = "iw", device = PHONE, showBackground = true)
@Composable
fun TuckedDayHidden() = Tucking {
    JournalHomeScreen(PreviewData.library, PreviewData.tuckedStories(revealed = false), ServerHealth.OK, HomeActions(), initialTab = HomeTab.MOMENTS)
}

@PreviewTest
@Preview(name = "revealed", device = "spec:width=390dp,height=4600dp,dpi=420", showBackground = true)
@Preview(name = "revealed-he", locale = "iw", device = "spec:width=390dp,height=4600dp,dpi=420", showBackground = true)
@Composable
fun TuckedDayRevealed() = Tucking {
    JournalHomeScreen(PreviewData.library, PreviewData.tuckedStories(revealed = true), ServerHealth.OK, HomeActions(), initialTab = HomeTab.MOMENTS)
}

@PreviewTest
@Preview(name = "hidden", device = "spec:width=390dp,height=1800dp,dpi=420", showBackground = true, uiMode = UI_MODE_NIGHT_YES)
@Preview(name = "hidden-he", locale = "iw", device = "spec:width=390dp,height=1800dp,dpi=420", showBackground = true, uiMode = UI_MODE_NIGHT_YES)
@Composable
fun TuckedStoryline() = Tucking {
    JournalHomeScreen(PreviewData.library, PreviewData.tuckedStories(revealed = false), ServerHealth.OK, HomeActions(), initialTab = HomeTab.STORYLINE)
}
