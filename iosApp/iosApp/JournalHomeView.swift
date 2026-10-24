import PhotosUI
import SwiftUI
import SharedLogic

/// What the home screen can ask its parent to do.
struct HomeActions {
    /// A photo of the Moments tab; the viewer swipes through its row.
    var openItem: (FeedSection, MediaItem) -> Void
    /// A photo of the Storyline tab; the viewer swipes through the storyline.
    var openStorylineItem: (MediaItem) -> Void
    var toggleSource: (MediaSource) -> Void
    /// Moments: the photos of the picked sets and photos become one set; a photo leaves its set.
    var merge: (_ itemIds: [String]) -> Void
    var takeOutOfSet: (_ itemId: String) -> Void
    var turnSet: (_ itemIds: [String], _ itemId: String) -> Void
    /// From a photo's long-press menu in Moments.
    var setWriting: (_ itemId: String, _ title: String, _ text: String) -> Void
    var restoreWriting: (_ itemId: String, _ version: NoteVersion) -> Void
    /// Crop in a photo's long-press menu: the crop screen opens here, on the Moments crop.
    var crop: (MediaItem) -> Void = { _ in }
    /// "Remove from storyline" (false), or "Add to storyline" (true) for a photo with writing.
    var setInStoryline: (_ itemId: String, _ inStoryline: Bool) -> Void
    var toggleTag: (_ itemId: String, _ tag: String, _ add: Bool) -> Void
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
    /// Search, from the 🔍 beside the tabs.
    var search = SearchActions()
}

enum HomeTab { case storyline, moments, explore }

/// The home screen: in the Storyline tab every photo the user wrote about, newest first, and in the Moments tab every
/// photo as a feed that groups itself (by day or run of photos, then time, place, source and looks), and in the Explore
/// tab every photo by place and year. The 🔍 beside the tabs opens search over them, until its ✕. See
/// docs/screens/journal-home.md, moments.md, explore.md and search.md.
struct JournalHomeView: View {
    let library: LibraryState
    let stories: StoriesState
    let health: ServerHealth
    /// Videos of the feed stop while the viewer is over it.
    let viewerOpen: Bool
    /// "Show in Moments" from the viewer: the Moments tab, landed on that photo.
    var showInMoments: PhotoRequest? = nil
    /// The backup run, for the bottom pill.
    let backupRunning: BackupProgress?
    let actions: HomeActions
    /// The last search and what it found; nil before the first.
    var search: SearchState? = nil
    /// Explore's People, when the server has face search; nil: no People pill.
    var people: PeopleState? = nil

    /// The tab and Explore view it opens on (the UI lab opens others).
    /// Moments on start (asked Oct 24, 2026).
    @State var tab = HomeTab.moments
    @State var exploreView = ExploreView.places
    /// Search covers the tab until its ✕, and the tab is as it was (the UI lab opens on it).
    @State var searching = false
    /// The Explore tab's towns and years, worked out off the main thread while it shows.
    @State private var explore: ExploreSummaries?
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
    /// The month pill grown into the ruler; a tap or drag anywhere else folds it.
    @State private var timelineOpen = false
    /// The Moments row at the top of the screen, by its key: the month the pill says.
    @State private var topSection: String?
    /// The pill, ruler or day strip asked for a row.
    @State private var jump: RowJump?
    /// Each feed row's id to its place in the feed and its section's key, worked out once per feed.
    @State private var rowSections: [String: (index: Int, section: String)] = [:]
    /// When the pill last jumped: the rows reported just after are where the feed was, not where it went.
    @State private var jumpedAt = Date.distantPast
    /// The top bar's controls sliding away, which follows the scroll every frame.
    @State private var scroll = HomeScroll()
    /// The tabs' height: how far the month pill goes down to take their place while they are slid away.
    @State private var tabsHeight: CGFloat = 0
    @Environment(\.scenePhase) private var scenePhase

