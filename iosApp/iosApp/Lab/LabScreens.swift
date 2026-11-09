#if DEBUG

import SwiftUI
import SharedLogic

// The UI lab's library (UiLabView): every screen, each with the app's current version first and then the
// suggestions for it. A suggestion is a view in <Screen>Suggestions.swift next to this file, added to its screen's
// list after `current`; when one is chosen it moves into the app and replaces the current version. The ids are the
// same as Android's LabScreens.kt.

/// The screen as the app draws it now, always the first option.
private func current<Content: View>(@ViewBuilder _ content: @escaping () -> Content) -> LabOption {
    LabOption(id: "current", title: "Current", content: content)
}

private let data = LabData.shared

let labScreens: [LabScreen] = [
    LabScreen(id: "storyline", title: "Storyline", options: [
        current { labHome(data.library, data.feedStories) },
    ]),
    LabScreen(id: "moments", title: "Moments", options: [
        current { labHome(data.library, data.feedStories, tab: .moments) },
        LabOption(id: "scrub-edge", title: "Edge scrubber", note: "Grab the right edge: years, then slide left for days") {
            EdgeScrubberSuggestion()
        },
        LabOption(id: "scrub-ruler", title: "Time ruler", note: "Tap the month pill: a dial of months and a strip of days") {
            TimeRulerSuggestion()
        },
        LabOption(id: "scrub-pad", title: "Date pad", note: "Hold the month pill and slide: up/down years, left/right months") {
            DatePadSuggestion()
        },
    ]),
    // A set of many photos (Nov 9, 2026): a page for it, choosing its main photo, a composition per photo, swipes that
    // cross-fade and a slideshow. Shared parts in MomentSet/MomentSetParts.swift; made-up pictures of one run on a beach.
    LabScreen(id: "moment-set", title: "A set of photos", options: [
        current { labHome(data.library, data.feedStories, tab: .moments) },
        LabOption(id: "a", title: "a: Set page", note: "Main photo on top with Play, then every photo in a grid; hold a tile for more") {
            MomentSetScreenA(set: MomentSetLab.beach())
        },
        LabOption(id: "a-viewer", title: "a: its viewer", note: "A tile opened: swipes cross-fade; Make main, Play, Recompose") {
            MomentSetScreenA(set: MomentSetLab.beach(), viewerAt: 20)
        },
        LabOption(id: "b", title: "b: Viewer with a scrubber", note: "Opens on the main photo; slide along the strip to flip through all 36") {
            MomentSetScreenB(set: MomentSetLab.beach())
        },
        LabOption(id: "b-play", title: "b: playing", note: "The slideshow: Fast · Normal · Slow and Stop") {
            MomentSetScreenB(set: MomentSetLab.beach(), playing: true)
        },
        LabOption(id: "b-recompose", title: "b: Recompose", note: "A frame per photo, the set's strip, Same frame for all") {
            MomentSetScreenB(set: MomentSetLab.beach(), at: 14, recomposing: true)
        },
        LabOption(id: "c", title: "c: Flip in the feed", note: "The set stays one photo in Moments: hold and slide to flip, Play in place") {
            MomentSetScreenC(set: MomentSetLab.beach())
        },
    ]),
    LabScreen(id: "moments-filter", title: "Moments filter, many folders", options: [
        current { labHome(data.library, data.folderStories, tab: .moments) },
    ]),
    LabScreen(id: "moments-years", title: "Moments over seven years", options: [
        current { labHome(data.timelineLibrary, data.timelineStories, tab: .moments) },
    ]),
    LabScreen(id: "moments-hidden", title: "Moments, sources hidden", options: [
        current { labHome(data.library, data.hiddenStories, tab: .moments) },
    ]),
    LabScreen(id: "explore", title: "Explore", options: [
        current { labHome(data.placesLibrary, data.placesStories, tab: .explore) },
    ]),
    LabScreen(id: "explore-years", title: "Explore by years", options: [
        current { labHome(data.exploreLibrary, data.exploreStories, tab: .explore, exploreView: .years) },
    ]),
    LabScreen(id: "search", title: "Search", options: [
        current { labHome(data.placesLibrary, data.placesStories, search: data.searchFound, searching: true) },
    ]),
    LabScreen(id: "search-empty", title: "Search, before typing", options: [
        current { labHome(data.placesLibrary, data.placesStories, searching: true) },
    ]),
    LabScreen(id: "country", title: "Country", options: [
        current { CountryPageView(country: "France", library: data.placesLibrary, stories: data.placesStories, openPlace: { _ in }) },
    ]),
    LabScreen(id: "offline", title: "Offline", options: [
        current { labHome(data.library, data.stories, health: .offline) },
    ]),
    LabScreen(id: "viewer", title: "Viewer", options: [
        current {
            MediaViewerView(
                items: [data.tripPhoto, data.notedPhoto], startId: data.tripPhoto.id, stories: data.feedStories, actions: .lab, chrome: true
            )
            .preferredColorScheme(.dark)
        },
    ]),
    LabScreen(id: "viewer-info", title: "Viewer details", options: [
        current {
            MediaViewerView(items: [data.infoPhoto], startId: data.infoPhoto.id, stories: data.infoStories, actions: .lab, showInfo: true)
                .preferredColorScheme(.dark)
        },
    ]),
    LabScreen(id: "crop", title: "Crop", options: [
        current {
            CropView(item: data.croppedPhoto, saved: data.marketCrops, startKind: .moments, onDone: { _ in }, onClose: {})
                .preferredColorScheme(.dark)
        },
    ]),
    LabScreen(id: "settings", title: "Settings", options: [
        current { labSettings() },
        LabOption(id: "offline", title: "Offline", note: "Before the server answers: no usage on Backup, no sync line") {
            labSettings(offline: true)
        },
    ]),
    LabScreen(id: "free-up", title: "Free up space", options: [
        current { FreeUpScreen(state: data.freeUp, actions: .lab) },
    ]),
    LabScreen(id: "hidden", title: "Private settings", options: [
        current { HiddenScreen(state: data.hiddenCollections, actions: .lab) },
    ]),
    // Swap with a friend on the live (unenrolled) view model: the steps that don't need the server.
    LabScreen(id: "swap", title: "Swap with a friend", options: [
        current { NavigationStack { SwapView(model: .shared) }.preferredColorScheme(.dark) },
        LabOption(id: "link", title: "Join: the link") { labSwapThere(.link) },
        LabOption(id: "protect", title: "Join: how it's kept") { labSwapThere(.protect) },
        LabOption(id: "words", title: "Join: the six words") { labSwapThere(.words) },
        LabOption(id: "check", title: "Join: two words typed back") { labSwapThere(.check) },
    ]),
]

