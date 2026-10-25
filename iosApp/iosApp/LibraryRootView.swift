import SwiftUI
import SharedLogic

/// Everything after enrollment: photo access, then the journal and its screens.
struct LibraryRootView: View {
    @ObservedObject var journal: JournalViewModel

    @ObservedObject var library: LibraryViewModel
    @ObservedObject var stories: StoriesViewModel
    @ObservedObject var settings: SettingsViewModel
    @ObservedObject var backup: BackupViewModel
    @ObservedObject var search: SearchViewModel
    @ObservedObject var people: PeopleViewModel
    @ObservedObject var findings: FindingsViewModel
    @ObservedObject var phoneSearch: PhoneSearchViewModel
    @ObservedObject var serverIndexing: ServerIndexingViewModel
    var onRegisterAgain: () -> Void

    @Environment(\.scenePhase) private var scenePhase
    /// Sound recordings play here; a recording that couldn't be played (Ogg, Opus) says so once.
    @ObservedObject private var audioPlayer = AudioPlayer.shared
    /// The system's scheme: the map over the always-dark viewer follows it, as the map from Explore does.
    @Environment(\.colorScheme) private var colorScheme
    @State private var path: [Route] = []
    @State private var viewer: ViewerRequest?
    /// The photo the viewer shows; the screen under it keeps that photo's tile in view to zoom back into.
    @State private var showing: ViewerShowing?
    /// Set to open the crop screen over the journal, e.g. `crop = CropRequest(itemId: id, kind: .moments)`.
    @State private var crop: CropRequest?
    /// The map opened from the viewer's details, over the viewer (back returns to the photo).
    @State private var viewerMap: MapCenter?
    /// "Show in Moments": the home's Moments tab, landed on the photo.
    @State private var momentsRequest: PhotoRequest?
    /// Photos mark themselves in it; the viewer zooms out of them.
    @Namespace private var zoom
    /// Looks like this › More's photos, by the photo they look like. The items are kept: `StoriesState.itemsById` holds
    /// only part of the library (docs/plans/library-on-disk.md, step 6a), and they show until the database's are read.
    @State private var similarLists: [String: [MediaItem]] = [:]

    private enum Route: Hashable {
        case tags
        case tag(String)
        case settings
        /// Explore: a town's page, a year's page (on one of its months), and the map.
        case place(String)
        /// A country's page of towns; nil for "Other places".
        case country(String?)
        case year(Int, month: Int?)
        case map
        /// A person's page, or a group's ("Who's this?").
        case person(String)
        /// The photos that look like this one (its details › Looks like this › More).
        case similar(String)
        /// A sound recording's page, by its sha256.
        case recording(String)
    }

    /// Opened from the storyline (`storyline`), a tag (`tag`), a row of Moments (`feedSection`, its key, with
    /// `revealed` a band of hidden photos on it), Explore (`scope`: the map's photos, or with `feedSection` a row of a
    /// town's or a year's page), or the search's results (`search`).
    private struct ViewerRequest: Identifiable {
        var storyline = false
        var search = false
        var feedSection: String? = nil
        /// With `feedSection`: the band of this hidden source's photos shown on that row.
        var revealed: MediaSource? = nil
        /// Empty from the map without a photo tapped: the first of its photos.
        var itemId: String
        var tag: String? = nil
        var scope: ExploreScope? = nil
        /// The photos a page showed, in its order (a person's, similar photos, Best first).
        var list: [String]? = nil
        /// What it swipes through, fixed when opened: tagging a photo or taking it out of the storyline must not pull it
        /// out from under it.
        var itemIds: [String] = []
        /// The items as opened: shown until the database's are read (`StoriesState.itemsById` holds only part of the
        /// library), so the viewer never comes out empty meanwhile.
        var items: [MediaItem] = []
        var id: String { itemId }
    }

    private func openViewer(_ request: ViewerRequest) {
        // An Explore page's or the map's photos are read from the database first (step 6c).
        if let scope = request.scope {
            let (library, stories) = (library.state, stories.state)
            Task { @MainActor in
                var request = request
                let all = await exploreReads.shown(library, stories, scope)
                request.items = request.feedSection.map { section in stories.feedOf(items: all).section(key: section)?.items ?? [] } ?? all
                if request.itemId.isEmpty, let first = request.items.first { request.itemId = first.id }
                guard !request.itemId.isEmpty else { return }
                present(request)
            }
        } else {
            var request = request
            request.items = items(for: request)
            present(request)
        }
    }

