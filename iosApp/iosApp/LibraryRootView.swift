import SwiftUI
import SharedLogic

/// Everything after enrollment: photo access, then the journal and its screens.
struct LibraryRootView: View {
    @ObservedObject var journal: JournalViewModel

    @ObservedObject var library: LibraryViewModel
    @ObservedObject var stories: StoriesViewModel
    @ObservedObject var settings: SettingsViewModel
    @ObservedObject var backup: BackupViewModel
    var onRegisterAgain: () -> Void

    @Environment(\.scenePhase) private var scenePhase
    @State private var path: [Route] = []
    @State private var filter = LibraryFilter.all
    @State private var viewer: ViewerRequest?
    /// The photo the viewer shows; the screen under it keeps that photo's tile in view to zoom back into.
    @State private var showing: ViewerShowing?
    /// Set to open the crop screen over the journal, e.g. `crop = CropRequest(itemId: id, kind: .moments)`.
    @State private var crop: CropRequest?
    /// Photos mark themselves in it; the viewer zooms out of them.
    @Namespace private var zoom

    private enum Route: Hashable {
        case allPhotos
        case tags
        case tag(String)
        case settings
        /// Explore: a town's page, a year's page (on one of its months), and the map.
        case place(String)
        /// A country's page of towns; nil for "Other places".
        case country(String?)
        case year(Int, month: Int?)
        case map
    }

    /// Opened from the storyline (`storyline`), a tag (`tag`), a row of Moments (`feedSection`, its key, with
    /// `revealed` a band of hidden photos on it), Explore (`scope`: the map's photos, or with `feedSection` a row of a
    /// town's or a year's page), or All photos with `filter`.
    private struct ViewerRequest: Identifiable {
        var storyline = false
        var feedSection: String? = nil
        /// With `feedSection`: the band of this hidden source's photos shown on that row.
        var revealed: MediaSource? = nil
        let filter: LibraryFilter
        let itemId: String
        var tag: String? = nil
        var scope: ExploreScope? = nil
        /// What it swipes through, fixed when opened: tagging a photo or taking it out of the storyline must not pull it
        /// out from under it.
        var itemIds: [String] = []
        var id: String { itemId }
    }

    private func openViewer(_ request: ViewerRequest) {
        var request = request
        request.itemIds = items(for: request).map(\.id)
        showing = ViewerShowing(session: UUID(), itemId: request.itemId)
        viewer = request
    }

    /// The tile the viewer zooms back into on closing: the photo it shows.
    private func zoomSource(_ request: ViewerRequest) -> String {
        ZoomID.item(showing?.itemId ?? request.itemId)
    }

