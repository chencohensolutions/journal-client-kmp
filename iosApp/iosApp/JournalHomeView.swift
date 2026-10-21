import PhotosUI
import SwiftUI
import SharedLogic

/// What the home screen can ask its parent to do.
struct HomeActions {
    /// A photo of the Moments tab; the viewer swipes through its row.
    var openItem: (FeedSection, MediaItem) -> Void
    var openStory: (String) -> Void
    /// "Save as story" on a trip of the Moments tab, by its key.
    var createStory: (_ title: String, _ feedKey: String) -> Void
    var toggleSource: (MediaSource) -> Void
    var dismissTrip: (_ key: String) -> Void
    /// Moments: the photos of the picked sets and photos become one set; a photo leaves its set.
    var merge: (_ itemIds: [String]) -> Void
    var takeOutOfSet: (_ itemId: String) -> Void
    var turnSet: (_ itemIds: [String], _ itemId: String) -> Void
    /// From a photo's long-press menu in Moments.
    var setCaption: (_ itemId: String, _ text: String) -> Void
    /// Crop in a photo's long-press menu: the crop screen opens here, on the Moments crop.
    var crop: (MediaItem) -> Void = { _ in }
    var toggleStory: (_ storyId: String, _ itemIds: [String], _ add: Bool) -> Void
    var createStoryWith: (_ title: String, _ itemIds: [String]) -> Void
    var toggleTag: (_ itemId: String, _ tag: String, _ add: Bool) -> Void
    var openAllPhotos: () -> Void
    var openSettings: () -> Void
    var openTags: () -> Void
    /// A hidden source's photos on one row of Moments ("+6 WhatsApp"), and hidden again; the setting doesn't change.
    var showOnRow: (_ sectionKey: String, MediaSource) -> Void
    var hideOnRow: (_ sectionKey: String, MediaSource) -> Void
    /// "Always show this day": the source's photos of these days show from now on.
    var alwaysShow: (_ days: [LocalDay], MediaSource) -> Void
    /// A photo of a row's band of hidden photos; the viewer swipes through the band.
    var openRevealed: (FeedSection, RevealedPhotos, MediaItem) -> Void
    /// The Explore tab's towns, years and map.
    var explore: ExploreActions
    var allowAccess: () -> Void
    var registerAgain: () -> Void
}

/// The home screen: in the Stories tab the journal's stories as a timeline, newest first, and in the Moments tab every
/// photo as a feed that groups itself (by day or run of photos, then time, place, source and looks), and in the Explore
/// tab every photo by place and year. See docs/screens/journal-home.md, moments.md and explore.md.
struct JournalHomeView: View {
    let library: LibraryState
    let stories: StoriesState
    let health: ServerHealth
    /// Videos of the feed stop while the viewer is over it.
    let viewerOpen: Bool
    /// The backup run, for the bottom pill.
    let backupRunning: BackupProgress?
    let actions: HomeActions

    private enum Tab { case stories, moments, explore }
    @State private var tab = Tab.stories
    @State private var exploreView = ExploreView.places
    /// The Explore tab's towns and years, worked out off the main thread while it shows.
    @State private var explore: ExploreSummaries?
    /// The trip whose "Save as story" was tapped, and the title to start with.
    @State private var tripToSave: String?
    @State private var newTitle = ""
    /// What a photo's long-press menu opened.
    @State private var sheet: FeedSheet?
    /// Photos and sets picked to merge, by their first photo's id; picking is on while any is.
    @State private var selection = Set<String>()
    /// The feed as stack items, worked out once per feed.
    @State private var rows: [FeedRow] = []
    @StateObject private var playback = FeedPlayback()
    @State private var onScreen = false
    /// "Showing 6 from WhatsApp on Oct 4 · Hide", for a few seconds after a row's chip was tapped.
    @State private var revealToast: RevealToast?
    @Environment(\.scenePhase) private var scenePhase

