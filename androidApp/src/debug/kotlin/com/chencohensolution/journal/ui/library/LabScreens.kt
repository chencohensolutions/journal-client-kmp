package com.chencohensolution.journal.ui.library

import androidx.compose.runtime.Composable
import com.chencohensolution.journal.connection.ServerHealth
import com.chencohensolution.journal.settings.SettingsState
import com.chencohensolution.journal.server.CamerasState
import com.chencohensolution.journal.server.SoundFoldersState
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
    // Made-up data (KindsLab): unlocked, as the emulator has no screen lock; the switches work while it is open.
    LabScreen("private", "Private settings", listOf(
        current { PrivateSettingsLab() },
        LabOption("locked", "Behind the phone's lock", "As the app opens it: the phone's lock first") {
            PhoneLocked(androidx.compose.ui.res.stringResource(com.chencohensolution.journal.R.string.settings_hidden_title), back = {}) { PrivateSettingsLab() }
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
    // Settings › Models on this phone (Nov 8, 2026; option A of the round in docs/screens/mockups/models/).
    LabScreen("models", "Settings › Models on this phone", listOf(
        current { LibraryTheme(dark = true) { ModelsScreen(ModelsLab.usual, ModelsActions()) } },
        LabOption("downloading", "Downloading") { LibraryTheme(dark = true) { ModelsScreen(ModelsLab.downloading, ModelsActions()) } },
        LabOption("waiting", "Waiting for Wi-Fi") { LibraryTheme(dark = true) { ModelsScreen(ModelsLab.waiting, ModelsActions()) } },
        LabOption("offline", "Offline, server without the photo model") { LibraryTheme(dark = true) { ModelsScreen(ModelsLab.offline, ModelsActions()) } },
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
        current {
            SettingsScreen(
                SettingsState(),
                SettingsActions(),
                kinds = labKinds,
                cameras = CamerasState.Ready(labCameras),
                soundFolders = SoundFoldersState.Ready(labSoundFolders),
            )
        },
        LabOption("phone-search", "With On this phone", "Search's row with a phone index kept") {
            SettingsScreen(SettingsState(), SettingsActions(), phoneSearch = PeopleLabData.phoneSearch)
        },
        LabOption("models", "With Models on this phone", "The row under Search: 1 of 2 downloaded") {
            SettingsScreen(SettingsState(), SettingsActions(), phoneSearch = PeopleLabData.phoneSearch, models = ModelsLab.usual)
        },
    )),
    // Made-up settings and copies (BackupLab): backup on, Camera and a Voice Recorder folder, two copies. The keep-on-phone
    // card and the copies were promoted from the rounds of Oct 28, 2026 (docs/ux-history/backup/).
    LabScreen("backup", "Settings › Backup", listOf(
        current { BackupLab(labCopies) },
        LabOption("problems", "Copies with problems", "Copying with 2 failed, a disk unplugged, one on the server's disk") {
            BackupLab(labCopiesWithProblems)
        },
        LabOption("copies", "The copies' page", "As the Copies row opens it, with the problems") { BackupLab(labCopiesWithProblems, onPage = true) },
    )),
    // Show in the app by kind (Oct 27, 2026), with made-up totals (KindsLab).
    LabScreen("kinds", "Settings › Show in the app", listOf(
        current { SettingsScreen(SettingsState(), SettingsActions(), page = SettingsPage.SHOWN, kinds = labKinds) },
    )),
    // Made-up cameras (CamerasLab): the server isn't asked, and the move is timed.
    LabScreen("cameras", "Settings › Your cameras", listOf(
        current { CamerasLab(labCameras) },
        LabOption("confirm", "Asking before moving", "\"463 photos will move to Sent to you\" (the LG's two tags)") { CamerasLabConfirm() },
        LabOption("moving", "Moving", "\"Moving 463 photos…\" on the row") { CamerasLabMoving() },
    )),
    // Made-up folders (SoundFoldersLab): the server isn't asked, and the move is timed.
    LabScreen("sound-folders", "Settings › Sound folders", listOf(
        current { SoundFoldersLab(labSoundFolders) },
        LabOption("confirm", "Asking in the row", "\"1,520 files will become Calls\"") { SoundFoldersLabConfirm() },
        LabOption("moving", "Moving", "\"Moving 1,140 files to Calls…\" in a band at the top") { SoundFoldersLabMoving() },
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
