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

/// Where a `StoryGrid` shows a photo: the band of its day with its own tile, or with the group's it is the cover of.
func storyPlace(of itemId: String, in days: [StoryDay]) -> PhotoPlace? {
    for day in days {
        for band in day.bands {
            if let tile = tilePlace(of: itemId, in: band.tiles.map(\.tile)) {
                return PhotoPlace(section: bandId(day, band), tile: tile)
            }
        }
    }
    return nil
}

/// The row id of a band in a `StoryGrid`'s lazy stack.
private func bandId(_ day: StoryDay, _ band: StoryBand) -> String { "\(day.day)|\(band.key)" }

/// The zoom id of the tile showing a photo: its own, or that of the group it is the cover of.
func tilePlace(of itemId: String, in tiles: [StoryTile]) -> String? {
    for tile in tiles {
        if let media = tile as? StoryTileMedia, media.item.id == itemId { return ZoomID.item(itemId) }
        if let group = (tile as? StoryTileGroup)?.group, group.cover.id == itemId { return ZoomID.group(group.id) }
    }
    return nil
}

/// Width over height of the tile's photo (a group's cover); 1.5 when the library doesn't know its size.
func tileRatio(_ tile: StoryTile) -> CGFloat {
    let item: MediaItem
    switch tile {
    case let group as StoryTileGroup: item = group.group.cover
    case let media as StoryTileMedia: item = media.item
    default: return 1.5
    }
    return item.width > 0 && item.height > 0 ? CGFloat(item.width) / CGFloat(item.height) : 1.5
}

/// How a tile crops its photo: anchored at the focus point (0...1 of the photo from the left and from the top),
/// `zoom` times closer than filling the tile. Anchored there, the point sits at the same fraction of the tile, so
/// zooming around that fraction keeps it in view.
struct TileCrop: Equatable {
    var x: CGFloat = 0.5
    var y: CGFloat = 0.5
    var zoom: CGFloat = 1

    static let none = TileCrop()
}

extension TileCrop {
    init(_ placed: PlacedTile) {
        self.init(x: CGFloat(placed.focus.x), y: CGFloat(placed.focus.y), zoom: max(CGFloat(placed.zoom), 1))
    }
}

/// A story's grid, day by day, each day packed onto the 6-column grid (`StoryDay.bands`): tiles are as large as the
/// owner arranged them (arranging is Android only for now), or automatic (full width at the photo's own ratio),
/// cropped around their focus point. Each band is one row of the lazy stack, so a long day still scrolls lazily.
/// With `selection` (tile keys), tapping a photo or group toggles it.
struct StoryGrid: View {
    let days: [StoryDay]
    let label: (StoryDay) -> String
    let width: CGFloat
    let selection: Set<String>?
    let onOpenItem: (MediaItem) -> Void
    let onOpenGroup: (GroupView) -> Void
    let onToggle: (String) -> Void
    let onLongPress: (String) -> Void

    /// Same as the stack's spacing between bands.
    private let gap: CGFloat = 2

    private struct Row: Identifiable {
        let id: String
        let day: StoryDay
        let band: StoryBand
        let first: Bool
    }

    private var rows: [Row] {
        days.flatMap { day in
            day.bands.enumerated().map { index, band in
                Row(id: bandId(day, band), day: day, band: band, first: index == 0)
            }
        }
    }

