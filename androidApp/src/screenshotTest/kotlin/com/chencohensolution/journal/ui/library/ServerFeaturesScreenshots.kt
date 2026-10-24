package com.chencohensolution.journal.ui.library

import android.content.res.Configuration.UI_MODE_NIGHT_YES
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import androidx.compose.foundation.background
import com.chencohensolution.journal.connection.ServerHealth
import com.chencohensolution.journal.settings.SettingsState

// Mockups of the server's new features in the apps (docs/plans/server-features.md): Explore's People, a person's
// page, "Who's this?", the viewer's findings, and Settings › On this phone. Made-up data in PeopleLabData.

@PreviewTest
@Preview(name = "light", device = PHONE, showBackground = true)
@Preview(name = "dark", device = PHONE, showBackground = true, uiMode = UI_MODE_NIGHT_YES)
@Composable
fun ExplorePeople() = Screen {
    JournalHomeScreen(
        PreviewData.placesLibrary,
        PreviewData.placesStories,
        ServerHealth.OK,
        HomeActions(),
        initialTab = HomeTab.EXPLORE,
        initialExploreView = ExploreView.PEOPLE,
        people = PeopleLabData.people,
    )
}

// While the server still reads files for faces, and before anyone is named.

@PreviewTest
@Preview(name = "reading", device = PHONE, showBackground = true, uiMode = UI_MODE_NIGHT_YES)
@Composable
fun ExplorePeopleReading() = Screen {
    JournalHomeScreen(
        PreviewData.placesLibrary,
        PreviewData.placesStories,
        ServerHealth.OK,
        HomeActions(),
        initialTab = HomeTab.EXPLORE,
        initialExploreView = ExploreView.PEOPLE,
        people = PeopleLabData.peopleReading,
    )
}

@PreviewTest
@Preview(name = "unnamed", device = PHONE, showBackground = true, uiMode = UI_MODE_NIGHT_YES)
@Composable
fun ExplorePeopleUnnamed() = Screen {
    JournalHomeScreen(
        PreviewData.placesLibrary,
        PreviewData.placesStories,
        ServerHealth.OK,
        HomeActions(),
        initialTab = HomeTab.EXPLORE,
        initialExploreView = ExploreView.PEOPLE,
        people = PeopleLabData.peopleUnnamed,
    )
}

@PreviewTest
@Preview(name = "light", device = PHONE, showBackground = true)
@Preview(name = "dark", device = PHONE, showBackground = true, uiMode = UI_MODE_NIGHT_YES)
@Composable
fun Person() = Screen { PersonScreen(PeopleLabData.momPage, PreviewData.feedStories, PeopleLabData.others, PersonActions()) }

// Searching within the page, sorted best first; Merge with…; Forget; "Not Mom" on a photo. The ⋯ menu is a popup,
// which a preview doesn't draw: see it in the UI lab.

@PreviewTest
@Preview(name = "searched", device = PHONE, showBackground = true, uiMode = UI_MODE_NIGHT_YES)
@Composable
fun PersonSearched() = Screen { PersonScreen(PeopleLabData.momSearched, PreviewData.feedStories, PeopleLabData.others, PersonActions()) }

@PreviewTest
@Preview(name = "merge", device = PHONE, showBackground = true, uiMode = UI_MODE_NIGHT_YES)
@Composable
fun PersonMerge() = Screen { PersonScreen(PeopleLabData.momPage, PreviewData.feedStories, PeopleLabData.others, PersonActions(), initialSheet = PersonSheet.Merge) }

@PreviewTest
@Preview(name = "forget", device = PHONE, showBackground = true, uiMode = UI_MODE_NIGHT_YES)
@Composable
fun PersonForget() = Screen { PersonScreen(PeopleLabData.momPage, PreviewData.feedStories, PeopleLabData.others, PersonActions(), initialSheet = PersonSheet.Forget) }

@PreviewTest
@Preview(name = "not-them", device = PHONE, showBackground = true, uiMode = UI_MODE_NIGHT_YES)
@Composable
fun PersonNotThem() = Screen { PersonScreen(PeopleLabData.momPage, PreviewData.feedStories, PeopleLabData.others, PersonActions(), initialSheet = PersonSheet.NotThem(PeopleLabData.notMomPhoto)) }

@PreviewTest
@Preview(name = "light", device = PHONE, showBackground = true)
@Preview(name = "dark", device = PHONE, showBackground = true, uiMode = UI_MODE_NIGHT_YES)
@Composable
fun GroupName() = Screen { GroupScreen(PeopleLabData.groupPage, GroupActions()) }

@PreviewTest
@Preview(name = "typing", device = PHONE, showBackground = true, uiMode = UI_MODE_NIGHT_YES)
@Composable
fun GroupNameTyping() = Screen { GroupScreen(PeopleLabData.groupTyping, GroupActions()) }

// The viewer's info panel with people, text and similar photos, over the photo, in light and dark as the panel is.

@PreviewTest
@Preview(name = "light", device = PHONE, showBackground = true)
@Preview(name = "dark", device = PHONE, showBackground = true, uiMode = UI_MODE_NIGHT_YES)
@Composable
fun ViewerFindings() = Screen {
    LabInfoBackdrop { modifier -> LabFindingsPanel(modifier) }
}

