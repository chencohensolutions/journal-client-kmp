import PhotosUI
import SwiftUI
import SharedLogic

/// What the home screen can ask its parent to do.
struct HomeActions {
    var openItem: (Moment, MediaItem) -> Void
    var openStory: (String) -> Void
    var createStory: (_ title: String, _ momentKey: String) -> Void
    var openAllPhotos: () -> Void
    var openSettings: () -> Void
    var openTags: () -> Void
    var allowAccess: () -> Void
    var registerAgain: () -> Void
}

/// The home screen: the journal as a timeline, newest first: stories, with the photos in no story between them as
/// "unsorted" moments (a day, or a run of photos; see the grouping setting). See docs/screens/journal-home.md.
struct JournalHomeView: View {
    let library: LibraryState
    let stories: StoriesState
    let health: ServerHealth
    let actions: HomeActions

    private enum Tab { case journal, unsorted }
    @State private var tab = Tab.journal
    /// The key of the moment whose "+ Story" was tapped.
    @State private var newStoryMoment: String?
    @State private var newTitle = ""

    var body: some View {
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
                    ForEach(entries, id: \.id) { row in
                        switch row.entry {
                        case let storyEntry as TimelineEntryStoryEntry:
                            StoryCardView(story: storyEntry.story, width: geometry.size.width)
                                .onTapGesture { actions.openStory(storyEntry.story.id) }
                        case let momentEntry as TimelineEntryMomentEntry:
                            UnsortedMoment(
                                moment: momentEntry.moment,
                                onOpen: { actions.openItem(momentEntry.moment, $0) },
                                onNewStory: { newStoryMoment = momentEntry.moment.key }
                            )
                        default:
                            EmptyView()
                        }
                    }
                }
                .padding(.top, 72)
                .padding(.bottom, 150)
            }
            .scrollIndicators(.hidden)
        }
        .alert("New story", isPresented: Binding(get: { newStoryMoment != nil }, set: { if !$0 { newStoryMoment = nil } })) {
            TextField("Japan trip, Noa's birthday…", text: $newTitle)
            Button("Create") {
                if let key = newStoryMoment { actions.createStory(newTitle, key) }
                newTitle = ""
            }
            .disabled(newTitle.trimmingCharacters(in: .whitespaces).isEmpty)
            Button("Cancel", role: .cancel) { newTitle = "" }
        }
        .background(LibraryColors.background.ignoresSafeArea())
        .overlay(alignment: .top) { topBar }
        .overlay(alignment: .bottom) {
            BottomControls(notBackedUp: Int(library.notBackedUpCount)) {
                SegmentedBar(
                    options: [(Tab.journal, "Journal"), (Tab.unsorted, "Unsorted \(Int(stories.unsortedCount))")],
                    selection: $tab
                )
            }
        }
    }

    private var canRead: Bool { library.access == .full || library.access == .limited }

    private var entries: [TimelineRow] {
        let entries = tab == .unsorted ? stories.timeline.filter { $0 is TimelineEntryMomentEntry } : stories.timeline
        return entries.map(TimelineRow.init)
    }

    private var topBar: some View {
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
        .padding(.bottom, 24)
        .background(LibraryColors.topFade.ignoresSafeArea())
    }
}

/// Photos that are in no story, taken on one day or close together: a heading, "+ Story", and a row of small photos.
private struct UnsortedMoment: View {
    let moment: Moment
    let onOpen: (MediaItem) -> Void
    let onNewStory: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack {
                VStack(alignment: .leading, spacing: 1) {
                    Text(moment.label)
                        .font(.system(size: 15, weight: .bold))
                        .foregroundStyle(LibraryColors.onBackground)
                        .accessibilityAddTraits(.isHeader)
                    Text("\(moment.items.count) unsorted")
                        .font(.footnote)
                        .foregroundStyle(LibraryColors.muted)
                }
                Spacer()
                Button(action: onNewStory) {
                    Label("Story", systemImage: "plus")
                        .font(.footnote.weight(.semibold))
                        .foregroundStyle(LibraryColors.onBackground)
                        .padding(.horizontal, 12)
                        .frame(height: 34)
                        .overlay(Capsule().stroke(LibraryColors.onBackground.opacity(0.28)))
                }
                .accessibilityLabel("New story from \(moment.label)")
            }
            .padding(.horizontal, 16)
            ScrollView(.horizontal) {
                LazyHStack(spacing: 2) {
                    ForEach(moment.items, id: \.id) { item in
                        MediaTileView(item: item, badgeSize: 18)
                            .frame(width: 76, height: 76)
                            .clipShape(RoundedRectangle(cornerRadius: 4))
                            .onTapGesture { onOpen(item) }
                    }
                }
                .padding(.horizontal, 16)
            }
            .scrollIndicators(.hidden)
        }
        .padding(.top, 14)
        .padding(.bottom, 16)
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

/// A timeline entry with a stable id for `ForEach`.
private struct TimelineRow {
    let entry: TimelineEntry

    var id: String {
        switch entry {
        case let story as TimelineEntryStoryEntry: return "story-\(story.story.id)"
        case let moment as TimelineEntryMomentEntry: return "moment-\(moment.moment.key)"
        default: return ""
        }
    }
}

extension Moment {
    /// "Today", or by breaks between photos "Today · 9:14 – 21:40" and "Sat, Oct 4 – Mon, Oct 6" for one that spans
    /// days.
    var label: String {
        let day = newestDay.label
        if mode == .byDay { return day }
        if oldestDay != newestDay { return String(localized: "\(oldestDay.label) – \(day)") }
        let start = Moment.time(startMillis), end = Moment.time(endMillis)
        return start == end ? String(localized: "\(day) · \(start)") : String(localized: "\(day) · \(start) – \(end)")
    }

    private static func time(_ millis: Int64) -> String {
        Date(timeIntervalSince1970: TimeInterval(millis) / 1000).formatted(date: .omitted, time: .shortened)
    }
}
