import SwiftUI
import SharedLogic

/// What the group page can ask its parent to do. `groupTiles` and `mergeTiles` take tile keys (photos and
/// subgroups) and return the group's id; `restore` undoes either; `takeOut` moves photos to the level above;
/// `acceptSuggestion` makes a suggestion's photos a subgroup and returns its id.
struct GroupPageActions {
    var openItem: (MediaItem) -> Void
    var openGroup: (GroupView) -> Void
    var rename: (String) -> Void
    var setText: (String) -> Void
    var ungroup: () -> Void
    var groupTiles: ([String]) -> String?
    var mergeTiles: ([String]) -> String?
    var restore: (Story) -> Void
    var takeOut: ([String]) -> Void
    var acceptSuggestion: (String) -> String?
    var dismissSuggestion: (String) -> Void
}

/// A group: its cover, title and story text, then its photos and subgroups. Long-press a tile to select tiles and
/// group them into a subgroup, merge them, or take them out. See docs/screens/group.md.
struct GroupPageView: View {
    let story: StoryView?
    let group: GroupView?
    let actions: GroupPageActions

    @Environment(\.dismiss) private var dismiss
    @State private var renaming = false
    @State private var newTitle = ""
    @State private var editing = false
    @State private var selection: Set<String>?
    /// The story as it was before grouping, to put back on Undo.
    @State private var undo: (groupId: String, count: Int, before: Story)?

    var body: some View {
        Group {
            if let story, let group {
                content(story, group)
            } else {
                // Ungrouped or deleted.
                Color.clear.onAppear { dismiss() }
            }
        }
        .background(LibraryColors.background.ignoresSafeArea())
        .navigationBarTitleDisplayMode(.inline)
        .navigationTitle(selection.map { "\($0.count) selected" } ?? "")
        .navigationBarBackButtonHidden(selection != nil)
        .toolbarBackground(selection == nil ? .hidden : .visible, for: .navigationBar)
        .toolbar { toolbar }
        .safeAreaInset(edge: .bottom) { bottomBar }
        .alert("Rename group", isPresented: $renaming) {
            TextField("Title", text: $newTitle)
            Button("Save") { actions.rename(newTitle) }
            Button("Cancel", role: .cancel) {}
        }
        .sheet(isPresented: $editing) {
            WritingEditor(title: group?.name ?? "", initial: group?.text ?? "", onSave: actions.setText)
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
            ToolbarItem(placement: .topBarTrailing) {
                Menu {
                    Button {
                        newTitle = group?.title ?? ""
                        renaming = true
                    } label: { Label("Rename", systemImage: "pencil") }
                    Button(action: actions.ungroup) { Label("Ungroup", systemImage: "square.on.square") }
                } label: {
                    Image(systemName: "ellipsis").foregroundStyle(LibraryColors.onBackground)
                }
                .accessibilityLabel("More")
            }
        }
    }

    /// Group / Merge / Take out while selecting; otherwise the "Grouped · Undo" message after grouping.
    @ViewBuilder
    private var bottomBar: some View {
        if let selection, let story, let group {
            let tiles = group.tiles.filter { selection.contains($0.key) }
            let ids = itemIds(of: tiles)
            SelectionBar(
                // Every tile into one subgroup would only wrap the group again.
                canGroup: tiles.count >= 2 && tiles.count < group.tiles.count,
                canMerge: tiles.count >= 2 && tiles.contains { $0 is StoryTileGroup },
                removeLabel: "Take out",
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
                    actions.takeOut(ids)
                    self.selection = nil
                }
            )
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

    private func content(_ story: StoryView, _ group: GroupView) -> some View {
        GeometryReader { geometry in
            let cell = (geometry.size.width - 4) / 3
            ScrollView {
                VStack(alignment: .leading, spacing: 0) {
                    MediaTileView(item: group.cover, badgeSize: 0)
                        .frame(width: geometry.size.width, height: 300)
                        .overlay(alignment: .bottom) {
                            LinearGradient(colors: [.clear, LibraryColors.background], startPoint: .top, endPoint: .bottom)
                                .frame(height: 120)
                        }
                    VStack(alignment: .leading, spacing: 4) {
                        // "JAPAN TRIP › DAY AT THE SEA": the story, then the groups around this one.
                        Text(([story.name] + story.parentsOf(groupId: group.id).map(\.name)).joined(separator: " › ").uppercased())
                            .font(.caption.weight(.semibold))
                            .tracking(0.7)
                            .foregroundStyle(LibraryColors.muted)
                        Text(group.name)
                            .font(.system(size: 28, weight: .bold))
                            .foregroundStyle(LibraryColors.onBackground)
                            .accessibilityAddTraits(.isHeader)
                        // An untitled group already shows its time as the title.
                        Text([
                            group.items.first?.day.date.formatted(.dateTime.month(.abbreviated).day()),
                            group.title != nil ? timeRange(group.items) : nil,
                            String(localized: "\(Int(group.itemCount)) photos"),
                        ].compactMap { $0 }.joined(separator: " · "))
                            .font(.subheadline)
                            .foregroundStyle(LibraryColors.muted)
                    }
                    .padding(.horizontal, 20)
                    .offset(y: -36)
                    WritingBlock(text: group.text, emptyPrompt: "Write about this moment…") { editing = true }
                        .offset(y: -24)
                    if selection == nil {
                        ForEach(group.suggestions, id: \.key) { suggestion in
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
                    LazyVGrid(columns: Array(repeating: GridItem(.fixed(cell), spacing: 2), count: 3), spacing: 2) {
                        ForEach(group.tiles, id: \.key) { tile in
                            self.tile(tile, in: group)
                                .frame(width: cell, height: cell)
                                .clipped()
                        }
                    }
                }
                .padding(.bottom, 32)
            }
            .scrollIndicators(.hidden)
            .ignoresSafeArea(edges: .top)
        }
    }

    @ViewBuilder
    private func tile(_ tile: StoryTile, in group: GroupView) -> some View {
        let key = tile.key
        let subgroup = (tile as? StoryTileGroup)?.group
        // The tile holding the cover: the photo itself, or the subgroup it is in.
        let holdsCover = subgroup.map { $0.items.contains { $0.id == group.cover.id } }
            ?? ((tile as? StoryTileMedia)?.item.id == group.cover.id)
        Group {
            if let subgroup {
                GroupTileView(group: subgroup, large: false)
                    .zoomSource(ZoomID.group(subgroup.id))
            } else if let media = tile as? StoryTileMedia {
                MediaTileView(item: media.item)
                    .zoomSource(ZoomID.item(media.item.id))
            }
        }
        .overlay(alignment: .topLeading) {
            // Hidden while selecting, where the check circle takes its corner.
            if holdsCover && selection == nil {
                Text("Cover")
                    .font(.caption2.weight(.bold))
                    .foregroundStyle(LibraryColors.paperInk)
                    .padding(.horizontal, 8)
                    .padding(.vertical, 3)
                    .background(LibraryColors.paper, in: Capsule())
                    .padding(6)
            }
        }
        .overlay {
            if let selection { SelectionOverlay(selected: selection.contains(key)) }
        }
        .onTapGesture {
            if selection != nil {
                if selection?.contains(key) == true { selection?.remove(key) } else { selection?.insert(key) }
            } else if let subgroup {
                actions.openGroup(subgroup)
            } else if let media = tile as? StoryTileMedia {
                actions.openItem(media.item)
            }
        }
        .onLongPressGesture {
            if selection == nil { selection = [] }
            selection?.insert(key)
        }
    }
}
