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

/// A story's grid, day by day: each photo, and each group as its cover, full width at the photo's own ratio (the
/// automatic layout; sizes arranged on Android aren't read here yet). With `selection` (tile keys), tapping a photo
/// or group toggles it.
struct StoryGrid: View {
    let days: [StoryDay]
    let label: (StoryDay) -> String
    let width: CGFloat
    let selection: Set<String>?
    let onOpenItem: (MediaItem) -> Void
    let onOpenGroup: (GroupView) -> Void
    let onToggle: (String) -> Void
    let onLongPress: (String) -> Void

    private let gap: CGFloat = 2

    var body: some View {
        ForEach(days, id: \.day) { day in
            VStack(spacing: gap) {
                ForEach(day.tiles, id: \.key) { tile in
                    self.tile(tile)
                        .frame(width: width, height: width / ratio(tile))
                        .clipped()
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
    private func tile(_ tile: StoryTile) -> some View {
        let key = tile.key
        Group {
            switch tile {
            case let group as StoryTileGroup:
                GroupTileView(group: group.group, large: true)
                    .onTapGesture { selection == nil ? onOpenGroup(group.group) : onToggle(key) }
            case let media as StoryTileMedia:
                MediaTileView(item: media.item)
                    .onTapGesture { selection == nil ? onOpenItem(media.item) : onToggle(key) }
            default:
                EmptyView()
            }
        }
        .overlay {
            if let selection { SelectionOverlay(selected: selection.contains(key)) }
        }
        .onLongPressGesture { onLongPress(key) }
    }

    /// Width over height of the tile's photo (a group's cover); 1.5 when the library doesn't know its size.
    private func ratio(_ tile: StoryTile) -> CGFloat {
        let item: MediaItem
        switch tile {
        case let group as StoryTileGroup: item = group.group.cover
        case let media as StoryTileMedia: item = media.item
        default: return 1.5
        }
        return item.width > 0 && item.height > 0 ? CGFloat(item.width) / CGFloat(item.height) : 1.5
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

/// A group in its story: just its cover, with the number of photos in it.
struct GroupTileView: View {
    let group: GroupView
    let large: Bool

    var body: some View {
        MediaTileView(item: group.cover, badgeSize: 0)
            .overlay(alignment: .bottomTrailing) {
                Text("\(group.itemCount)")
                    .font(.caption.weight(.bold))
                    .foregroundStyle(.white)
                    .padding(.horizontal, 9)
                    .padding(.vertical, 3)
                    .background(LibraryColors.pillScrim, in: Capsule())
                    .padding(6)
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
