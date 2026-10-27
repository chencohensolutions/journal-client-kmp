package com.chencohensolution.journal.ui.library

import androidx.compose.runtime.Composable
import com.chencohensolution.journal.connection.ServerHealth
import com.chencohensolution.journal.settings.SettingsState
import com.chencohensolution.journal.server.HiddenCollectionsState
import com.chencohensolution.journal.server.HiddenCollections
import com.chencohensolution.journal.stories.CropKind
import com.chencohensolution.journal.stories.PhotoCrops

// The UI lab's library (UiLabActivity): every screen, each with the app's current version first and then the
// suggestions for it. A suggestion is a composable in <Screen>Suggestions.kt next to this file, added to its
// screen's list after `current`; when one is chosen it moves to src/main and replaces the current version.

/** The screen as the app draws it now, always the first option. */
private fun current(content: @Composable () -> Unit) = LabOption("current", "Current", content = content)

/** A screen the app doesn't have yet, as proposed. */
private fun proposed(content: @Composable () -> Unit) = LabOption("proposed", "Proposed", content = content)

internal val labScreens: List<LabScreen> = listOf(
    LabScreen("storyline", "Storyline", listOf(
        current { JournalHomeScreen(PreviewData.library, PreviewData.feedStories, ServerHealth.OK, HomeActions()) },
    )),
    LabScreen("moments", "Moments", listOf(
        current {
            JournalHomeScreen(PreviewData.library, PreviewData.feedStories, ServerHealth.OK, HomeActions(), initialTab = HomeTab.MOMENTS)
        },
    )),
    LabScreen("private", "Private settings", listOf(
        current {
            val shots = listOf("Bumble" to 1458, "Chrome" to 310, "WhatsApp" to 96, "Instagram" to 88, "Maps" to 41, "Google Pay" to 12)
                .mapIndexed { i, (name, n) -> HiddenCollections.Collection(name, n, hidden = i == 0) }
            val collections = HiddenCollections(
                apps = listOf("WhatsApp" to 3412, "Telegram" to 640, "Signal" to 210, "Petcube" to 5)
                    .mapIndexed { i, (name, n) -> HiddenCollections.Collection(name, n, hidden = i == 0) },
                screenshotsWithoutApp = HiddenCollections.Collection("", 128, false),
                screenshotApps = shots,
                nudity = HiddenCollections.Nudity(true, 50, 61, 0),
                recordings = HiddenCollections.Collection("recordings", 1204, true),
                calls = HiddenCollections.Collection("calls", 6196, true),
                voiceMessages = HiddenCollections.Collection("voiceMessages", 14, true),
            )
            HiddenContent(
                androidx.compose.ui.res.stringResource(com.chencohensolution.journal.R.string.settings_hidden_title),
                HiddenCollectionsState.Ready(collections, "", shots, saving = false, notChanged = false),
                HiddenActions(retry = {}, set = { _, _ -> }, setQuery = {}),
                back = {},
            )
        },
    )),
    LabScreen("recording", "A sound recording's page", listOf(
        current {
            LibraryTheme(dark = true) {
                val moment = PreviewData.feedStories.feed.entries
                    .flatMap { (it as com.chencohensolution.journal.stories.FeedEntry.Section).section.moments }
                    .first { it.recording != null }
                AudioPage(AudioPageState(moment.recording!!, moment.place, moment.items.sortedBy { it.takenAtMillis }, null), AudioPageActions())
            }
        },
        LabOption("call", "A call", "Who it was with, Call and Edit contact") {
            LibraryTheme(dark = true) {
                AudioPage(AudioPageState(PreviewData.feedStories.sounds.getValue("lab-call-2").copy(number = "0521234567"), null, emptyList(), null), AudioPageActions())
            }
        },
    )),
    LabScreen("moments-years", "Moments over seven years", listOf(
        current {
            JournalHomeScreen(PreviewData.timelineLibrary, PreviewData.timelineStories, ServerHealth.OK, HomeActions(), initialTab = HomeTab.MOMENTS)
        },
    )),
    LabScreen("moments-hidden", "Moments, sources hidden", listOf(
        current {
            JournalHomeScreen(PreviewData.library, PreviewData.hiddenStories, ServerHealth.OK, HomeActions(), initialTab = HomeTab.MOMENTS)
        },
    )),
    LabScreen("explore", "Explore", listOf(
        current {
            JournalHomeScreen(PreviewData.placesLibrary, PreviewData.placesStories, ServerHealth.OK, HomeActions(), initialTab = HomeTab.EXPLORE)
        },
    )),
    LabScreen("explore-years", "Explore by years", listOf(
        current {
            JournalHomeScreen(
                PreviewData.exploreLibrary,
                PreviewData.exploreStories,
                ServerHealth.OK,
                HomeActions(),
                initialTab = HomeTab.EXPLORE,
                initialExploreView = ExploreView.YEARS,
            )
        },
    )),
    // Mockups of the server's new features (docs/plans/server-features.md): not in the app yet, so "proposed" is
    // their only option. Their data is in PeopleLabData.
    LabScreen("explore-people", "Explore by people", listOf(
        proposed {
            JournalHomeScreen(
                PreviewData.placesLibrary,
                PreviewData.placesStories,
                ServerHealth.OK,
                HomeActions(),
                initialTab = HomeTab.EXPLORE,
                initialExploreView = ExploreView.PEOPLE,
                people = PeopleLabData.people,
            )
        },
        LabOption("reading", "Still reading faces") {
            JournalHomeScreen(
                PreviewData.placesLibrary,
                PreviewData.placesStories,
                ServerHealth.OK,
                HomeActions(),
                initialTab = HomeTab.EXPLORE,
                initialExploreView = ExploreView.PEOPLE,
                people = PeopleLabData.peopleReading,
            )
        },
    )),
    LabScreen("person", "A person's page", listOf(
        proposed { PersonScreen(PeopleLabData.momPage, PreviewData.feedStories, PeopleLabData.others, PersonActions()) },
        LabOption("searched", "Searched, best first") { PersonScreen(PeopleLabData.momSearched, PreviewData.feedStories, PeopleLabData.others, PersonActions()) },
    )),
    LabScreen("group", "Who's this? (naming)", listOf(
        proposed { GroupScreen(PeopleLabData.groupPage, GroupActions()) },
        LabOption("typing", "Typing a name") { GroupScreen(PeopleLabData.groupTyping, GroupActions()) },
    )),
    LabScreen("phone-search", "On device search", listOf(
        proposed { SearchSettingsScreen(100, PeopleLabData.indexingIdle, PeopleLabData.phoneSearch, SearchSettingsActions()) },
        LabOption("downloading", "Downloading") { SearchSettingsScreen(100, PeopleLabData.indexingIdle, PeopleLabData.phoneSearchDownloading, SearchSettingsActions()) },
        LabOption("waiting", "Waiting for Wi-Fi") { SearchSettingsScreen(100, PeopleLabData.indexingIdle, PeopleLabData.phoneSearchWaiting, SearchSettingsActions()) },
        LabOption("off", "Never turned on") { SearchSettingsScreen(100, PeopleLabData.indexingIdle, PeopleLabData.phoneSearchOff, SearchSettingsActions()) },
    )),
    LabScreen("country", "Country", listOf(
        current { CountryScreen("France", PreviewData.placesLibrary, PreviewData.placesStories, onOpenPlace = {}, onBack = {}) },
    )),
    LabScreen("offline", "Offline", listOf(
        current { JournalHomeScreen(PreviewData.library, PreviewData.stories, ServerHealth.OFFLINE, HomeActions()) },
    )),
    LabScreen("viewer", "Viewer", listOf(
        current {
            LibraryTheme(dark = true) {
                ViewerScreen(
                    listOf(PreviewData.tripPhoto, PreviewData.notedPhoto),
                    PreviewData.tripPhoto.id,
                    PreviewData.feedStories,
                    ViewerActions(),
                    chromeAtStart = true,
                )
            }
        },
    )),
    LabScreen("viewer-info", "Viewer info panel", listOf(
        current {
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
        },
        LabOption("findings", "With people, text, similar", "Proposed: what the server found, above the map") {
            LabInfoBackdrop { modifier -> LabFindingsPanel(modifier) }
        },
    )),
    LabScreen("crop", "Crop", listOf(
        current {
            val crops = PreviewData.marketCrops
            CropScreen(PreviewData.croppedPhoto, CropKind.MOMENTS, crops.moments!!, PhotoCrops(moments = crops.moments), PreviewData.SCREEN_RATIO, CropActions())
        },
    )),
    LabScreen("settings", "Settings", listOf(
        current { SettingsScreen(SettingsState(), SettingsActions()) },
        LabOption("phone-search", "With On this phone", "Search's row with a phone index kept") {
            SettingsScreen(SettingsState(), SettingsActions(), phoneSearch = PeopleLabData.phoneSearch)
        },
    )),
    LabScreen("sources", "Settings › Show in the app: imported", listOf(
        current {
            val counts = mapOf(
                com.chencohensolution.journal.library.MediaSource.CAMERA to 39650,
                com.chencohensolution.journal.library.MediaSource.WHATSAPP to 10378,
                com.chencohensolution.journal.library.MediaSource.OTHER to 3948,
                com.chencohensolution.journal.library.MediaSource.SCREENSHOTS to 2268,
                com.chencohensolution.journal.library.MediaSource.folder("Petcube") to 5,
                com.chencohensolution.journal.library.MediaSource.imported(com.chencohensolution.journal.library.MediaSource.CAMERA) to 38410,
                com.chencohensolution.journal.library.MediaSource.imported(com.chencohensolution.journal.library.MediaSource.OTHER) to 3917,
                com.chencohensolution.journal.library.MediaSource.imported(com.chencohensolution.journal.library.MediaSource.SCREENSHOTS) to 540,
            )
            SettingsScreen(SettingsState(), SettingsActions(), page = SettingsPage.SHOWN, sourceCounts = counts)
        },
    )),
    // A page the app doesn't have yet (Settings › Library › Your cameras): its suggestions only.
    LabScreen("cameras", "Settings › Your cameras", listOf(
        camerasOptionA,
        camerasOptionAConfirm,
        camerasOptionAMoving,
    )),
    LabScreen("search-settings", "Settings › Search", listOf(
        current { SearchSettingsScreen(100, PeopleLabData.indexingRunning, PeopleLabData.phoneSearch, SearchSettingsActions()) },
        LabOption("nightly", "Nightly run", "The nightly run reading this user's photos") {
            SearchSettingsScreen(100, PeopleLabData.indexingNightly, PeopleLabData.phoneSearch, SearchSettingsActions())
        },
        LabOption("paused", "Paused", "Paused from the phone") {
            SearchSettingsScreen(100, PeopleLabData.indexingPaused, PeopleLabData.phoneSearch, SearchSettingsActions())
        },
        LabOption("queued", "Waiting", "Behind another run") {
            SearchSettingsScreen(100, PeopleLabData.indexingQueued, PeopleLabData.phoneSearch, SearchSettingsActions())
        },
        LabOption("idle", "Idle", "The last run, last night") {
            SearchSettingsScreen(100, PeopleLabData.indexingIdle, PeopleLabData.phoneSearch, SearchSettingsActions())
        },
    )),
)