    var body: some View {
        let moments = tab == .moments
        // Room for the title, and for the bar under it when there is one.
        let barUnderTitle = tab == .explore || (moments && stories.shownSources.showFilterBar)
        GeometryReader { geometry in
            ScrollView {
                if searching {
                    SearchResultsView(
                        search: shownSearch, places: stories.places, width: geometry.size.width, actions: actions.search,
                        faces: Array((people?.list.people ?? []).sorted { $0.files > $1.files }.prefix(16))
                    )
                        .padding(.top, 80)
                        .padding(.bottom, 150)
                } else {
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
                    if tab == .explore && exploreView == .people {
                        PeopleListView(people: people ?? PeopleState.companion.NONE, actions: actions.explore)
                    } else if tab == .explore {
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
                        if stories.storyline.isEmpty && !library.days.isEmpty {
                            Message(text: "Nothing here yet. Open a photo and tap Write: what you write shows here.")
                        }
                        StorylineView(
                            items: stories.storyline,
                            note: { stories.note(itemId: $0) },
                            width: geometry.size.width,
                            openItem: actions.openStorylineItem,
                            actions: feedActions,
                            playback: playback
                        )
                    }
                }
                // Its rows' visibility says which month is at the top (onScrollTargetVisibilityChange).
                .scrollTargetLayout()
                .padding(.top, barUnderTitle ? 118 : 72)
                .padding(.bottom, 150)
                // Swiping in the viewer keeps its photo in view here, and a set turns to it.
                .followsViewer { id in
                    switch tab {
                    case .moments: return rows.place(of: id)
                    case .storyline: return stories.storyline.contains { $0.id == id } ? PhotoPlace(section: id, tile: ZoomID.item(id)) : nil
                    case .explore: return nil
                    }
                }
                .landsOn(showInMoments) { rows.place(of: $0) }
                // The row lands just under the title and the sources bar.
                .jumps(to: jump, anchor: UnitPoint(x: 0.5, y: 118 / max(geometry.size.height, 1)))
                }
            }
            .scrollIndicators(.hidden)
            .onScrollGeometryChange(for: CGFloat.self, of: { $0.contentOffset.y + $0.contentInsets.top }) { old, new in
                scroll.scrolled(by: new - old, atTop: new <= 1)
            }
            .onScrollTargetVisibilityChange(idType: String.self, threshold: 0.2) { ids in
                // Reported in no set order: the top one is the first in the feed.
                guard moments, Date().timeIntervalSince(jumpedAt) > 0.8,
                      let key = ids.compactMap({ rowSections[$0] }).min(by: { $0.index < $1.index })?.section else { return }
                if key != topSection { topSection = key }
            }
            .onAppear { playback.viewport = viewport(geometry) }
            .onChange(of: geometry.size) { playback.viewport = viewport(geometry) }
        }
        .onChange(of: stories.feed, initial: true) {
            rows = stories.feed.rows
            rowSections = rows.sectionKeys
        }
        .onChange(of: tab) {
            timelineOpen = false
            scroll.showTitle()
        }
        .onChange(of: searching) {
            timelineOpen = false
            scroll.showTitle()
        }
        .onChange(of: selection.isEmpty) {
            timelineOpen = false
            scroll.showTitle()
        }
        .onChange(of: timelineOpen) { if timelineOpen { scroll.showTitle() } }
        .onChange(of: showInMoments) { _, request in
            guard request != nil else { return }
            selection = []
            searching = false
            tab = .moments
        }
        .task(id: tab == .explore ? ExploreInput(library, stories) : nil) {
            if tab == .explore { explore = await ExploreSummaries.of(library, stories) }
        }
        .onChange(of: canPlay, initial: true) { playback.enabled = canPlay }
        .onAppear { onScreen = true }
        .onDisappear { onScreen = false }
        .sheet(item: $sheet, content: sheetContent)
        .background(LibraryColors.background.ignoresSafeArea())
        .overlay(alignment: .top) {
            if searching { SearchTitleBar() } else { topBar }
        }
        .overlay {
            // Over the feed while the ruler is open: a tap or a drag folds it and does nothing else.
            if timelineOpen {
                Color.clear
                    .contentShape(Rectangle())
                    .ignoresSafeArea()
                    .onTapGesture { timelineOpen = false }
                    .gesture(DragGesture(minimumDistance: 10).onChanged { _ in timelineOpen = false })
                    .accessibilityHidden(true)
            }
        }
        .overlay(alignment: .bottom) {
            if searching {
                BottomControls(summary: nil) {
                    SearchFieldBar(search: shownSearch, actions: actions.search) {
                        withAnimation { searching = false }
                    }
                }
            } else {
                bottomControls
            }
        }
    }

    /// Nothing searched yet when the parent has no search.
    private var shownSearch: SearchState {
        search ?? SearchState.Companion.shared.NONE
    }

    private var bottomControls: some View {
        BottomControls(
            summary: selection.isEmpty && !timelineOpen ? backupSummary(running: backupRunning, notBackedUp: Int(library.notBackedUpCount)) : nil,
            summaryHidden: scroll.bottomHidden
        ) {
            if !selection.isEmpty && tab == .moments {
                FeedSelectionBar(count: selection.count, onCancel: { selection = [] }, onMerge: merge)
            } else {
                if let toast = revealToast, tab == .moments {
                    UndoBar(
                        message: "Showing \(toast.count) from \(toast.source.title) on \(toast.days)",
                        actionLabel: "Hide",
                        onUndo: {
                            actions.hideOnRow(toast.sectionKey, toast.source)
                            withAnimation { revealToast = nil }
                        },
                        onDismiss: { withAnimation { revealToast = nil } }
                    )
                }
                if tab == .moments && revealToast == nil && !stories.feed.timeline.isEmpty {
                    TimelineBar(
                        timeline: stories.feed.timeline,
                        current: topSection.flatMap { stories.feed.timeline.monthOf(sectionKey: $0)?.intValue },
                        day: topSection.flatMap { stories.feed.section(key: $0)?.span.newestDay },
                        open: $timelineOpen
                    ) { day in
                        // A jump doesn't report the rows it scrolled past, so the pill learns its month here.
                        topSection = day.sectionKey
                        jumpedAt = Date()
                        jump = RowJump(rowId: "heading-\(day.sectionKey)")
                    }
                    // Slides down with the tabs into their place at the bottom, and back up with them.
                    .offset(y: scroll.bottomHidden ? tabsHeight + 10 : 0)
                }
                // Slides down off the screen while the list goes down, back on the way up; it keeps its place for the
                // month pill to take.
                HStack(spacing: 10) {
                    SegmentedBar(options: [(HomeTab.moments, "Moments"), (HomeTab.storyline, "Storyline"), (HomeTab.explore, "Explore")], selection: tabBinding)
                    SearchButton {
                        selection = []
                        revealToast = nil
                        withAnimation { searching = true }
                    }
                }
                .onGeometryChange(for: CGFloat.self) { $0.size.height } action: { tabsHeight = $0 }
                .offset(y: scroll.bottomHidden ? 140 : 0)
                .allowsHitTesting(!scroll.bottomHidden)
            }
        }
    }

    /// Changing tabs stops picking.
    private var tabBinding: Binding<HomeTab> {
        Binding(get: { tab }, set: { newTab in
            selection = []
            tab = newTab
        })
    }

    @ViewBuilder
    private func sheetContent(_ sheet: FeedSheet) -> some View {
        switch sheet {
        case .write(let id):
            NoteEditor(
                note: stories.note(itemId: id),
                onSave: { actions.setWriting(id, $0, $1) },
                onRestore: { actions.restoreWriting(id, $0) }
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
    private var canPlay: Bool { tab == .moments && !searching && onScreen && !viewerOpen && scenePhase == .active }

    /// Where videos play: below the top bar and above the bottom controls.
    private func viewport(_ geometry: GeometryProxy) -> ClosedRange<CGFloat> {
        let frame = geometry.frame(in: .global)
        return (frame.minY + 100)...max(frame.minY + 101, frame.maxY - 60)
    }

    private var feedActions: FeedActions {
        FeedActions(
            openItem: actions.openItem,
            write: { sheet = .write($0.id) },
            crop: actions.crop,
            setInStoryline: { actions.setInStoryline($0.id, $1) },
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
        // The title row and the bar under it (the filter bar, Explore's) slide away together.
        CollapsingTitle(scroll: scroll) {
            VStack(alignment: .leading, spacing: 4) {
                titleRow
                if tab == .moments {
                    if stories.shownSources.showFilterBar { FeedSourceBar(feed: stories.feed, onToggle: actions.toggleSource) }
                } else if tab == .explore {
                    ExploreBar(view: $exploreView, people: people != nil, onMap: actions.explore.openMap)
                }
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        // No fade behind it (removed Oct 24, 2026): its controls float over the photos.
        .padding(.bottom, 8)
    }

    /// "Journal", the offline pill, Tags and Settings.
    private var titleRow: some View {
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
            Button(action: actions.openSettings) {
                Image(systemName: "gearshape")
                    .font(.system(size: 18, weight: .semibold))
                    .foregroundStyle(LibraryColors.onBackground)
                    .frame(width: 44, height: 44)
            }
            .accessibilityLabel("Settings")
        }
        .padding(.leading, 16)
        .padding(.trailing, 8)
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

/// What a photo's long-press menu opened, by the photo's id.
private enum FeedSheet: Identifiable {
    case write(String)
    case tag(String)

    var id: String {
        switch self {
        case .write(let id): return "write-\(id)"
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

/// How long a bar like "Showing 6 from WhatsApp · Hide" stays, unless closed with its ✕ first.
let undoSeconds = 6

/// A message with an action and ✕ at the bottom, for a few seconds ("Showing 6 from WhatsApp on Oct 4 · Hide").
struct UndoBar: View {
    let message: LocalizedStringKey
    var actionLabel: LocalizedStringKey = "Undo"
    let onUndo: () -> Void
    let onDismiss: () -> Void

    var body: some View {
        HStack(spacing: 4) {
            Text(message).font(.subheadline)
            Spacer()
            Button(actionLabel, action: onUndo)
                .font(.subheadline.weight(.bold))
                .foregroundStyle(LibraryColors.accent)
            Button(action: onDismiss) {
                Image(systemName: "xmark").font(.system(size: 14, weight: .semibold)).frame(width: 36, height: 36)
            }
            .accessibilityLabel("Dismiss")
        }
        .foregroundStyle(LibraryColors.onInverse)
        .padding(.leading, 16)
        .padding(.trailing, 6)
        .frame(height: 52)
        .background(LibraryColors.inverse, in: RoundedRectangle(cornerRadius: 14))
        .shadow(color: .black.opacity(0.4), radius: 12, y: 6)
        .padding(.horizontal, 16)
        .padding(.bottom, 8)
        .transition(.move(edge: .bottom).combined(with: .opacity))
    }
}
