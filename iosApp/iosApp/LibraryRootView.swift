import SwiftUI
import SharedLogic

/// Everything after enrollment: photo access, then the journal and its screens.
struct LibraryRootView: View {
    @ObservedObject var journal: JournalViewModel

    @ObservedObject var library: LibraryViewModel
    @ObservedObject var stories: StoriesViewModel
    var onRegisterAgain: () -> Void

    @Environment(\.scenePhase) private var scenePhase
    @State private var path: [Route] = []
    @State private var filter = LibraryFilter.all
    @State private var viewer: ViewerRequest?

    private enum Route: Hashable {
        case allPhotos
        case story(String)
        case group(storyId: String, groupId: String)
        case tags
        case tag(String)
    }

    /// Opened from a group (`storyId` and `groupId`), a story (`storyId`), a tag (`tag`), one day of the journal
    /// (`day`), or All photos with `filter`.
    private struct ViewerRequest: Identifiable {
        let storyId: String?
        var groupId: String? = nil
        let day: LocalDay?
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
        viewer = request
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
                        actions: HomeActions(
                            openItem: { day, item in openViewer(ViewerRequest(storyId: nil, day: day.day, filter: .all, itemId: item.id)) },
                            openStory: { path.append(.story($0)) },
                            createStory: { title, day in
                                if let id = stories.createStory(title: title, day: day) { path.append(.story(id)) }
                            },
                            openAllPhotos: { path.append(.allPhotos) },
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
                                openViewer(ViewerRequest(storyId: nil, day: nil, filter: filter, itemId: item.id))
                            }
                        case .tags:
                            TagsView(stories: stories.state) { path.append(.tag($0)) }
                        case .tag(let name):
                            TagView(tag: name, stories: stories.state) { item in
                                openViewer(ViewerRequest(storyId: nil, day: nil, filter: .all, itemId: item.id, tag: name))
                            }
                        case .story(let id):
                            StoryPageView(
                                story: stories.state.story(id: id),
                                neighborDays: stories.state.neighborDays(storyId: id),
                                health: journal.state.health,
                                actions: StoryPageActions(
                                    openItem: { openViewer(ViewerRequest(storyId: id, day: nil, filter: .all, itemId: $0.id)) },
                                    openGroup: { path.append(.group(storyId: id, groupId: $0.id)) },
                                    addDay: { stories.addDay($0, to: id) },
                                    rename: { stories.rename(id, to: $0) },
                                    delete: {
                                        stories.delete(id)
                                        path.removeLast()
                                    },
                                    setIntro: { stories.setIntro($0, of: id) },
                                    acceptSuggestion: { stories.acceptSuggestion($0, in: id) },
                                    createGroup: { stories.createGroup($0, in: id) },
                                    dismissSuggestion: { stories.dismissSuggestion($0, in: id) },
                                    ungroup: { stories.ungroup($0, in: id) },
                                    removeItems: { stories.remove($0, from: id) }
                                )
                            )
                        case let .group(storyId, groupId):
                            GroupPageView(
                                story: stories.state.story(id: storyId),
                                group: stories.state.story(id: storyId)?.group(id: groupId),
                                onOpen: {
                                    openViewer(ViewerRequest(storyId: storyId, groupId: groupId, day: nil, filter: .all, itemId: $0.id))
                                },
                                onRename: { stories.renameGroup(groupId, in: storyId, to: $0) },
                                onSetText: { stories.setText($0, ofGroup: groupId, in: storyId) },
                                onUngroup: { stories.ungroup(groupId, in: storyId) }
                            )
                        }
                    }
                }
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
                            }
                        )
                    )
                    // The viewer is always dark, like a photo app; the rest follows the system.
                    .preferredColorScheme(.dark)
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
    /// cover), the day of the journal (only its unsorted photos, as shown there), or everything in the All photos filter.
    private func items(for request: ViewerRequest) -> [MediaItem] {
        if let tag = request.tag {
            return stories.state.tagged(tag: tag).flatMap { $0.items }
        }
        if let storyId = request.storyId, let groupId = request.groupId {
            return stories.state.story(id: storyId)?.group(id: groupId)?.items ?? []
        }
        if let storyId = request.storyId {
            return stories.state.story(id: storyId)?.viewerItems ?? []
        }
        if let day = request.day {
            return stories.state.unsortedDays.first { $0.day == day }?.items ?? []
        }
        return library.state.days(filter: request.filter).flatMap { $0.items }
    }
}
