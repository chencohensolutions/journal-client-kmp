import SwiftUI
import SharedLogic

/// What the story page can ask its parent to do. `acceptSuggestion`, `groupTiles` and `mergeTiles` return the group's
/// id; `groupTiles` takes tile keys (photos and groups) and puts them in a new group (groups nest), `mergeTiles` joins
/// them into the first picked group, and `restore` undoes either.
struct StoryPageActions {
    var openItem: (MediaItem) -> Void
    var openGroup: (GroupView) -> Void
    var addDay: (LocalDay) -> Void
    var rename: (String) -> Void
    var delete: () -> Void
    var setIntro: (String) -> Void
    var acceptSuggestion: (String) -> String?
    var groupTiles: ([String]) -> String?
    var mergeTiles: ([String]) -> String?
    var restore: (Story) -> Void
    var dismissSuggestion: (String) -> Void
    var ungroup: (String) -> Void
    var removeItems: ([String]) -> Void
    /// Removes every group and brings suggestions back; `restore` undoes it.
    var resetGroups: () -> Void
}

/// One story: its title, dates and story, burst suggestions, then its photos and groups day by day, oldest first.
/// Long-press a photo or group to select tiles and group them (groups nest) or merge them. See docs/screens/story.md.
struct StoryPageView: View {
    let story: StoryView?
    let neighborDays: [DayGroup]
    let health: ServerHealth
    let actions: StoryPageActions

    @Environment(\.dismiss) private var dismiss
    @State private var renaming = false
    @State private var newTitle = ""
    @State private var confirmingDelete = false
    @State private var confirmingGroupsReset = false
    /// The story as it was before Reset groups, to put back on Undo.
    @State private var resetUndo: Story?
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
            TextField("Title (optional)", text: $newTitle)
            // Titles are optional: a blank one removes it.
            Button("Save") { actions.rename(newTitle) }
            Button("Cancel", role: .cancel) {}
        }
        .confirmationDialog("Reset the groups?", isPresented: $confirmingGroupsReset, titleVisibility: .visible) {
            Button("Reset groups", role: .destructive) {
                guard let before = story?.story else { return }
                actions.resetGroups()
                withAnimation { resetUndo = before }
                Task {
                    try? await Task.sleep(for: .seconds(undoSeconds))
                    if resetUndo === before { withAnimation { resetUndo = nil } }
                }
            }
        } message: {
            Text("Every group in this story is removed and its photos go back to the story. Group text moves to the story's text. Suggestions start over, including ones you answered \"Not now\".")
        }
        .confirmationDialog("Delete \"\(story?.name ?? "")\"?", isPresented: $confirmingDelete, titleVisibility: .visible) {
            Button("Delete", role: .destructive, action: actions.delete)
        } message: {
            Text("The story and its title go away. Its photos and videos stay on this device.")
        }
        .sheet(isPresented: $editingIntro) {
            WritingEditor(title: story?.name ?? "", initial: story?.intro ?? "", onSave: actions.setIntro)
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
                    if let story, !story.groups.isEmpty || !story.story.dismissedSuggestions.isEmpty {
                        Button { confirmingGroupsReset = true } label: {
                            Label("Reset groups", systemImage: "square.stack")
                        }
                    }
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

    /// Group / Merge / Remove while selecting; otherwise the "Grouped · Undo" message after grouping.
    @ViewBuilder
    private var bottomBar: some View {
        if let selection, let story {
            let tiles = story.tiles.filter { selection.contains($0.key) }
            let ids = itemIds(of: tiles)
            SelectionBar(
                canGroup: tiles.count >= 2,
                canMerge: tiles.count >= 2 && tiles.contains { $0 is StoryTileGroup },
                removeLabel: "Remove",
                onGroup: {
                    let before = story.story
                    self.selection = nil
                    showUndo(actions.groupTiles(Array(selection)), count: ids.count, before: before)
                },
                onMerge: {
                    let before = story.story
                    self.selection = nil
                    showUndo(actions.mergeTiles(Array(selection)), count: ids.count, before: before)
                },
                onRemove: {
                    actions.removeItems(ids)
                    self.selection = nil
                }
            )
        } else if let resetUndo {
            UndoBar(count: 0, message: "Groups reset", onUndo: {
                actions.restore(resetUndo)
                self.resetUndo = nil
            }, onDismiss: { withAnimation { self.resetUndo = nil } })
        } else if let undo {
            UndoBar(count: undo.count, onUndo: {
                actions.restore(undo.before)
                self.undo = nil
            }, onDismiss: { withAnimation { self.undo = nil } })
        }
    }

    private func showUndo(_ groupId: String?, count: Int, before: Story) {
        guard let groupId else { return }
        withAnimation { undo = (groupId, count, before) }
        Task {
            try? await Task.sleep(for: .seconds(undoSeconds))
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
                    // The grid's rows are its bands, so space goes above the first one only.
                    Color.clear.frame(height: 6)
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
                }
                .padding(.bottom, 32)
                .followsViewer { id in storyPlace(of: id, in: story.tileDays) }
            }
            .scrollIndicators(.hidden)
        }
    }

    private func header(_ story: StoryView) -> some View {
        HStack(spacing: 14) {
            if let first = story.firstDay { DateBlock(day: first) }
            VStack(alignment: .leading, spacing: 3) {
                if story.hasTitle {
                    Text(story.title)
                        .font(.system(size: 28, weight: .bold))
                        .foregroundStyle(LibraryColors.onBackground)
                        .accessibilityAddTraits(.isHeader)
                }
                let groups = story.groups.count
                // Untitled, the dates are the heading.
                Text(groups > 0 ? "\(story.summary) · \(String(localized: "\(groups) groups"))" : story.summary)
                    .font(story.hasTitle ? .subheadline : .title3.weight(.semibold))
                    .foregroundStyle(story.hasTitle ? LibraryColors.muted : LibraryColors.onBackground)
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
