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

/// A story on the journal timeline: a mosaic of its first photos with the title over it.
struct StoryCardView: View {
    let story: StoryView
    let width: CGFloat

    var body: some View {
        CoverMosaic(items: Array(story.items.prefix(5)), width: width)
            .overlay(alignment: .topTrailing) {
                if story.notBackedUpCount > 0 {
                    Label("\(Int(story.notBackedUpCount)) not backed up", systemImage: "icloud.slash")
                        .font(.caption.weight(.semibold))
                        .foregroundStyle(.white)
                        .padding(.horizontal, 9)
                        .padding(.vertical, 4)
                        .background(LibraryColors.pillScrim, in: Capsule())
                        .padding(10)
                }
            }
            .overlay(alignment: .bottomLeading) {
                HStack(alignment: .bottom, spacing: 12) {
                    if let first = story.firstDay { DateBlock(day: first) }
                    VStack(alignment: .leading, spacing: 3) {
                        Text(story.title)
                            .font(.system(size: 24, weight: .bold))
                            .foregroundStyle(.white)
                        Text(story.summary)
                            .font(.footnote.weight(.medium))
                            .foregroundStyle(LibraryColors.photoTextMuted)
                    }
                }
                .padding(.horizontal, 16)
                .padding(.top, 56)
                .padding(.bottom, 14)
                .frame(maxWidth: .infinity, alignment: .leading)
                .background(LibraryColors.photoFade)
            }
            .contentShape(Rectangle())
            .accessibilityElement(children: .combine)
            .accessibilityAddTraits(.isButton)
            .padding(.bottom, 6)
    }
}

/// The cover: one photo full width, two side by side, or a large photo with smaller ones around it.
private struct CoverMosaic: View {
    let items: [MediaItem]
    let width: CGFloat
    private let gap: CGFloat = 2

    var body: some View {
        let third = (width - 2 * gap) / 3
        switch items.count {
        case 0:
            LibraryColors.raised.frame(width: width, height: width / 3)
        case 1:
            CoverTile(item: items[0]).frame(width: width, height: width / 1.5)
        case 2:
            HStack(spacing: gap) {
                ForEach(items, id: \.id) { CoverTile(item: $0).frame(width: (width - gap) / 2, height: width / 2) }
            }
        default:
            VStack(spacing: gap) {
                HStack(spacing: gap) {
                    CoverTile(item: items[0]).frame(width: third * 2 + gap, height: third * 2 + gap)
                    VStack(spacing: gap) {
                        CoverTile(item: items[1]).frame(width: third, height: third)
                        CoverTile(item: items[2]).frame(width: third, height: third)
                    }
                }
                if items.count >= 5 {
                    HStack(spacing: gap) {
                        CoverTile(item: items[3]).frame(width: third * 2 + gap, height: third)
                        CoverTile(item: items[4]).frame(width: third, height: third)
                    }
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
