import SwiftUI
import SharedLogic

/// "2:09 PM", or "2:09 – 2:10 PM" when the photos span minutes.
func timeRange(_ items: [MediaItem]) -> String {
    guard let first = items.map(\.takenAt).min(), let last = items.map(\.takenAt).max() else { return "" }
    let start = first.formatted(date: .omitted, time: .shortened)
    let end = last.formatted(date: .omitted, time: .shortened)
    return start == end ? start : "\(start) – \(end)"
}

extension GroupView {
    /// The title, or its time when it has none.
    var name: String { title ?? timeRange(items) }
}

/// A story's grid, day by day: a group is a large tile; the first tile of a day with three or more tiles is large
/// too; the rest fill rows of three. With `selection`, tapping a photo toggles it; groups can't be picked.
struct StoryGrid: View {
    let days: [StoryDay]
    let label: (StoryDay) -> String
    let cell: CGFloat
    let selection: Set<String>?
    let onOpenItem: (MediaItem) -> Void
    let onOpenGroup: (GroupView) -> Void
    let onToggle: (MediaItem) -> Void
    let onLongPress: (MediaItem) -> Void

    private let gap: CGFloat = 2

    var body: some View {
        ForEach(days, id: \.day) { day in
            VStack(spacing: gap) {
                ForEach(rows(day.tiles), id: \.id) { row in
                    switch row.kind {
                    case .hero(let large, let side):
                        HStack(spacing: gap) {
                            tile(large, large: true).frame(width: cell * 2 + gap, height: cell * 2 + gap)
                            VStack(spacing: gap) {
                                ForEach(side.indices, id: \.self) { tile(side[$0], large: false).frame(width: cell, height: cell) }
                                if side.count < 2 { Spacer(minLength: 0) }
                            }
                            .frame(width: cell, height: cell * 2 + gap)
                        }
                    case .regular(let tiles):
                        HStack(spacing: gap) {
                            ForEach(tiles.indices, id: \.self) { tile(tiles[$0], large: false).frame(width: cell, height: cell) }
                            Spacer(minLength: 0)
                        }
                    }
                }
            }
            .overlay(alignment: .topLeading) {
                Text(label(day))
                    .font(.system(size: 15, weight: .bold))
                    .foregroundStyle(.white)
                    .padding(.horizontal, 14)
                    .padding(.top, 12)
                    .padding(.bottom, 24)
                    .background(LibraryColors.labelFade)
                    .allowsHitTesting(false)
                    .accessibilityAddTraits(.isHeader)
            }
        }
    }

    @ViewBuilder
    private func tile(_ tile: StoryTile, large: Bool) -> some View {
        switch tile {
        case let group as StoryTileGroup:
            GroupTileView(group: group.group, large: large)
                .onTapGesture { if selection == nil { onOpenGroup(group.group) } }
        case let media as StoryTileMedia:
            MediaTileView(item: media.item)
                .overlay {
                    if let selection {
                        SelectionOverlay(selected: selection.contains(media.item.id))
                    }
                }
                .onTapGesture { selection == nil ? onOpenItem(media.item) : onToggle(media.item) }
                .onLongPressGesture { onLongPress(media.item) }
        default:
            EmptyView()
        }
    }

    private struct Row {
        enum Kind {
            case hero(StoryTile, [StoryTile])
            case regular([StoryTile])
        }
        let id: String
        let kind: Kind
    }

    private func key(_ tile: StoryTile) -> String {
        switch tile {
        case let group as StoryTileGroup: return "group-\(group.group.id)"
        case let media as StoryTileMedia: return media.item.id
        default: return ""
        }
    }

    private func rows(_ tiles: [StoryTile]) -> [Row] {
        var rows: [Row] = []
        var index = 0
        while index < tiles.count {
            let tile = tiles[index]
            if tile is StoryTileGroup || (index == 0 && tiles.count >= 3) {
                // Up to two photos beside it; another group starts its own row.
                let side = Array(tiles[(index + 1)...].prefix(2).prefix { $0 is StoryTileMedia })
                rows.append(Row(id: "hero-\(key(tile))", kind: .hero(tile, side)))
                index += 1 + side.count
            } else {
                let run = Array(tiles[index...].prefix(3).prefix { $0 is StoryTileMedia })
                rows.append(Row(id: "row-\(key(tile))", kind: .regular(run)))
                index += run.count
            }
        }
        return rows
    }
}

/// Check circle and outline over a photo in selection mode.
private struct SelectionOverlay: View {
    let selected: Bool

    var body: some View {
        ZStack(alignment: .topLeading) {
            if selected { Rectangle().stroke(.white, lineWidth: 3) }
            ZStack {
                Circle().fill(selected ? LibraryColors.accent : LibraryColors.badgeScrim)
                Circle().stroke(.white, lineWidth: 2)
                if selected { Image(systemName: "checkmark").font(.system(size: 11, weight: .bold)).foregroundStyle(.white) }
            }
            .frame(width: 24, height: 24)
            .padding(7)
        }
        .accessibilityAddTraits(selected ? [.isSelected] : [])
    }
}

/// A group in its story: the cover with a stack behind it, the title or time, and "⧉ 13".
struct GroupTileView: View {
    let group: GroupView
    let large: Bool

