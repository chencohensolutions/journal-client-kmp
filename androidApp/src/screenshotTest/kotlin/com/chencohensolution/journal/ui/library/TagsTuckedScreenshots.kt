package com.chencohensolution.journal.ui.library

import android.content.res.Configuration.UI_MODE_NIGHT_YES
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import com.chencohensolution.journal.connection.ServerHealth
import com.chencohensolution.journal.stories.StoriesState

// Tagging several photos, tucked-away tags and highlights that show through (Nov 19, 2026): the quick tags row, the
// tag sheet for several photos, a tag's ⋯ sheet, a day's "3 Dog" pill beside "1 tucked away", Dog revealed, and a
// tucked tag's page, in light and dark. See docs/screens/tags.md.

private const val TALL = "spec:width=390dp,height=1400dp,dpi=420"

@Composable
private fun Tags(stories: StoriesState, content: @Composable () -> Unit) = Screen {
    CompositionLocalProvider(LocalTucked provides TuckedActions(canTuck = true, tagOf = stories::tuckedTagOf)) { content() }
}

@PreviewTest
@Preview(name = "light", device = PHONE, showBackground = true)
@Preview(name = "dark", device = PHONE, showBackground = true, uiMode = UI_MODE_NIGHT_YES)
@Preview(name = "light-he", locale = "iw", device = PHONE, showBackground = true)
@Composable
fun QuickTagsRow() {
    val stories = PreviewData.tagStories()
    Tags(stories) {
        JournalHomeScreen(
            PreviewData.library, stories, ServerHealth.OK, HomeActions(),
            initialTab = HomeTab.MOMENTS,
            initialSelection = setOf(PreviewData.notedPhoto.id, PreviewData.croppedPhoto.id),
        )
    }
}

@PreviewTest
@Preview(name = "light", device = PHONE, showBackground = true)
@Preview(name = "dark", device = PHONE, showBackground = true, uiMode = UI_MODE_NIGHT_YES)
@Preview(name = "light-he", locale = "iw", device = PHONE, showBackground = true)
@Composable
fun TagSheetSeveral() {
    val stories = PreviewData.tagStories()
    Tags(stories) {
        TagSheet(PreviewData.taggingPicks.map { it.id }, stories, onToggle = { _, _ -> }, onCreate = {}, onDismiss = {})
    }
}

@PreviewTest
@Preview(name = "light", device = PHONE, showBackground = true)
@Preview(name = "dark", device = PHONE, showBackground = true, uiMode = UI_MODE_NIGHT_YES)
@Composable
fun TagsWithTucked() {
    val stories = PreviewData.tagStories()
    Tags(stories) { TagsScreen(stories, onOpenTag = {}, onBack = {}) }
}

@PreviewTest
@Preview(name = "tuck", device = PHONE, showBackground = true)
@Preview(name = "tuck-dark", device = PHONE, showBackground = true, uiMode = UI_MODE_NIGHT_YES)
@Preview(name = "tuck-he", locale = "iw", device = PHONE, showBackground = true)
@Composable
fun TagMenu() {
    val stories = PreviewData.tagStories()
    Tags(stories) { TagsScreen(stories, onOpenTag = {}, onBack = {}, initialMenu = "Jaffa") }
}

@PreviewTest
@Preview(name = "bring back", device = PHONE, showBackground = true)
@Preview(name = "bring back-dark", device = PHONE, showBackground = true, uiMode = UI_MODE_NIGHT_YES)
@Composable
fun TagMenuTucked() {
    val stories = PreviewData.tagStories()
    Tags(stories) { TagsScreen(stories, onOpenTag = {}, onBack = {}, initialMenu = "Dog") }
}

@PreviewTest
@Preview(name = "light", device = TALL, showBackground = true)
@Preview(name = "dark", device = TALL, showBackground = true, uiMode = UI_MODE_NIGHT_YES)
@Preview(name = "light-he", locale = "iw", device = TALL, showBackground = true)
@Composable
fun DayWithTuckedTag() {
    val stories = PreviewData.tagStories()
    Tags(stories) { JournalHomeScreen(PreviewData.library, stories, ServerHealth.OK, HomeActions(), initialTab = HomeTab.MOMENTS) }
}

@PreviewTest
@Preview(name = "light", device = TALL, showBackground = true)
@Preview(name = "dark", device = TALL, showBackground = true, uiMode = UI_MODE_NIGHT_YES)
@Composable
fun DayWithDogRevealed() {
    val stories = PreviewData.tagStories(revealed = true)
    Tags(stories) { JournalHomeScreen(PreviewData.library, stories, ServerHealth.OK, HomeActions(), initialTab = HomeTab.MOMENTS) }
}

@PreviewTest
@Preview(name = "light", device = PHONE, showBackground = true)
@Preview(name = "dark", device = PHONE, showBackground = true, uiMode = UI_MODE_NIGHT_YES)
@Preview(name = "light-he", locale = "iw", device = PHONE, showBackground = true)
@Composable
fun TuckedTagPage() {
    val stories = PreviewData.dogPage()
    Tags(stories) { TagScreen("Dog", stories, onOpenItem = {}, onBack = {}) }
}
