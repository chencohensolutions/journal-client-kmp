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
    ]),
    LabScreen(id: "free-up", title: "Free up space", options: [
        current { FreeUpScreen(state: data.freeUp, actions: .lab) },
    ]),
    LabScreen(id: "hidden", title: "Hidden collections", options: [
        current { HiddenScreen(state: data.hiddenCollections, actions: .lab) },
    ]),
]

/// Settings as the app pushes it, with backup running; its Free up space row opens the lab's Free up space.
private func labSettings() -> some View {
    SettingsScreen(
        settings: data.settings, backup: data.backup, notBackedUp: Int(data.notBackedUp), sourceCounts: data.sourceCounts,
        previewBytes: data.previewBytes, serverUsage: data.serverUsage, freeUp: data.freeUp, actions: .lab,
        syncProgress: data.syncProgress
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
        toggleTag: { _, _, _ in }, openAllPhotos: {}, openSettings: {}, openTags: {}, showOnRow: { _, _ in }, hideOnRow: { _, _ in },
        alwaysShow: { _, _ in }, openRevealed: { _, _, _ in },
        explore: ExploreActions(openPlace: { _ in }, openCountry: { _ in }, openYear: { _, _ in }, openMap: {}, toggleSource: { _ in }),
        allowAccess: {}, registerAgain: {}
    )
}

extension SettingsActions {
    /// Does nothing: the lab has no controllers.
    static let lab = SettingsActions(
        setGroupingMode: { _ in }, setGapHours: { _ in }, setSimilarityPercent: { _ in }, resetSimilarity: {}, toggleFeedSource: { _ in },
        setSayWhatsHidden: { _ in }, setPreviewsEnabled: { _ in }, setPreviewFormat: { _ in }, setPreviewQuality: { _ in },
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