    var body: some View {
        ZStack {
            RoundedRectangle(cornerRadius: 3).fill(LibraryColors.muted.opacity(0.35))
                .padding(EdgeInsets(top: -4, leading: 8, bottom: 8, trailing: -2))
            RoundedRectangle(cornerRadius: 3).fill(LibraryColors.muted.opacity(0.55))
                .padding(EdgeInsets(top: 0, leading: 4, bottom: 4, trailing: 2))
            MediaTileView(item: group.cover)
                .overlay(alignment: .bottomLeading) {
                    HStack(alignment: .bottom) {
                        VStack(alignment: .leading, spacing: 1) {
                            if let title = group.title {
                                Text(title).font(.system(size: large ? 17 : 13, weight: .bold)).lineLimit(2)
                            }
                            if large || group.title == nil {
                                Text(timeRange(group.items)).font(.caption)
                            }
                        }
                        Spacer(minLength: 4)
                        Label("\(group.itemCount)", systemImage: "square.on.square")
                            .font(.caption.weight(.bold))
                            .padding(.horizontal, 8)
                            .padding(.vertical, 4)
                            .background(LibraryColors.pillScrim, in: Capsule())
                    }
                    .foregroundStyle(.white)
                    .padding(.horizontal, 10)
                    .padding(.top, 32)
                    .padding(.bottom, 8)
                    .background(LibraryColors.photoFade)
                }
                .clipShape(RoundedRectangle(cornerRadius: 3))
                .padding(EdgeInsets(top: 4, leading: 0, bottom: 0, trailing: 6))
        }
        .contentShape(Rectangle())
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("Group: \(group.name), \(Int(group.itemCount)) photos")
        .accessibilityAddTraits(.isButton)
    }
}

/// "13 similar photos · 2:09 PM · taken within 14 seconds" with Group them / Not now.
struct BurstSuggestionCard: View {
    let suggestion: BurstSuggestion
    let onGroup: () -> Void
    let onDismiss: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack(spacing: 12) {
                ZStack(alignment: .topLeading) {
                    ForEach(Array(suggestion.items.prefix(3).enumerated().reversed()), id: \.offset) { index, item in
                        MediaTileView(item: item, badgeSize: 0)
                            .frame(width: 52, height: 52)
                            .clipShape(RoundedRectangle(cornerRadius: 8))
                            .overlay(RoundedRectangle(cornerRadius: 8).stroke(LibraryColors.raised, lineWidth: 2))
                            .offset(x: CGFloat(8 - index * 4), y: CGFloat(index * 5))
                    }
                }
                .frame(width: 64, height: 64, alignment: .topLeading)
                .accessibilityHidden(true)
                VStack(alignment: .leading, spacing: 2) {
                    Text("\(Int(suggestion.count)) similar photos").font(.subheadline.weight(.bold))
                    Text("\(timeRange(Array(suggestion.items.prefix(1)))) · taken within \(Int(max(suggestion.seconds, 1))) seconds")
                        .font(.footnote).foregroundStyle(LibraryColors.muted)
                    Text("Show them as one?").font(.footnote).foregroundStyle(LibraryColors.muted)
                }
                .foregroundStyle(LibraryColors.onBackground)
            }
            HStack(spacing: 8) {
                Button(action: onGroup) {
                    Text("Group them").font(.subheadline.weight(.bold)).frame(maxWidth: .infinity, minHeight: 44)
                        .foregroundStyle(LibraryColors.onInverse)
                        .background(LibraryColors.inverse, in: RoundedRectangle(cornerRadius: 12))
                }
                Button(action: onDismiss) {
                    Text("Not now").font(.subheadline.weight(.semibold)).frame(maxWidth: .infinity, minHeight: 44)
                        .foregroundStyle(LibraryColors.onBackground)
                        .overlay(RoundedRectangle(cornerRadius: 12).stroke(LibraryColors.onBackground.opacity(0.28)))
                }
            }
        }
        .padding(14)
        .background(LibraryColors.raised, in: RoundedRectangle(cornerRadius: 16))
        .padding(.horizontal, 16)
        .padding(.vertical, 6)
    }
}

/// Story text (from story.md) in a journal serif, with Edit; a prompt to write when it's empty.
struct WritingBlock: View {
    let text: String
    let emptyPrompt: LocalizedStringKey
    let onEdit: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            (text.isEmpty ? Text(emptyPrompt) : Text(text))
                .font(.system(size: 17, design: .serif))
                .lineSpacing(4)
                .foregroundStyle(text.isEmpty ? LibraryColors.muted : LibraryColors.onBackground)
                .frame(maxWidth: .infinity, alignment: .leading)
            HStack {
                Text("Saved in story.md").font(.caption).foregroundStyle(LibraryColors.muted)
                Spacer()
                Button(action: onEdit) { Label("Edit", systemImage: "pencil") }
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(LibraryColors.onBackground)
            }
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 14)
        .background(LibraryColors.raised, in: RoundedRectangle(cornerRadius: 14))
        .contentShape(Rectangle())
        .onTapGesture(perform: onEdit)
        .padding(.horizontal, 16)
        .padding(.vertical, 6)
    }
}

/// Full-screen editor for story text.
struct WritingEditor: View {
    let title: String
    let onSave: (String) -> Void

    @State private var text: String
    @Environment(\.dismiss) private var dismiss
    @FocusState private var focused: Bool

    init(title: String, initial: String, onSave: @escaping (String) -> Void) {
        self.title = title
        self.onSave = onSave
        _text = State(initialValue: initial)
    }

    var body: some View {
        NavigationStack {
            TextEditor(text: $text)
                .font(.system(size: 18, design: .serif))
                .scrollContentBackground(.hidden)
                .padding(12)
                .focused($focused)
                .background(LibraryColors.background)
                .navigationTitle(title)
                .navigationBarTitleDisplayMode(.inline)
                .toolbar {
                    ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
                    ToolbarItem(placement: .confirmationAction) {
                        Button("Save") {
                            onSave(text)
                            dismiss()
                        }
                    }
                }
                .onAppear { focused = true }
        }
    }
}