    var body: some View {
        Group {
            if library.state.access == .notDetermined {
                PhotoAccessView(onAllow: library.requestAccess, onNotNow: library.skipAccess)
            } else {
                NavigationStack(path: $path) {
                    JournalHomeView(
                        library: library.state,
                        stories: stories.state,
                        health: journal.state.health,
                        viewerOpen: viewer != nil,
                        backupRunning: backup.running,
                        actions: HomeActions(
                            openItem: { section, item in
                                openViewer(ViewerRequest(feedSection: section.key, filter: .all, itemId: item.id))
                            },
                            openStorylineItem: { item in openViewer(ViewerRequest(storyline: true, filter: .all, itemId: item.id)) },
                            toggleSource: settings.toggleFeedSource,
                            merge: stories.mergeInFeed,
                            takeOutOfSet: stories.takeOutOfSet,
                            turnSet: { stories.turnSet($0, to: $1) },
                            setWriting: { itemId, title, text in stories.setWriting(title: title, text: text, of: itemId) },
                            restoreWriting: { itemId, version in stories.restoreWriting(version, of: itemId) },
                            // The crop screen, on the photo's Moments crop.
                            crop: { item in crop = CropRequest(itemId: item.id, kind: .moments) },
                            setInStoryline: { itemId, inStoryline in stories.setInStoryline(inStoryline, of: itemId) },
                            toggleTag: { itemId, tag, add in
                                if add { stories.addTag(tag, to: [itemId]) } else { stories.removeTag(tag, from: [itemId]) }
                            },
                            openAllPhotos: { path.append(.allPhotos) },
                            openSettings: { path.append(.settings) },
                            openTags: { path.append(.tags) },
                            showOnRow: { stories.showOnRow($0, source: $1) },
                            hideOnRow: { stories.hideOnRow($0, source: $1) },
                            alwaysShow: { settings.alwaysShow(days: $0, source: $1) },
                            openRevealed: { section, revealed, item in
                                openViewer(ViewerRequest(feedSection: section.key, revealed: revealed.source, filter: .all, itemId: item.id))
                            },
                            explore: ExploreActions(
                                openPlace: { path.append(.place($0)) },
                                openCountry: { path.append(.country($0)) },
                                openYear: { path.append(.year($0, month: $1)) },
                                openMap: { path.append(.map) },
                                toggleSource: settings.toggleFeedSource
                            ),
                            allowAccess: requestOrOpenSettings,
                            registerAgain: onRegisterAgain
                        )
                    )
                    .toolbar(.hidden, for: .navigationBar)
                    .navigationDestination(for: Route.self) { route in
                        switch route {
                        case .allPhotos:
                            AllPhotosView(
                                library: library.state, stories: stories.state, backupRunning: backup.running, health: journal.state.health, filter: $filter
                            ) { item in
                                openViewer(ViewerRequest(filter: filter, itemId: item.id))
                            }
                        case .settings:
                            SettingsView(
                                settings: settings, backup: backup, notBackedUp: Int(library.state.notBackedUpCount),
                                sourceCounts: stories.state.feed.counts, refreshLibrary: library.refresh
                            )
                        case .place(let town):
                            PlacePageView(
                                town: town, library: library.state, stories: stories.state, openItem: openExplore, toggleSource: settings.toggleFeedSource
                            )
                        case .country(let country):
                            CountryPageView(country: country, library: library.state, stories: stories.state) { path.append(.place($0)) }
                        case let .year(year, month):
                            YearPageView(
                                year: year, month: month, library: library.state, stories: stories.state, openItem: openExplore,
                                toggleSource: settings.toggleFeedSource
                            )
                        case .map:
                            ExploreMapView(library: library.state, stories: stories.state, toggleSource: settings.toggleFeedSource) { scope, itemId in
                                guard let first = itemId ?? scopeItems(library.state, stories.state, scope).first?.id else { return }
                                openViewer(ViewerRequest(filter: .all, itemId: first, scope: scope))
                            }
                        case .tags:
                            TagsView(stories: stories.state) { path.append(.tag($0)) }
                        case .tag(let name):
                            TagView(tag: name, stories: stories.state) { item in
                                openViewer(ViewerRequest(filter: .all, itemId: item.id, tag: name))
                            }
                        }
                    }
                }
                .environment(\.zoomNamespace, zoom)
                .environment(\.showOnPhoneBadge, settings.state.showOnPhoneBadge)
                .cropCover($crop, stories: stories.state) { itemId, crops in stories.setCrops(crops, of: itemId) }
                .environment(\.viewerShowing, showing)
                .fullScreenCover(item: $viewer) { request in
                    MediaViewerView(
                        items: request.itemIds.compactMap { stories.state.itemsById[$0] },
                        startId: request.itemId,
                        stories: stories.state,
                        actions: ViewerActions(
                            close: { viewer = nil },
                            setWriting: { itemId, title, text in stories.setWriting(title: title, text: text, of: itemId) },
                            restoreWriting: { itemId, version in stories.restoreWriting(version, of: itemId) },
                            setInStoryline: { itemId, inStoryline in stories.setInStoryline(inStoryline, of: itemId) },
                            toggleTag: { itemId, tag, add in
                                if add { stories.addTag(tag, to: [itemId]) } else { stories.removeTag(tag, from: [itemId]) }
                            },
                            setCrops: { itemId, crops in stories.setCrops(crops, of: itemId) },
                            showing: { itemId in
                                showing = ViewerShowing(session: showing?.session ?? UUID(), itemId: itemId)
                            },
                            downloadOriginal: { item, progress in await ServerImages.shared.downloadOriginal(item, progress: progress) },
                            deleteFromPhone: { item in await ServerImages.shared.deleteFromPhone(item, refresh: library.refresh) },
                            setPrivate: { item, isPrivate in await ServerImages.shared.setPrivate(item, isPrivate: isPrivate) },
                            setZoom: { itemId, zoom in stories.setZoom(zoom, of: itemId) }
                        )
                    )
                    // The viewer is always dark, like a photo app; the rest follows the system.
                    .preferredColorScheme(.dark)
                    .navigationTransition(.zoom(sourceID: zoomSource(request), in: zoom))
                }
            }
        }
        .task { journal.connect() }
        // Picks up photos taken and permission changes made while the app was in the background.
        .onChange(of: scenePhase, initial: true) { _, phase in
            if phase == .active { library.refresh() }
        }
    }

    /// A photo of a town's or a year's page: the viewer swipes through its row, as the page shows it.
    private func openExplore(_ scope: ExploreScope, _ section: FeedSection, _ item: MediaItem) {
        openViewer(ViewerRequest(feedSection: section.key, filter: .all, itemId: item.id, scope: scope))
    }

    /// iOS asks only once; after that only Settings can grant access.
    private func requestOrOpenSettings() {
        if library.state.access == .denied, let url = URL(string: UIApplication.openSettingsURLString) {
            UIApplication.shared.open(url)
        } else {
            library.requestAccess()
        }
    }

    /// The items the viewer swipes through: the storyline, a tag's photos, the row of Moments or an Explore page (its
    /// photos in the order shown), the map's photos, or everything in the All photos filter.
    private func items(for request: ViewerRequest) -> [MediaItem] {
        if let scope = request.scope {
            let items = scopeItems(library.state, stories.state, scope)
            guard let section = request.feedSection else { return items }
            return stories.state.feedOf(items: items).section(key: section)?.items ?? []
        }
        if let tag = request.tag {
            return stories.state.tagged(tag: tag).flatMap { $0.items }
        }
        if request.storyline {
            return stories.state.storyline
        }
        if let section = request.feedSection, let source = request.revealed {
            return stories.state.feed.section(key: section)?.revealed.first { $0.source == source }?.items ?? []
        }
        if let section = request.feedSection {
            return stories.state.feed.section(key: section)?.items ?? []
        }
        return stories.state.shownDays(days: library.state.days(filter: request.filter)).flatMap { $0.items }
    }
}
