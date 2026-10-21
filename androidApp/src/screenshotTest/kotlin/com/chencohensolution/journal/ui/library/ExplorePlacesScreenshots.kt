package com.chencohensolution.journal.ui.library

import android.content.res.Configuration.UI_MODE_NIGHT_YES
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import com.chencohensolution.journal.connection.ServerHealth

// Explore › Places: the home town and the home country's towns in a row, then every other country as one row, and a
// country's page of towns.

@PreviewTest
@Preview(name = "dark", device = PHONE, showBackground = true, uiMode = UI_MODE_NIGHT_YES)
@Preview(name = "light", device = PHONE, showBackground = true)
@Preview(name = "whole scroll", device = "spec:width=390dp,height=1300dp,dpi=420", showBackground = true, uiMode = UI_MODE_NIGHT_YES)
@Composable
fun ExplorePlaces() = Screen {
    JournalHomeScreen(PreviewData.placesLibrary, PreviewData.placesStories, ServerHealth.OK, HomeActions(), initialTab = HomeTab.EXPLORE)
}

@PreviewTest
@Preview(name = "dark", device = PHONE, showBackground = true, uiMode = UI_MODE_NIGHT_YES)
@Composable
fun ExploreCountry() = Screen {
    CountryScreen("France", PreviewData.placesLibrary, PreviewData.placesStories, onOpenPlace = {}, onBack = {})
}

@PreviewTest
@Preview(name = "home country", device = PHONE, showBackground = true, uiMode = UI_MODE_NIGHT_YES)
@Composable
fun ExploreHomeCountry() = Screen {
    CountryScreen("Israel", PreviewData.placesLibrary, PreviewData.placesStories, onOpenPlace = {}, onBack = {})
}