    var body: some View {
        let moments = tab == .moments
        let barUnderTitle = tab != .stories
        GeometryReader { geometry in
            ScrollView {
                LazyVStack(alignment: .leading, spacing: 0) {
                    if health == .rejected {
                        RegisterAgainCard(onRegister: actions.registerAgain)
                    }
                    switch library.access {
                    case .notDetermined, .denied:
                        NoAccessCard(onAllow: actions.allowAccess)
                    case .limited:
                        LimitedBanner(count: Int(library.itemCount))
                    default:
                        EmptyView()
                    }
                    if let error = library.loadError {
                        Message(text: "Couldn't read your photos: \(error)")
                    }
                    if library.loading && library.days.isEmpty {
                        ProgressView().tint(LibraryColors.onBackground).frame(maxWidth: .infinity).padding(48)
                    } else if canRead && library.days.isEmpty && library.loadError == nil {
                        Message(text: "No photos or videos on this device yet.")
                    }
                    if tab == .explore {
                        if let explore {
                            ExploreRowsView(
                                summaries: explore, view: exploreView, width: geometry.size.width, actions: actions.explore,
                                shownSources: stories.shownSources
                            )
                        }
                    } else if moments {
                        if stories.feed.entries.isEmpty && !library.days.isEmpty {
                            Message(text: "Nothing to show. Turn on a source above.")
                        }
                        FeedRowsView(
                            rows: rows,
                            note: { stories.note(itemId: $0) },
                            width: geometry.size.width,
                            actions: feedActions,
                            selection: $selection,
                            playback: playback
                        )
                    } else {
                        if storyEntries.isEmpty && !library.days.isEmpty {
                            Message(text: "No stories yet. Make one from a day in Moments.")
                        }
                        ForEach(storyEntries, id: \.story.id) { entry in
                            StoryCardView(story: entry.story, width: geometry.size.width)
                                .zoomSource(ZoomID.story(entry.story.id))
                                .onTapGesture { actions.openStory(entry.story.id) }
                        }
                    }
                }
                .padding(.top, barUnderTitle ? 118 : 72)
                .padding(.bottom, 150)
                // Swiping in the viewer keeps its photo in view here, and a set turns to it.
                .followsViewer { id in tab == .moments ? rows.place(of: id) : nil }
            }
            .scrollIndicators(.hidden)
            .onAppear { playback.viewport = viewport(geometry) }
            .onChange(of: geometry.size) { playback.viewport = viewport(geometry) }
        }
        .onChange(of: stories.feed, initial: true) { rows = stories.feed.rows }
        .task(id: tab == .explore ? ExploreInput(library, stories) : nil) {
            if tab == .explore { explore = await ExploreSummaries.of(library, stories) }
        }
        .onChange(of: canPlay, initial: true) { playback.enabled = canPlay }
        .onAppear { onScreen = true }
        .onDisappear { onScreen = false }
        .alert("New story", isPresented: Binding(get: { tripToSave != nil }, set: { if !$0 { tripToSave = nil } })) {
            TextField("Title (optional)", text: $newTitle)
            Button("Create") {
                if let key = tripToSave { actions.createStory(newTitle, key) }
                newTitle = ""
            }
            Button("Cancel", role: .cancel) { newTitle = "" }
        }
        .sheet(item: $sheet, content: sheetContent)
        .background(LibraryColors.background.ignoresSafeArea())
        .overlay(alignment: .top) { topBar }
        .overlay(alignment: .bottom) { bottomControls }
    }

    private var bottomControls: some View {
        BottomControls(summary: selection.isEmpty ? backupSummary(running: backupRunning, notBackedUp: Int(library.notBackedUpCount)) : nil) {
            if !selection.isEmpty && tab == .moments {
                FeedSelectionBar(count: selection.count, onCancel: { selection = [] }, onMerge: merge)
            } else {
                if let toast = revealToast, tab == .moments {
                    UndoBar(
                        count: toast.count,
                        message: "Showing \(toast.count) from \(toast.source.title) on \(toast.days)",
                        actionLabel: "Hide",
                        onUndo: {
                            actions.hideOnRow(toast.sectionKey, toast.source)
                            withAnimation { revealToast = nil }
                        },
                        onDismiss: { withAnimation { revealToast = nil } }
                    )
                }
                SegmentedBar(options: [(Tab.stories, "Stories"), (Tab.moments, "Moments"), (Tab.explore, "Explore")], selection: tabBinding)
            }
        }
    }

    /// Changing tabs stops picking.
    private var tabBinding: Binding<Tab> {
        Binding(get: { tab }, set: { newTab in
            selection = []
            tab = newTab
        })
    }

