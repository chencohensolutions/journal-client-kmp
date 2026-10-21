import SwiftUI
import SharedLogic

/// Everything after enrollment: photo access, then the journal and its screens.
struct LibraryRootView: View {
    @ObservedObject var journal: JournalViewModel

    @ObservedObject var library: LibraryViewModel
    @ObservedObject var stories: StoriesViewModel
    @ObservedObject var settings: SettingsViewModel
    var onRegisterAgain: () -> Void

    @Environment(\.scenePhase) private var scenePhase
    @State private var path: [Route] = []
    @State private var filter = LibraryFilter.all
    @State private var viewer: ViewerRequest?
    /// The photo the viewer shows; the screen under it keeps that photo's tile in view to zoom back into.
    @State private var showing: ViewerShowing?
    /// Photos, story cards and group tiles mark themselves in it; the screens they open zoom out of them.
    @Namespace private var zoom

    private enum Route: Hashable {
        case allPhotos
        case story(String)
        case group(storyId: String, groupId: String)
        case tags
        case tag(String)
        case settings
    }

    /// Opened from a group (`storyId` and `groupId`), a story (`storyId`), a tag (`tag`), a row of Moments
    /// (`feedSection`, its key), or All photos with `filter`.
    private struct ViewerRequest: Identifiable {
        let storyId: String?
        var groupId: String? = nil
        var feedSection: String? = nil
        let filter: LibraryFilter
        let itemId: String
        var tag: String? = nil
        /// What it swipes through, fixed when opened: tagging or grouping a photo must not pull it out from under it.
        var itemIds: [String] = []
        var id: String { itemId }
    }

    private func openViewer(_ request: ViewerRequest) {
        var request = request
        request.itemIds = items(for: request).map(\.id)
        showing = ViewerShowing(session: UUID(), itemId: request.itemId)
        viewer = request
    }