@PreviewTest
@Preview(name = "light", device = PHONE, showBackground = true)
@Preview(name = "dark", device = PHONE, showBackground = true, uiMode = UI_MODE_NIGHT_YES)
@Composable
fun PhoneSearch() = Screen { SearchSettingsScreen(100, PeopleLabData.indexingIdle, PeopleLabData.phoneSearch, SearchSettingsActions()) }

@PreviewTest
@Preview(name = "downloading", device = PHONE, showBackground = true, uiMode = UI_MODE_NIGHT_YES)
@Composable
fun PhoneSearchDownloading() = Screen { SearchSettingsScreen(100, PeopleLabData.indexingIdle, PeopleLabData.phoneSearchDownloading, SearchSettingsActions()) }

@PreviewTest
@Preview(name = "waiting", device = PHONE, showBackground = true, uiMode = UI_MODE_NIGHT_YES)
@Composable
fun PhoneSearchWaiting() = Screen { SearchSettingsScreen(100, PeopleLabData.indexingIdle, PeopleLabData.phoneSearchWaiting, SearchSettingsActions()) }

@PreviewTest
@Preview(name = "off", device = PHONE, showBackground = true, uiMode = UI_MODE_NIGHT_YES)
@Composable
fun PhoneSearchOff() = Screen { SearchSettingsScreen(100, PeopleLabData.indexingIdle, PeopleLabData.phoneSearchOff, SearchSettingsActions()) }

// Settings, scrolled to Search: its row, which says how the server's reading goes. Tall to reach it.

@PreviewTest
@Preview(name = "dark", device = "spec:width=390dp,height=2200dp,dpi=420", showBackground = true, uiMode = UI_MODE_NIGHT_YES)
@Composable
fun SettingsPhoneSearch() = Screen {
    SettingsScreen(SettingsState(), SettingsActions(), phoneSearch = PeopleLabData.phoneSearch, indexing = PeopleLabData.indexingRunning)
}

// Settings › Search: the server's reading of the user's photos, photos per page, and On this phone.

@PreviewTest
@Preview(name = "running", device = PHONE, showBackground = true, uiMode = UI_MODE_NIGHT_YES)
@Composable
fun SearchSettingsRunning() = Screen { SearchSettingsScreen(100, PeopleLabData.indexingRunning, PeopleLabData.phoneSearch, SearchSettingsActions()) }

@PreviewTest
@Preview(name = "nightly", device = PHONE, showBackground = true, uiMode = UI_MODE_NIGHT_YES)
@Composable
fun SearchSettingsNightly() = Screen { SearchSettingsScreen(100, PeopleLabData.indexingNightly, PeopleLabData.phoneSearch, SearchSettingsActions()) }

@PreviewTest
@Preview(name = "paused", device = PHONE, showBackground = true, uiMode = UI_MODE_NIGHT_YES)
@Composable
fun SearchSettingsPaused() = Screen { SearchSettingsScreen(100, PeopleLabData.indexingPaused, PeopleLabData.phoneSearch, SearchSettingsActions()) }

@PreviewTest
@Preview(name = "queued", device = PHONE, showBackground = true, uiMode = UI_MODE_NIGHT_YES)
@Composable
fun SearchSettingsQueued() = Screen { SearchSettingsScreen(100, PeopleLabData.indexingQueued, null, SearchSettingsActions()) }

@PreviewTest
@Preview(name = "idle", device = PHONE, showBackground = true, uiMode = UI_MODE_NIGHT_YES)
@Preview(name = "idle light", device = PHONE, showBackground = true)
@Composable
fun SearchSettingsIdle() = Screen { SearchSettingsScreen(100, PeopleLabData.indexingIdle, PeopleLabData.phoneSearch, SearchSettingsActions()) }

// Correcting faces: a face tapped in a photo's details (Mom's, then "Who is it?"), and Edit faces with two marked.

@PreviewTest
@Preview(name = "named", device = PHONE, showBackground = true, uiMode = UI_MODE_NIGHT_YES)
@Composable
fun FaceSheetNamed() = Screen {
    PageSheet({}) { FaceSheet(PeopleLabData.findings.people[0], PeopleLabData.people.list.people, FaceActions(), onDone = {}) }
}

@PreviewTest
@Preview(name = "who", device = PHONE, showBackground = true, uiMode = UI_MODE_NIGHT_YES)
@Composable
fun FaceSheetWho() = Screen {
    PageSheet({}) { FaceSheet(PeopleLabData.findings.people[0], PeopleLabData.people.list.people, FaceActions(), onDone = {}, initialAsking = true) }
}

@PreviewTest
@Preview(name = "dark", device = PHONE, showBackground = true, uiMode = UI_MODE_NIGHT_YES)
@Composable
fun EditFaces() = Screen {
    val faces = PeopleLabData.momPage.itemFaces.map { it.second }
    androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.background(LibraryColors.Background)) {
        EditFacesGrid(PeopleLabData.momPage, setOf(faces[1].id, faces[4].id)) {}
    }
}