    @ViewBuilder
    private func sheetContent(_ sheet: FeedSheet) -> some View {
        switch sheet {
        case .write(let id):
            WritingEditor(title: String(localized: "Note"), initial: stories.note(itemId: id)?.caption ?? "") {
                actions.setCaption(id, $0)
            }
        case .addToStory(let ids):
            AddToStorySheet(
                itemIds: ids,
                stories: stories,
                onToggle: { storyId, add in actions.toggleStory(storyId, ids, add) },
                onCreate: { title in
                    self.sheet = nil
                    actions.createStoryWith(title, ids)
                }
            )
        case .tag(let id):
            TagSheet(
                itemIds: [id],
                stories: stories,
                onToggle: { tag, add in actions.toggleTag(id, tag, add) },
                onCreate: { tag in actions.toggleTag(id, tag, true) }
            )
        }
    }

    private var canRead: Bool { library.access == .full || library.access == .limited }

    /// One video plays at a time, and only while the feed is what shows.
    private var canPlay: Bool { tab == .moments && onScreen && !viewerOpen && scenePhase == .active }

    /// Stories shows only stories; every photo is in the Moments tab, as the feed.
    private var storyEntries: [TimelineEntryStoryEntry] {
        stories.timeline.compactMap { $0 as? TimelineEntryStoryEntry }
    }

    /// Where videos play: below the top bar and above the bottom controls.
    private func viewport(_ geometry: GeometryProxy) -> ClosedRange<CGFloat> {
        let frame = geometry.frame(in: .global)
        return (frame.minY + 100)...max(frame.minY + 101, frame.maxY - 60)
    }

    private var feedActions: FeedActions {
        FeedActions(
            openItem: actions.openItem,
            saveTrip: { trip in
                newTitle = trip.place ?? ""
                tripToSave = trip.key
            },
            dismissTrip: actions.dismissTrip,
            write: { sheet = .write($0.id) },
            crop: actions.crop,
            addToStory: { sheet = .addToStory([$0.id]) },
            tag: { sheet = .tag($0.id) },
            takeOutOfSet: { actions.takeOutOfSet($0.id) },
            turnSet: actions.turnSet,
            showOnRow: { section, source in
                let count = Int(section.hidden[source]?.intValue ?? 0)
                actions.showOnRow(section.key, source)
                let toast = RevealToast(sectionKey: section.key, source: source, count: count, days: shortDays(section.span))
                withAnimation { revealToast = toast }
                Task {
                    try? await Task.sleep(for: .seconds(undoSeconds))
                    if revealToast == toast { withAnimation { revealToast = nil } }
                }
            },
            hideOnRow: { section, source in
                actions.hideOnRow(section.key, source)
                if revealToast?.sectionKey == section.key && revealToast?.source == source { withAnimation { revealToast = nil } }
            },
            openRevealed: actions.openRevealed,
            addAllToStory: { sheet = .addToStory($0) },
            alwaysShow: { section, source in
                actions.alwaysShow(section.days, source)
                actions.hideOnRow(section.key, source)
                if revealToast?.sectionKey == section.key { withAnimation { revealToast = nil } }
            }
        )
    }

    /// The photos of the picked sets and photos, in the feed's order.
    private func merge() {
        let ids = rows.flatMap { row -> [String] in
            guard case .photo(_, let block) = row, selection.contains(block.key) else { return [] }
            return block.items.map(\.id)
        }
        selection = []
        actions.merge(ids)
    }

    private var topBar: some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack {
                Text("Journal")
                    .font(.system(size: 30, weight: .bold))
                    .foregroundStyle(LibraryColors.onBackground)
                    .accessibilityAddTraits(.isHeader)
                Spacer()
                if health == .offline { OfflinePill() }
                Button(action: actions.openTags) {
                    Image(systemName: "tag")
                        .font(.system(size: 18, weight: .semibold))
                        .foregroundStyle(LibraryColors.onBackground)
                        .frame(width: 44, height: 44)
                }
                .accessibilityLabel("Tags")
                Menu {
                    Button(action: actions.openAllPhotos) {
                        Label("All photos", systemImage: "square.grid.2x2")
                    }
                    Button(action: actions.openSettings) {
                        Label("Settings", systemImage: "gearshape")
                    }
                } label: {
                    Image(systemName: "ellipsis")
                        .font(.system(size: 18, weight: .semibold))
                        .foregroundStyle(LibraryColors.onBackground)
                        .frame(width: 44, height: 44)
                }
                .accessibilityLabel("More")
            }
            .padding(.leading, 16)
            .padding(.trailing, 8)
            if tab == .moments {
                FeedSourceBar(feed: stories.feed, onToggle: actions.toggleSource)
            } else if tab == .explore {
                ExploreBar(view: $exploreView, onMap: actions.explore.openMap)
            }
        }
        .padding(.bottom, 24)
        .background(LibraryColors.topFade.ignoresSafeArea())
    }
}