    /// The tile the viewer zooms back into on closing: the photo it shows, or the group whose cover that is (a group
    /// is one stop in the viewer, its cover).
    private func zoomSource(_ request: ViewerRequest) -> String {
        let itemId = showing?.itemId ?? request.itemId
        let story = request.storyId.flatMap { stories.state.story(id: $0) }
        let tiles = request.groupId.flatMap { story?.group(id: $0)?.tiles } ?? story?.tiles ?? []
        let group = tiles.lazy.compactMap { ($0 as? StoryTileGroup)?.group }.first { $0.cover.id == itemId }
        return group.map { ZoomID.group($0.id) } ?? ZoomID.item(itemId)
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
                        actions: HomeActions(
                            openItem: { section, item in
                                openViewer(ViewerRequest(storyId: nil, feedSection: section.key, filter: .all, itemId: item.id))
                            },
                            openStory: { path.append(.story($0)) },
                            createStory: { title, feedKey in
                                if let id = stories.createStory(title: title, feedKey: feedKey) { path.append(.story(id)) }
                            },
                            toggleSource: settings.toggleFeedSource,
                            dismissTrip: settings.dismissTrip,
                            merge: stories.mergeInFeed,
                            takeOutOfSet: stories.takeOutOfSet,
                            turnSet: { stories.turnSet($0, to: $1) },
                            setCaption: { itemId, text in stories.setCaption(text, of: itemId) },
                            // The crop screen opens here, on the photo's Moments crop.
                            crop: { _ in },
                            toggleStory: { storyId, itemId, add in
                                if add { stories.add([itemId], to: storyId) } else { stories.remove([itemId], from: storyId) }
                            },
                            createStoryWith: { title, itemId in
                                if let id = stories.createStory(title: title, itemIds: [itemId]) { path.append(.story(id)) }
                            },
                            toggleTag: { itemId, tag, add in
                                if add { stories.addTag(tag, to: [itemId]) } else { stories.removeTag(tag, from: [itemId]) }
                            },
                            openAllPhotos: { path.append(.allPhotos) },
                            openSettings: { path.append(.settings) },
                            openTags: { path.append(.tags) },
                            allowAccess: requestOrOpenSettings,
                            registerAgain: onRegisterAgain
                        )
                    )
                    .toolbar(.hidden, for: .navigationBar)
                    .navigationDestination(for: Route.self) { route in
                        switch route {
                        case .allPhotos:
                            AllPhotosView(library: library.state, health: journal.state.health, filter: $filter) { item in
                                openViewer(ViewerRequest(storyId: nil, filter: filter, itemId: item.id))
                            }
                        case .settings:
                            SettingsView(settings: settings)
                        case .tags:
                            TagsView(stories: stories.state) { path.append(.tag($0)) }
                        case .tag(let name):
                            TagView(tag: name, stories: stories.state) { item in
                                openViewer(ViewerRequest(storyId: nil, filter: .all, itemId: item.id, tag: name))
                            }
                        case .story(let id):
                            StoryPageView(
                                story: stories.state.story(id: id),
                                neighborDays: stories.state.neighborDays(storyId: id),
                                health: journal.state.health,
                                actions: StoryPageActions(
                                    openItem: { openViewer(ViewerRequest(storyId: id, filter: .all, itemId: $0.id)) },
                                    openGroup: { path.append(.group(storyId: id, groupId: $0.id)) },
                                    addDay: { stories.addDay($0, to: id) },
                                    rename: { stories.rename(id, to: $0) },
                                    delete: {
                                        stories.delete(id)
                                        path.removeLast()
                                    },
                                    setIntro: { stories.setIntro($0, of: id) },
                                    acceptSuggestion: { stories.acceptSuggestion($0, in: id) },
                                    groupTiles: { stories.groupTiles($0, in: id) },
                                    mergeTiles: { stories.mergeTiles($0, in: id) },
                                    restore: { stories.restore($0) },
                                    dismissSuggestion: { stories.dismissSuggestion($0, in: id) },
                                    ungroup: { stories.ungroup($0, in: id) },
                                    removeItems: { stories.remove($0, from: id) },
                                    resetGroups: { stories.resetGroups(in: id) }
                                )
                            )
                            .navigationTransition(.zoom(sourceID: ZoomID.story(id), in: zoom))
                        case let .group(storyId, groupId):
                            GroupPageView(
                                story: stories.state.story(id: storyId),
                                group: stories.state.story(id: storyId)?.group(id: groupId),
                                actions: GroupPageActions(
                                    openItem: {
                                        openViewer(ViewerRequest(storyId: storyId, groupId: groupId, filter: .all, itemId: $0.id))
                                    },
                                    openGroup: { path.append(.group(storyId: storyId, groupId: $0.id)) },
                                    rename: { stories.renameGroup(groupId, in: storyId, to: $0) },
                                    setText: { stories.setText($0, ofGroup: groupId, in: storyId) },
                                    ungroup: { stories.ungroup(groupId, in: storyId) },
                                    groupTiles: { stories.groupTiles($0, in: storyId, parent: groupId) },
                                    mergeTiles: { stories.mergeTiles($0, in: storyId, parent: groupId) },
                                    restore: { stories.restore($0) },
                                    takeOut: { stories.removeFromGroup($0, groupId: groupId, in: storyId) },
                                    acceptSuggestion: { stories.acceptSuggestion($0, inGroup: groupId, of: storyId) },
                                    dismissSuggestion: { stories.dismissSuggestion($0, in: storyId) }
                                )
                            )
                            .navigationTransition(.zoom(sourceID: ZoomID.group(groupId), in: zoom))
                        }
                    }
                }
                .environment(\.zoomNamespace, zoom)
                .environment(\.viewerShowing, showing)
                .fullScreenCover(item: $viewer) { request in
                    MediaViewerView(
                        items: request.itemIds.compactMap { stories.state.itemsById[$0] },
                        startId: request.itemId,
                        stories: stories.state,
                        openedFrom: request.storyId.flatMap { stories.state.story(id: $0) },
                        group: request.groupId.flatMap { groupId in request.storyId.flatMap { stories.state.story(id: $0)?.group(id: groupId) } },
                        actions: ViewerActions(
                            close: { viewer = nil },
                            openGroup: { groupId in
                                guard let storyId = request.storyId else { return }
                                viewer = nil
                                path.append(.group(storyId: storyId, groupId: groupId))
                            },
                            setCover: { itemId in
                                if let storyId = request.storyId, let groupId = request.groupId {
                                    stories.setCover(itemId, ofGroup: groupId, in: storyId)
                                }
                            },
                            removeFromGroup: { itemId in
                                if let storyId = request.storyId, let groupId = request.groupId {
                                    stories.removeFromGroup([itemId], groupId: groupId, in: storyId)
                                }
                            },
                            toggleStory: { storyId, itemId, add in
                                if add { stories.add([itemId], to: storyId) } else { stories.remove([itemId], from: storyId) }
                            },
                            createStory: { title, itemId in stories.createStory(title: title, itemIds: [itemId]) },
                            setCaption: { itemId, text in stories.setCaption(text, of: itemId) },
                            toggleTag: { itemId, tag, add in
                                if add { stories.addTag(tag, to: [itemId]) } else { stories.removeTag(tag, from: [itemId]) }
                            },
                            showing: { itemId in
                                showing = ViewerShowing(session: showing?.session ?? UUID(), itemId: itemId)
                            }
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

    /// iOS asks only once; after that only Settings can grant access.
    private func requestOrOpenSettings() {
        if library.state.access == .denied, let url = URL(string: UIApplication.openSettingsURLString) {
            UIApplication.shared.open(url)
        } else {
            library.requestAccess()
        }
    }

    /// The items the viewer swipes through: the group or story it was opened from (where a group is one stop, its
    /// cover), the row of Moments (its photos in the order shown), or everything in the All photos filter.
    private func items(for request: ViewerRequest) -> [MediaItem] {
        if let tag = request.tag {
            return stories.state.tagged(tag: tag).flatMap { $0.items }
        }
        if let storyId = request.storyId, let groupId = request.groupId {
            return stories.state.story(id: storyId)?.group(id: groupId)?.viewerItems ?? []
        }
        if let storyId = request.storyId {
            return stories.state.story(id: storyId)?.viewerItems ?? []
        }
        if let section = request.feedSection {
            return stories.state.feed.section(key: section)?.items ?? []
        }
        return library.state.days(filter: request.filter).flatMap { $0.items }
    }
}
