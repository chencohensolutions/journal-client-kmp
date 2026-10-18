package com.chencohensolution.journal.ui.library

import android.content.res.Configuration.UI_MODE_NIGHT_YES
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest

// A group's page: cover, title, text, then its photos and a subgroup as tiles. See docs/previews.md.

@PreviewTest
@Preview(name = "light", device = "spec:width=390dp,height=1200dp,dpi=420", showBackground = true)
@Preview(name = "dark", device = "spec:width=390dp,height=1200dp,dpi=420", showBackground = true, uiMode = UI_MODE_NIGHT_YES)
@Composable
fun GroupPage() = Screen {
    val story = PreviewData.seaside
    GroupScreen(story, story.group("g1"), GroupActions())
}