    private func present(_ request: ViewerRequest) {
        var request = request
        request.itemIds = request.items.map(\.id)
        showing = ViewerShowing(session: UUID(), itemId: request.itemId)
        viewer = request
    }

    /// The viewer's photos: as the database has them now, else as opened, unless they are gone from the library.
    private func viewerItems(_ request: ViewerRequest) -> [MediaItem] {
        let state = stories.state
        let opened = Dictionary(request.items.map { ($0.id, $0) }, uniquingKeysWith: { first, _ in first })
        return request.itemIds.compactMap { id in
            state.itemsById[id] ?? opened[id].flatMap { state.gone(itemId: id) ? nil : $0 }
        }
    }

    private var exploreReads: ExploreReads { ExploreReads(controller: stories.controller) }

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
                        showInMoments: momentsRequest,
                        backupRunning: backup.running,
                        actions: HomeActions(
                            openItem: { section, item in
                                openViewer(ViewerRequest(feedSection: section.key, itemId: item.id))
                            },
                            openStorylineItem: { item in openViewer(ViewerRequest(storyline: true, itemId: item.id)) },
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
                            openSettings: { path.append(.settings) },
                            openTags: { path.append(.tags) },
                            showOnRow: { stories.showOnRow($0, source: $1) },
                            hideOnRow: { stories.hideOnRow($0, source: $1) },
                            alwaysShow: { settings.alwaysShow(days: $0, source: $1) },
                            openRevealed: { section, revealed, item in
                                openViewer(ViewerRequest(feedSection: section.key, revealed: revealed.source, itemId: item.id))
                            },
                            explore: ExploreActions(
                                openPlace: { path.append(.place($0)) },
                                openCountry: { path.append(.country($0)) },
                                openYear: { path.append(.year($0, month: $1)) },
                                openMap: { path.append(.map) },
                                toggleSource: settings.toggleFeedSource,
                                openPerson: { path.append(.person($0)) },
                                loadPeople: people.load,
                                reloadPeople: people.reload
                            ),
                            allowAccess: requestOrOpenSettings,
                            registerAgain: onRegisterAgain,
                            search: SearchActions(
                                search: search.search,
                                clear: search.clear,
                                chooseYear: search.chooseYear,
                                chooseTown: search.chooseTown,
                                loadMore: search.loadMore,
                                openItem: { item in openViewer(ViewerRequest(search: true, itemId: item.id)) },
                                prepare: search.prepare,
                                addPerson: search.addPerson,
                                removePerson: search.removePerson
                            ),
                            loadMoreMoments: stories.controller.loadMoreMoments,
                            loadMomentsTo: { day, done in stories.controller.loadMomentsTo(day: day, onLoaded: done) },
                            loadMomentsToItem: { stories.controller.loadMomentsToItem(itemId: $0) }
                        ),
                        search: search.state,
                        people: journal.features.faces ? people.state : nil
                    )
                    .toolbar(.hidden, for: .navigationBar)
                    .navigationDestination(for: Route.self) { route in
                        switch route {
                        case .settings:
                            SettingsView(
                                settings: settings, backup: backup, notBackedUp: Int(library.state.notBackedUpCount),
                                sourceCounts: stories.state.feed.counts, refreshLibrary: library.refresh,
                                phoneSearch: journal.features.localIndexes ? phoneSearch : nil,
                                indexing: journal.features.indexing ? serverIndexing : nil
                            )
                        case .place(let town):
                            PlacePageView(
                                town: town, library: library.state, stories: stories.state, openItem: openExplore, toggleSource: settings.toggleFeedSource,
                                openInOrder: journal.features.aesthetic ? openInOrder : nil
                            )
                        case .country(let country):
                            CountryPageView(country: country, library: library.state, stories: stories.state) { path.append(.place($0)) }
                        case let .year(year, month):
                            YearPageView(
                                year: year, month: month, library: library.state, stories: stories.state, openItem: openExplore,
                                toggleSource: settings.toggleFeedSource, openInOrder: journal.features.aesthetic ? openInOrder : nil
                            )
                        case .map:
                            ExploreMapView(library: library.state, stories: stories.state, toggleSource: settings.toggleFeedSource) { scope, itemId in
                                openViewer(ViewerRequest(itemId: itemId ?? "", scope: scope))
                            }
                        case .person(let id):
                            PersonRoute(id: id, library: library, people: people) { items, item in
                                openInOrder(items, item)
                            } openPerson: { path.append(.person($0)) } close: {
                                if !path.isEmpty { path.removeLast() }
                            }
                        case .similar(let itemId):
                            // As the page gave them until the database's are read.
                            let opened = similarLists[itemId] ?? []
                            let items = opened.compactMap { item in
                                stories.state.itemsById[item.id] ?? (stories.state.gone(itemId: item.id) ? nil : item)
                            }
                            SimilarPageView(items: items) { openInOrder(items, $0) }
                                .task(id: itemId) { await stories.want(opened.map(\.id)) }
                        case .recording(let sha256):
                            recordingPage(sha256)
                        case .tags:
                            TagsView(stories: stories.state) { path.append(.tag($0)) }
                        case .tag(let name):
                            TagView(tag: name, stories: stories.state) { item in
                                openViewer(ViewerRequest(itemId: item.id, tag: name))
                            }
                        }
                    }
                }
                .environment(\.zoomNamespace, zoom)
                .environment(\.exploreReads, exploreReads)
                .environment(\.feedAudio, FeedAudio(player: AudioPlayer.shared) { path.append(.recording($0.sha256)) })
                .environment(\.showOnPhoneBadge, settings.state.showOnPhoneBadge)
                .cropCover($crop, stories: stories.state, want: stories.want) { itemId, crops in stories.setCrops(crops, of: itemId) }
                .environment(\.viewerShowing, showing)
                .fullScreenCover(item: $viewer) { request in
                    MediaViewerView(
                        items: viewerItems(request),
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
                            showing: { itemId in
                                showing = ViewerShowing(session: showing?.session ?? UUID(), itemId: itemId)
                            },
                            openMap: { point in viewerMap = MapCenter(latitude: point.latitude, longitude: point.longitude) },
                            // Opened from Moments (a row, not an Explore page's): it is there already.
                            showInMoments: request.feedSection != nil && request.scope == nil ? nil : { itemId in
                                viewer = nil
                                path = []
                                momentsRequest = PhotoRequest(itemId: itemId)
                            },
                            setPrivate: { item, isPrivate in await ServerImages.shared.setPrivate(item, isPrivate: isPrivate) },
                            setZoom: { itemId, zoom in stories.setZoom(zoom, of: itemId) },
                            findIn: findings.show,
                            openPerson: { id in
                                viewer = nil
                                path.append(.person(id))
                            },
                            openSimilar: { item in
                                let similar = findings.state.similar
                                viewer = nil
                                DispatchQueue.main.async { openViewer(ViewerRequest(itemId: item.id, list: similar.map(\.id), items: similar)) }
                            },
                            moreSimilar: {
                                similarLists[findings.state.itemId] = findings.state.similar
                                let itemId = findings.state.itemId
                                viewer = nil
                                path.append(.similar(itemId))
                            },
                            faces: facesActions(closingViewer: true),
                            people: people.state.list.people
                        ),
                        findings: findings.state
                    )
                    // The photo shown and its neighbours, read from the database as it is swiped.
                    .task(id: showing?.itemId ?? request.itemId) {
                        await stories.want(around(request.itemIds, showing?.itemId ?? request.itemId))
                    }
                    // The map from the details, close up on the place; a photo opened on it takes the viewer's place.
                    .fullScreenCover(item: $viewerMap) { center in
                        ExploreMapView(library: library.state, stories: stories.state, at: center, toggleSource: settings.toggleFeedSource) { scope, itemId in
                            viewerMap = nil
                            openViewer(ViewerRequest(itemId: itemId ?? "", scope: scope))
                        }
                        .environment(\.colorScheme, colorScheme)
                        .preferredColorScheme(colorScheme)
                    }
                    // The viewer is always dark, like a photo app; the rest follows the system.
                    .preferredColorScheme(.dark)
                    .navigationTransition(.zoom(sourceID: zoomSource(request), in: zoom))
                }
            }
        }
        .alert("The recording couldn’t be played", isPresented: $audioPlayer.failed) {
            Button("OK", role: .cancel) {}
        }
        .task { journal.connect() }
        // The search page offers people's faces once the server says it has face search.
        .onChange(of: journal.features.faces, initial: true) { _, faces in if faces { people.load() } }
        // Picks up photos taken and permission changes made while the app was in the background.
        .onChange(of: scenePhase, initial: true) { _, phase in
            // A recording plays only while the app is in front: no background audio.
            if phase == .background { AudioPlayer.shared.pause() }
            if phase == .active {
                library.refresh()
                // The indexes kept on this phone catch up once a day.
                phoneSearch.updateIfDue()
            }
        }
    }

    /// A sound recording's page: the photos taken while it ran come from its moment in Moments. Gone (hidden again,
    /// say) closes it.
    @ViewBuilder
    private func recordingPage(_ sha256: String) -> some View {
        if let recording = stories.state.sounds[sha256] {
            let moment = stories.state.feed.entries.lazy
                .compactMap { ($0 as? FeedEntrySection)?.section }
                .flatMap { $0.moments }
                .first { $0.recording?.sha256 == sha256 }
            AudioPageView(
                recording: recording,
                place: moment?.place,
                during: (moment?.items ?? []).sorted { $0.takenAtMillis < $1.takenAtMillis },
                player: AudioPlayer.shared
            ) { contact, number in
                Task {
                    _ = try? await IosJournal.shared.serverLibrary().setCall(
                        sha256: sha256, contact: contact.isEmpty ? nil : contact, number: number.isEmpty ? nil : number
                    )
                }
            }
            .preferredColorScheme(.dark)
        } else {
            Color.clear.onAppear { if !path.isEmpty { path.removeLast() } }
        }
    }

    /// A photo of a town's or a year's page: the viewer swipes through its row, as the page shows it.
    private func openExplore(_ scope: ExploreScope, _ section: FeedSection, _ item: MediaItem) {
        openViewer(ViewerRequest(feedSection: section.key, itemId: item.id, scope: scope))
    }

    /// A face's sheet: corrections go through the people view model; Open their page closes the viewer first.
    private func facesActions(closingViewer: Bool) -> FaceActions {
        var actions = people.faceActions
        actions.openPerson = { id in
            if closingViewer { viewer = nil }
            path.append(.person(id))
        }
        return actions
    }

    /// A photo of a page that shows its photos in its own order (a person's, Best first, similar photos): the viewer
    /// swipes through them in that order.
    private func openInOrder(_ items: [MediaItem], _ item: MediaItem) {
        openViewer(ViewerRequest(itemId: item.id, list: items.map(\.id), items: items))
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
    /// photos in the order shown), the map's photos, what the search found, or what a page showed (`list`).
    private func items(for request: ViewerRequest) -> [MediaItem] {
        if request.list != nil {
            return request.items
        }
        if request.search {
            return search.state.items
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
        return []
    }
}

/// The viewer's photo `id` and up to 50 neighbours each way: what it asks the database for as it is swiped (as
/// Android's `around`).
private func around(_ ids: [String], _ id: String) -> [String] {
    let at = ids.firstIndex(of: id) ?? 0
    return Array(ids[max(0, at - 50)..<min(ids.count, at + 51)])
}

/// A person's page, or "Who's this?" for a group, on its own `PersonViewModel`.
private struct PersonRoute: View {
    @StateObject private var model: PersonViewModel
    @ObservedObject var people: PeopleViewModel
    let openItems: ([MediaItem], MediaItem) -> Void
    let openPerson: (String) -> Void
    let close: () -> Void

    init(
        id: String, library: LibraryViewModel, people: PeopleViewModel, openItems: @escaping ([MediaItem], MediaItem) -> Void,
        openPerson: @escaping (String) -> Void, close: @escaping () -> Void
    ) {
        _model = StateObject(wrappedValue: PersonViewModel(id: id, library: library))
        self.people = people
        self.openItems = openItems
        self.openPerson = openPerson
        self.close = close
    }

    var body: some View {
        let state = model.state
        Group {
            if state.person?.isGroup ?? state.id.hasPrefix("g") {
                GroupPageView(
                    state: state,
                    actions: GroupActions(
                        setName: model.setName, name: model.rename, join: model.join,
                        openItem: { item in openItems(state.items, item) }, retry: model.load
                    )
                )
            } else {
                PersonPageView(
                    state: state,
                    others: (people.state.list.people + people.state.list.groups).filter { $0.id != state.id },
                    actions: PersonActions(
                        setQuery: model.setQuery, setSort: model.setSort, openPerson: openPerson,
                        openItem: { item in openItems(state.items, item) },
                        rename: model.rename, merge: model.merge, forget: model.forget, notThem: model.notThem, retry: model.load,
                        notThemFaces: { model.notThem(faces: $0) }, faces: people.faceActions
                    )
                )
            }
        }
        .onChange(of: state.gone) { _, gone in if gone { close() } }
    }
}