private func labSwapThere(_ step: JoinStep) -> some View {
    let draft = JoinDraft()
    draft.linkText = "journal://swap?address=https%3A%2F%2F203.0.113.5%3A56398&code=K7QM-4XPD&from=dana&id=MFZWI3D-BONSGYC-MFZWI3D-BONSGYC-MFZWI3D-BONSGYC-MFZWI3D-BONSGYC"
    draft.name = "Dana"
    draft.suggested = Passphrase.shared.suggest()
    draft.step = step
    return NavigationStack { SwapThereView(model: .shared, draft: draft) }.preferredColorScheme(.dark)
}

/// Settings as the app pushes it, with backup running; its rows open the lab's Free up space and Hidden collections.
/// `offline`: no answer from the server yet.
private func labSettings(offline: Bool = false) -> some View {
    SettingsScreen(
        content: SettingsContent(
            settings: data.settings, backup: data.backup, notBackedUp: Int(data.notBackedUp), sourceCounts: data.sourceCounts,
            previewBytes: data.previewBytes, serverUsage: offline ? nil : data.serverUsage, freeUp: data.freeUp, actions: .lab,
            syncProgress: offline ? nil : data.syncProgress
        )
    ) {
        FreeUpScreen(state: data.freeUp, actions: .lab)
    } hiddenScreen: {
        HiddenScreen(state: data.hiddenCollections, actions: .lab)
    }
}

/// The home screen as the library root shows it, on `tab`.
private func labHome(
    _ library: LibraryState, _ stories: StoriesState, health: ServerHealth = .ok, tab: HomeTab = .storyline, exploreView: ExploreView = .places,
    search: SearchState? = nil, searching: Bool = false
) -> some View {
    JournalHomeView(
        library: library, stories: stories, health: health, viewerOpen: false, backupRunning: nil, actions: .lab, search: search, tab: tab,
        exploreView: exploreView, searching: searching
    )
    .toolbar(.hidden, for: .navigationBar)
}

extension HomeActions {
    /// Does nothing: the lab has no controllers.
    static let lab = HomeActions(
        openItem: { _, _ in }, openStorylineItem: { _ in }, toggleSource: { _ in }, merge: { _ in }, takeOutOfSet: { _ in },
        turnSet: { _, _ in }, setWriting: { _, _, _ in }, restoreWriting: { _, _ in }, setInStoryline: { _, _ in },
        toggleTag: { _, _, _ in }, openSettings: {}, openTags: {}, showOnRow: { _, _ in }, hideOnRow: { _, _ in },
        alwaysShow: { _, _ in }, openRevealed: { _, _, _ in },
        explore: ExploreActions(openPlace: { _ in }, openCountry: { _ in }, openYear: { _, _ in }, openMap: {}, toggleSource: { _ in }),
        allowAccess: {}, registerAgain: {}
    )
}

extension SettingsActions {
    /// Does nothing: the lab has no controllers.
    static let lab = SettingsActions(
        setGroupingMode: { _ in }, setGapHours: { _ in }, setSimilarityPercent: { _ in }, resetSimilarity: {}, toggleFeedSource: { _ in },
        setSayWhatsHidden: { _ in }, setShowFilterBar: { _ in }, setPreviewsEnabled: { _ in }, setPreviewFormat: { _ in }, setPreviewQuality: { _ in },
        setPreviewLimit: { _ in }, clearPreviews: {}, setShowPrivateMedia: { _ in },
        setShowOnPhoneBadge: { _ in }, setSearchPageSize: { _ in }, setBackupEnabled: { _ in }, toggleBackupSource: { _ in },
        setWifiOnly: { _ in }, setChargingOnly: { _ in }, setOrder: { _ in }, backUpNow: {}
    )
}

extension FreeUpActions {
    /// Does nothing: the lab has no controllers.
    static let lab = FreeUpActions(setRule: { _ in }, toggleSource: { _ in }, freeUp: {}, moveToTrash: {})
}

extension HiddenActions {
    /// Does nothing: the lab has no controllers.
    static let lab = HiddenActions(retry: {}, set: { _, _ in }, setQuery: { _ in }, setNudityHidden: { _ in }, setNudityFromPercent: { _ in })
}

extension ViewerActions {
    /// Does nothing: the lab has no controllers.
    static let lab = ViewerActions(
        close: {}, setWriting: { _, _, _ in }, restoreWriting: { _, _ in }, setInStoryline: { _, _ in }, toggleTag: { _, _, _ in },
        showing: { _ in }, openMap: { _ in }, showInMoments: nil, setPrivate: { _, _ in false }, setZoom: { _, _ in }
    )
}

#endif