/// A row's hidden photos just shown, for "Showing 6 from WhatsApp on Oct 4 · Hide".
private struct RevealToast: Equatable {
    let sectionKey: String
    let source: MediaSource
    let count: Int
    /// "Oct 4", or "Oct 3 – Oct 4" for a row over two days.
    let days: String
}

/// "Oct 4", or "Oct 3 – Oct 4": a row's days, short.
private func shortDays(_ span: Moment) -> String {
    let formatter = DateFormatter()
    formatter.setLocalizedDateFormatFromTemplate("MMMd")
    let newest = formatter.string(from: span.newestDay.date)
    if span.oldestDay == span.newestDay { return newest }
    return String(localized: "\(formatter.string(from: span.oldestDay.date)) – \(newest)")
}

/// What a photo's long-press menu in Moments opened, by the photo's id (or a band's photos, to add to a story).
private enum FeedSheet: Identifiable {
    case write(String)
    case addToStory([String])
    case tag(String)

    var id: String {
        switch self {
        case .write(let id): return "write-\(id)"
        case .addToStory(let ids): return "story-\(ids.joined(separator: ","))"
        case .tag(let id): return "tag-\(id)"
        }
    }
}

/// The server refused this device (401) or there is no identity: offer to register again.
private struct RegisterAgainCard: View {
    let onRegister: () -> Void

    var body: some View {
        Card {
            Text("This device isn't registered with the server").font(.subheadline.weight(.semibold))
            Text("The server doesn't accept this device's certificate anymore. Register it again with a new enrollment code.")
                .font(.subheadline)
                .foregroundStyle(LibraryColors.onInverseMuted)
            Button("Register again", action: onRegister)
                .buttonStyle(.borderedProminent)
                .tint(LibraryColors.error)
        }
    }
}

private struct NoAccessCard: View {
    let onAllow: () -> Void

    var body: some View {
        Card {
            Text("Journal can't see your photos").font(.subheadline.weight(.semibold))
            Text("Allow access to see your photos and videos here.")
                .font(.subheadline)
                .foregroundStyle(LibraryColors.onInverseMuted)
            Button("Allow access", action: onAllow)
                .buttonStyle(.borderedProminent)
                .tint(LibraryColors.accent)
        }
    }
}

/// iOS lets the user change the selection from the system sheet.
private struct LimitedBanner: View {
    let count: Int

    var body: some View {
        HStack {
            Text("Journal can see \(count) photos and videos")
                .font(.subheadline)
                .foregroundStyle(LibraryColors.onBackground)
            Spacer()
            Button("Change") { presentLimitedPicker() }
                .font(.subheadline.weight(.semibold))
                .foregroundStyle(LibraryColors.warning)
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 12)
        .background(LibraryColors.raised, in: RoundedRectangle(cornerRadius: 14))
        .padding(.horizontal, 16)
        .padding(.vertical, 8)
    }

    private func presentLimitedPicker() {
        guard let root = UIApplication.shared.connectedScenes
            .compactMap({ ($0 as? UIWindowScene)?.keyWindow?.rootViewController }).first else { return }
        PHPhotoLibrary.shared().presentLimitedLibraryPicker(from: root)
    }
}

private struct Card<Content: View>: View {
    @ViewBuilder let content: Content

    var body: some View {
        VStack(alignment: .leading, spacing: 8) { content }
            .foregroundStyle(LibraryColors.onInverse)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(16)
            .background(LibraryColors.inverse, in: RoundedRectangle(cornerRadius: 16))
            .padding(.horizontal, 16)
            .padding(.vertical, 8)
    }
}

private struct Message: View {
    let text: LocalizedStringKey

    var body: some View {
        Text(text)
            .foregroundStyle(LibraryColors.muted)
            .padding(.horizontal, 24)
            .padding(.vertical, 32)
    }
}

