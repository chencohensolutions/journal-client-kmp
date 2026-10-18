import SwiftUI
import SharedLogic

/// What the story page can ask its parent to do. `acceptSuggestion` and `groupTiles` return the group's id;
/// `groupTiles` takes tile keys (photos and groups) and merges them into one group, and `restore` undoes that.
struct StoryPageActions {
    var openItem: (MediaItem) -> Void
    var openGroup: (GroupView) -> Void
    var addDay: (LocalDay) -> Void
    var rename: (String) -> Void
    var delete: () -> Void
    var setIntro: (String) -> Void
    var acceptSuggestion: (String) -> String?
    var groupTiles: ([String]) -> String?
    var restore: (Story) -> Void
    var dismissSuggestion: (String) -> Void
    var ungroup: (String) -> Void
    var removeItems: ([String]) -> Void
}

/// One story: its title, dates and story, burst suggestions, then its photos and groups day by day, oldest first.
/// Long-press a photo or group to select tiles and group or merge them. See docs/screens/story.md.
struct StoryPageView: View {
    let story: StoryView?
    let neighborDays: [DayGroup]
    let health: ServerHealth
    let actions: StoryPageActions

    @Environment(\.dismiss) private var dismiss
    @State private var renaming = false
    @State private var newTitle = ""
    @State private var confirmingDelete = false
    @State private var editingIntro = false
    @State private var selection: Set<String>?
    /// The story as it was before grouping, to put back on Undo.
    @State private var undo: (groupId: String, count: Int, before: Story)?

    var body: some View {
        Group {
            if let story {
                content(story)
            } else {
                // Deleted, here or on another screen.
                Color.clear.onAppear { dismiss() }
            }
        }
        .background(LibraryColors.background.ignoresSafeArea())
        .navigationBarTitleDisplayMode(.inline)
        .navigationTitle(selection.map { "\($0.count) selected" } ?? "")
        .navigationBarBackButtonHidden(selection != nil)
        .toolbarBackground(LibraryColors.background, for: .navigationBar)
        .toolbar { toolbar }
        .safeAreaInset(edge: .bottom) { bottomBar }
        .alert("Rename story", isPresented: $renaming) {
            TextField("Title", text: $newTitle)
            Button("Save") { actions.rename(newTitle) }
                .disabled(newTitle.trimmingCharacters(in: .whitespaces).isEmpty)
            Button("Cancel", role: .cancel) {}
        }
        .confirmationDialog("Delete \"\(story?.title ?? "")\"?", isPresented: $confirmingDelete, titleVisibility: .visible) {
            Button("Delete", role: .destructive, action: actions.delete)
        } message: {
            Text("The story and its title go away. Its photos and videos stay on this device.")
        }
        .sheet(isPresented: $editingIntro) {
            WritingEditor(title: story?.title ?? "", initial: story?.intro ?? "", onSave: actions.setIntro)
        }
    }

    @ToolbarContentBuilder
    private var toolbar: some ToolbarContent {
        if selection != nil {
            ToolbarItem(placement: .topBarLeading) {
                Button { selection = nil } label: { Image(systemName: "xmark") }
                    .accessibilityLabel("Cancel selection")
            }
        } else {
            if health == .offline {
                ToolbarItem(placement: .topBarTrailing) { OfflinePill() }
            }
            ToolbarItem(placement: .topBarTrailing) {
                Menu {
                    Button {
                        newTitle = story?.title ?? ""
                        renaming = true
                    } label: { Label("Rename", systemImage: "pencil") }
                    Button(role: .destructive) { confirmingDelete = true } label: {
                        Label("Delete story", systemImage: "trash")
                    }
                } label: {
                    Image(systemName: "ellipsis").foregroundStyle(LibraryColors.onBackground)
                }
                .accessibilityLabel("More")
            }
        }
    }

    /// Group / Remove while selecting; otherwise the "Grouped · Undo" message after grouping.
    @ViewBuilder
    private var bottomBar: some View {
        if let selection, let story {
            let tiles = story.tiles.filter { selection.contains($0.key) }
            let itemIds = tiles.flatMap { tile -> [String] in
                if let group = tile as? StoryTileGroup { return group.group.items.map(\.id) }
                return (tile as? StoryTileMedia).map { [$0.item.id] } ?? []
            }
            let merging = tiles.contains { $0 is StoryTileGroup }
            HStack(spacing: 8) {
                Button {
                    let before = story.story
                    self.selection = nil
                    showUndo(actions.groupTiles(Array(selection)), count: itemIds.count, before: before)
                } label: {
                    Label(merging ? LocalizedStringKey("Merge") : LocalizedStringKey("Group"), systemImage: "square.on.square")
                        .font(.headline)
                        .frame(maxWidth: .infinity, minHeight: 52)
                        .foregroundStyle(LibraryColors.onInverse)
                        .background(LibraryColors.inverse.opacity(tiles.count >= 2 ? 1 : 0.4), in: RoundedRectangle(cornerRadius: 14))
                }
                .disabled(tiles.count < 2)
                Button {
                    actions.removeItems(itemIds)
                    self.selection = nil
                } label: {
                    Label("Remove", systemImage: "minus")
                        .font(.headline)
                        .frame(maxWidth: .infinity, minHeight: 52)
                        .foregroundStyle(LibraryColors.warning)
                }
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 12)
            .background(LibraryColors.raised.ignoresSafeArea())
        } else if let undo {
            HStack {
                Text("Grouped \(undo.count) photos").font(.subheadline)
                Spacer()
                Button("Undo") {
                    actions.restore(undo.before)
                    self.undo = nil
                }
                .font(.subheadline.weight(.bold))
                .foregroundStyle(LibraryColors.accent)
            }
            .foregroundStyle(LibraryColors.onInverse)
            .padding(.horizontal, 16)
            .frame(height: 52)
            .background(LibraryColors.inverse, in: RoundedRectangle(cornerRadius: 14))
            .shadow(color: .black.opacity(0.4), radius: 12, y: 6)
            .padding(.horizontal, 16)
            .padding(.bottom, 8)
            .transition(.move(edge: .bottom).combined(with: .opacity))
        }
    }

