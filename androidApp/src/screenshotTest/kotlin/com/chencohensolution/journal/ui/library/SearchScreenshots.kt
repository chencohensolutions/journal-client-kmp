package com.chencohensolution.journal.ui.library

import android.content.res.Configuration.UI_MODE_NIGHT_YES
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
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
fun SearchOneYear() = Screen {
    JournalHomeScreen(PreviewData.placesLibrary, PreviewData.placesStories, ServerHealth.OK, HomeActions(), search = PreviewData.searchIn2025, initialSearching = true)
}

@PreviewTest
@Preview(name = "dark", device = PHONE, showBackground = true, uiMode = UI_MODE_NIGHT_YES)
@Preview(name = "light", device = PHONE, showBackground = true)
@Composable
fun SearchNewestFirst() = Screen {
    JournalHomeScreen(PreviewData.placesLibrary, PreviewData.placesStories, ServerHealth.OK, HomeActions(), search = PreviewData.searchNewest, initialSearching = true)
}

// The Sort sheet's inside, as it opens over the results: the orders, the slider and the weakest kept.
@PreviewTest
@Preview(name = "dark", device = PHONE, showBackground = true, uiMode = UI_MODE_NIGHT_YES)
@Preview(name = "hebrew", device = PHONE, showBackground = true, uiMode = UI_MODE_NIGHT_YES, locale = "iw")
@Composable
fun SearchSortSheet() = Screen {
    Box(Modifier.fillMaxSize().background(LibraryColors.Background).padding(top = 24.dp)) {
        SortSheetContent(PreviewData.searchNewest, SearchActions()) {}
    }
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
