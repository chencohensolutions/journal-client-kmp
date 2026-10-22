package com.chencohensolution.journal.ui.library

import android.content.res.Configuration.UI_MODE_NIGHT_YES
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import com.chencohensolution.journal.connection.ServerHealth
import com.chencohensolution.journal.search.SearchError
import com.chencohensolution.journal.search.SearchState
import com.chencohensolution.journal.search.SearchStatus

// Search: the 🔍 beside the tabs, the field at the bottom, and the photos found one per row with when and where.

@PreviewTest
@Preview(name = "dark", device = PHONE, showBackground = true, uiMode = UI_MODE_NIGHT_YES)
@Preview(name = "light", device = PHONE, showBackground = true)
@Composable
fun SearchResults() = Screen {
    JournalHomeScreen(PreviewData.placesLibrary, PreviewData.placesStories, ServerHealth.OK, HomeActions(), search = PreviewData.searchFound, initialSearching = true)
}

@PreviewTest
@Preview(name = "dark", device = PHONE, showBackground = true, uiMode = UI_MODE_NIGHT_YES)
@Composable
fun SearchBeforeTyping() = Screen {
    JournalHomeScreen(PreviewData.placesLibrary, PreviewData.placesStories, ServerHealth.OK, HomeActions(), initialSearching = true)
}

@PreviewTest
@Preview(name = "dark", device = PHONE, showBackground = true, uiMode = UI_MODE_NIGHT_YES)
@Composable
fun SearchOffline() = Screen {
    JournalHomeScreen(
        PreviewData.placesLibrary, PreviewData.placesStories, ServerHealth.OFFLINE, HomeActions(),
        search = SearchState("snow in the mountains", SearchStatus.FAILED, error = SearchError.OFFLINE),
        initialSearching = true,
    )
}

@PreviewTest
@Preview(name = "dark", device = PHONE, showBackground = true, uiMode = UI_MODE_NIGHT_YES)
@Composable
fun SearchButtonBesideTabs() = Screen {
    JournalHomeScreen(PreviewData.placesLibrary, PreviewData.placesStories, ServerHealth.OK, HomeActions(), initialTab = HomeTab.EXPLORE)
}