    private func showUndo(_ groupId: String?, count: Int, before: Story) {
        guard let groupId else { return }
        withAnimation { undo = (groupId, count, before) }
        Task {
            try? await Task.sleep(for: .seconds(5))
            if undo?.groupId == groupId { withAnimation { undo = nil } }
        }
    }

    private func content(_ story: StoryView) -> some View {
        GeometryReader { geometry in
            ScrollView {
                LazyVStack(alignment: .leading, spacing: 2) {
                    header(story)
                    WritingBlock(text: story.intro, emptyPrompt: "Write about this story…") { editingIntro = true }
                    if selection == nil {
                        if !neighborDays.isEmpty { neighbors }
                        ForEach(story.suggestions, id: \.key) { suggestion in
                            BurstSuggestionCard(
                                suggestion: suggestion,
                                onGroup: {
                                    let before = story.story
                                    showUndo(actions.acceptSuggestion(suggestion.key), count: Int(suggestion.count), before: before)
                                },
                                onDismiss: { actions.dismissSuggestion(suggestion.key) }
                            )
                        }
                    }
                    if story.missingCount > 0 {
                        note("\(Int(story.missingCount)) photos of this story aren't on this device.")
                    }
                    if story.items.isEmpty {
                        note("None of this story's photos are on this device.")
                    }
                    StoryGrid(
                        days: story.tileDays,
                        label: { label(for: $0, in: story) },
                        width: geometry.size.width,
                        selection: selection,
                        onOpenItem: actions.openItem,
                        onOpenGroup: actions.openGroup,
                        onToggle: { key in
                            if selection?.contains(key) == true { selection?.remove(key) } else { selection?.insert(key) }
                        },
                        onLongPress: { key in
                            if selection == nil { selection = [] }
                            selection?.insert(key)
                        }
                    )
                    .padding(.top, 8)
                }
                .padding(.bottom, 32)
            }
            .scrollIndicators(.hidden)
        }
    }

    private func header(_ story: StoryView) -> some View {
        HStack(spacing: 14) {
            if let first = story.firstDay { DateBlock(day: first) }
            VStack(alignment: .leading, spacing: 3) {
                Text(story.title)
                    .font(.system(size: 28, weight: .bold))
                    .foregroundStyle(LibraryColors.onBackground)
                    .accessibilityAddTraits(.isHeader)
                let groups = story.groups.count
                Text(groups > 0 ? "\(story.summary) · \(String(localized: "\(groups) groups"))" : story.summary)
                    .font(.subheadline)
                    .foregroundStyle(LibraryColors.muted)
            }
        }
        .padding(.horizontal, 16)
        .padding(.bottom, 10)
    }

    private var neighbors: some View {
        ScrollView(.horizontal) {
            HStack(spacing: 8) {
                ForEach(neighborDays, id: \.day) { day in
                    Button { actions.addDay(day.day) } label: {
                        Label("Add \(day.day.label) · \(day.items.count) photos", systemImage: "plus")
                            .font(.footnote.weight(.semibold))
                            .foregroundStyle(LibraryColors.onBackground)
                            .padding(.horizontal, 14)
                            .frame(height: 40)
                            .overlay(Capsule().stroke(LibraryColors.onBackground.opacity(0.28)))
                    }
                }
            }
            .padding(.horizontal, 16)
        }
        .scrollIndicators(.hidden)
        .padding(.vertical, 6)
    }

    /// "Day 2 · Fri, Oct 9" in trip-like stories longer than a day; not in stories spanning months.
    private func label(for day: StoryDay, in story: StoryView) -> String {
        guard story.isShortSpan, story.dayCount > 1, let first = story.firstDay else { return day.day.label }
        let number = day.day.toEpochDay() - first.toEpochDay() + 1
        return String(localized: "Day \(number) · \(day.day.label)")
    }

    private func note(_ text: LocalizedStringKey) -> some View {
        Text(text)
            .font(.subheadline)
            .foregroundStyle(LibraryColors.muted)
            .padding(.horizontal, 16)
            .padding(.vertical, 8)
    }
}