    var body: some View {
        ForEach(rows) { row in
            bandGrid(row.band)
                .overlay(alignment: .topLeading) {
                    if row.first {
                        Text(label(row.day))
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
    }

    /// One band: square cells six across, each tile spanning its size in cells; or one full-width tile at its photo's
    /// own ratio (`StoryBand.aspectRatio`).
    @ViewBuilder
    private func bandGrid(_ band: StoryBand) -> some View {
        if let ratio = band.aspectRatio, let placed = band.tiles.first {
            tile(placed).frame(width: width, height: width / CGFloat(truncating: ratio))
        } else {
            let columns = CGFloat(StoryLayoutKt.GRID_COLUMNS)
            let cell = (width - gap * (columns - 1)) / columns
            let span = { (cells: Int32) in cell * CGFloat(cells) + gap * CGFloat(cells - 1) }
            // Leading padding follows the layout direction, so the grid mirrors in Hebrew like the rest of the screen.
            ZStack(alignment: .topLeading) {
                ForEach(band.tiles, id: \.tile.key) { placed in
                    tile(placed)
                        .frame(width: span(placed.size.columns), height: span(placed.size.rows))
                        .padding(.leading, (cell + gap) * CGFloat(placed.column))
                        .padding(.top, (cell + gap) * CGFloat(placed.row))
                }
            }
            .frame(width: width, height: span(band.rows), alignment: .topLeading)
        }
    }

    @ViewBuilder
    private func tile(_ placed: PlacedTile) -> some View {
        let tile = placed.tile
        let key = tile.key
        Group {
            switch tile {
            case let group as StoryTileGroup:
                GroupTileView(group: group.group, large: true, crop: TileCrop(placed))
                    .zoomSource(ZoomID.group(group.group.id))
                    .onTapGesture { selection == nil ? onOpenGroup(group.group) : onToggle(key) }
            case let media as StoryTileMedia:
                MediaTileView(item: media.item, crop: TileCrop(placed))
                    .zoomSource(ZoomID.item(media.item.id))
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

}

/// Check circle and outline over a photo in selection mode.
struct SelectionOverlay: View {
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

/// The photos of the picked tiles: a photo itself, or all of a group's.
func itemIds(of tiles: [StoryTile]) -> [String] {
    tiles.flatMap { tile -> [String] in
        if let group = tile as? StoryTileGroup { return group.group.items.map(\.id) }
        return (tile as? StoryTileMedia).map { [$0.item.id] } ?? []
    }
}

/// The bar under a selection: Group puts the picked tiles in a new group, Merge (when a group is picked) joins them
/// into the first picked group, and the last button takes them out.
struct SelectionBar: View {
    let canGroup: Bool
    let canMerge: Bool
    let removeLabel: LocalizedStringKey
    let onGroup: () -> Void
    let onMerge: () -> Void
    let onRemove: () -> Void

    var body: some View {
        HStack(spacing: 8) {
            Button(action: onGroup) {
                Label("Group", systemImage: "square.on.square")
                    .font(.headline)
                    .frame(maxWidth: .infinity, minHeight: 52)
                    .foregroundStyle(LibraryColors.onInverse)
                    .background(LibraryColors.inverse.opacity(canGroup ? 1 : 0.4), in: RoundedRectangle(cornerRadius: 14))
            }
            .disabled(!canGroup)
            if canMerge {
                Button(action: onMerge) {
                    Text("Merge")
                        .font(.headline)
                        .frame(maxWidth: .infinity, minHeight: 52)
                        .foregroundStyle(LibraryColors.accent)
                }
            }
            Button(action: onRemove) {
                Label(removeLabel, systemImage: "minus")
                    .font(.headline)
                    .frame(maxWidth: .infinity, minHeight: 52)
                    .foregroundStyle(LibraryColors.warning)
            }
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 12)
        .background(LibraryColors.raised.ignoresSafeArea())
    }
}

/// How long "Grouped 14 photos · Undo" stays, unless closed with its ✕ first.
let undoSeconds = 6

/// "Grouped 14 photos · Undo ✕", for a few seconds after grouping.
struct UndoBar: View {
    let count: Int
    /// Instead of "Grouped N photos".
    var message: LocalizedStringKey?
    let onUndo: () -> Void
    let onDismiss: () -> Void

    var body: some View {
        HStack(spacing: 4) {
            Text(message ?? "Grouped \(count) photos").font(.subheadline)
            Spacer()
            Button("Undo", action: onUndo)
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

/// A group in its story: just its cover, with the number of photos in it.
struct GroupTileView: View {
    let group: GroupView
    let large: Bool
    var crop: TileCrop = .none

    var body: some View {
        MediaTileView(item: group.cover, badgeSize: 0, crop: crop)
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

/// "Look alike", "13 similar photos" (or "Taken together", "13 photos in a row"), "2:09 PM · taken within 14 seconds" (or minutes, hours,
/// days) with Group them / Not now.
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
                    // Why they were picked: what's in them, or when they were taken.
                    Label(suggestion.byLooks ? "Look alike" : "Taken together", systemImage: suggestion.byLooks ? "eye" : "timer")
                        .font(.caption.weight(.semibold))
                        .foregroundStyle(LibraryColors.muted)
                    Group {
                        if suggestion.byLooks {
                            Text("\(Int(suggestion.count)) similar photos")
                        } else {
                            Text("\(Int(suggestion.count)) photos in a row")
                        }
                    }
                        .font(.subheadline.weight(.bold))
                    // Photos that look alike can be taken hours or days apart.
                    Group {
                        if suggestion.seconds < 60 {
                            Text("\(timeRange(Array(suggestion.items.prefix(1)))) · taken within \(Int(max(suggestion.seconds, 1))) seconds")
                        } else if suggestion.sameDay && suggestion.seconds < 3600 {
                            Text("\(timeRange(Array(suggestion.items.prefix(1)))) · taken within \(Int((suggestion.seconds + 59) / 60)) minutes")
                        } else if suggestion.sameDay {
                            Text(timeRange(suggestion.items))
                        } else if let first = suggestion.items.first, let last = suggestion.items.last {
                            Text("\(first.day.date.formatted(.dateTime.month(.abbreviated).day())) – \(last.day.date.formatted(.dateTime.month(.abbreviated).day()))")
                        }
                    }
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
