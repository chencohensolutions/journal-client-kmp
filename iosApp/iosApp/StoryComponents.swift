import SwiftUI
import SharedLogic

extension StoryView {
    /// "Oct 8", "Oct 8 – 16", "Sep 28 – Oct 3", with the year when it isn't this year; from its photos (stories have
    /// no dates of their own), nil without photos.
    var dateRange: String? {
        guard let first = firstDay, let last = lastDay else { return nil }
        let formatter = DateIntervalFormatter()
        let thisYear = Calendar.current.component(.year, from: Date())
        let sameYear = first.year == thisYear && last.year == thisYear
        formatter.dateTemplate = sameYear ? "MMMd" : "MMMdyyyy"
        return formatter.string(from: first.date, to: last.date)
    }

    /// The title, or for an untitled story its dates ("Oct 8 – 16").
    var name: String { hasTitle ? title : (dateRange ?? String(localized: "Untitled story")) }

    /// "Oct 8 – 16 · 9 days · 142 photos". The day count shows only for trip-like stories of more than one day.
    var summary: String {
        var parts = [dateRange].compactMap { $0 }
        if isShortSpan && dayCount > 1 { parts.append(String(localized: "\(Int(dayCount)) days")) }
        parts.append(String(localized: "\(Int(itemCount)) photos"))
        return parts.joined(separator: " · ")
    }
}

/// Calendar-style first day of a story: "OCT" over "8".
struct DateBlock: View {
    let day: LocalDay

    var body: some View {
        VStack(spacing: 1) {
            Text(day.date.formatted(.dateTime.month(.abbreviated)).uppercased())
                .font(.system(size: 11, weight: .bold))
                .tracking(0.8)
                .foregroundStyle(LibraryColors.accent)
            Text("\(day.day)")
                .font(.system(size: 24, weight: .bold))
                .foregroundStyle(LibraryColors.paperInk)
        }
        .frame(width: 52)
        .padding(.vertical, 6)
        .background(LibraryColors.paper.opacity(0.94), in: RoundedRectangle(cornerRadius: 12))
        .accessibilityHidden(true)
    }
}

/// A story on the journal: its title (when it has one) and dates above a mosaic of its main photos.
struct StoryCardView: View {
    let story: StoryView
    let width: CGFloat

    /// A story's main photos are its first tiles, in its order, a group as its cover.
    private var highlights: [MediaItem] {
        story.tiles.prefix(6).compactMap { tile in
            (tile as? StoryTileGroup)?.group.cover ?? (tile as? StoryTileMedia)?.item
        }
    }

    var body: some View {
        let items = highlights
        VStack(alignment: .leading, spacing: 0) {
            HStack(alignment: .bottom) {
                VStack(alignment: .leading, spacing: 2) {
                    if story.hasTitle {
                        Text(story.title)
                            .font(.system(size: 22, weight: .bold))
                            .foregroundStyle(LibraryColors.onBackground)
                            .lineLimit(2)
                            .accessibilityAddTraits(.isHeader)
                    }
                    // Untitled, the dates are the heading.
                    Text(story.summary)
                        .font(story.hasTitle ? .footnote.weight(.medium) : .subheadline.weight(.semibold))
                        .foregroundStyle(story.hasTitle ? LibraryColors.muted : LibraryColors.onBackground)
                }
                Spacer()
                if story.notBackedUpCount > 0 {
                    Label("\(Int(story.notBackedUpCount))", systemImage: "icloud.slash")
                        .font(.footnote.weight(.medium))
                        .foregroundStyle(LibraryColors.muted)
                        .accessibilityLabel("\(Int(story.notBackedUpCount)) not backed up")
                }
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 10)
            HighlightMosaic(items: items, more: Int(story.itemCount) - items.count, width: width)
        }
        .contentShape(Rectangle())
        .accessibilityElement(children: .combine)
        .accessibilityAddTraits(.isButton)
        .padding(.bottom, 22)
    }
}

/// One photo full width; two side by side; a large one with two beside it; with six, a row of three under them. The
/// last tile says how many more photos the story has.
private struct HighlightMosaic: View {
    let items: [MediaItem]
    let more: Int
    let width: CGFloat
    private let gap: CGFloat = 2

    var body: some View {
        let third = (width - 2 * gap) / 3
        switch items.count {
        case 0:
            LibraryColors.raised.frame(width: width, height: width / 3)
        case 1:
            tile(items[0]).frame(width: width, height: width / 1.5)
        case 2:
            HStack(spacing: gap) {
                ForEach(items, id: \.id) { tile($0).frame(width: (width - gap) / 2, height: width / 2) }
            }
        default:
            VStack(spacing: gap) {
                HStack(spacing: gap) {
                    tile(items[0]).frame(width: third * 2 + gap, height: third * 2 + gap)
                    VStack(spacing: gap) {
                        tile(items[1]).frame(width: third, height: third)
                        tile(items[2]).frame(width: third, height: third)
                    }
                }
                if items.count >= 6 {
                    HStack(spacing: gap) {
                        ForEach(items[3..<6], id: \.id) { tile($0).frame(width: third, height: third) }
                    }
                }
            }
        }
    }

    /// "+12" over the last photo shown.
    private func tile(_ item: MediaItem) -> some View {
        CoverTile(item: item).overlay {
            if more > 0, item.id == items.last?.id {
                ZStack {
                    Color.black.opacity(0.38)
                    Text("+\(more)").font(.system(size: 22, weight: .bold)).foregroundStyle(.white)
                }
            }
        }
    }
}

private struct CoverTile: View {
    let item: MediaItem
    @State private var image: UIImage?

    var body: some View {
        GeometryReader { geometry in
            ZStack {
                LibraryColors.raised
                if let image {
                    Image(uiImage: image)
                        .resizable()
                        .scaledToFill()
                        .frame(width: geometry.size.width, height: geometry.size.height)
                        .clipped()
                }
            }
            .task(id: item.id) {
                image = await PhotoLibraryImages.shared.image(id: item.id, pointSize: geometry.size)
            }
        }
    }
}
