import SwiftUI
import SharedLogic

/// Where `DayGrid`s show a photo: in its day.
func dayPlace(of itemId: String, in days: [DayGroup]) -> PhotoPlace? {
    days.first { $0.items.contains { $0.id == itemId } }.map { PhotoPlace(section: $0.day, tile: ZoomID.item(itemId)) }
}

/// One day's photos under its name, for a tag's page. Days with three or more items open with a
/// 2×2 photo; the rest fill rows of three.
struct DayGrid: View {
    let day: DayGroup
    let label: String
    let cell: CGFloat
    let onOpen: (MediaItem) -> Void

    private let gap: CGFloat = 2

    var body: some View {
        let items = day.items
        let hasHero = items.count >= 3
        let rest = hasHero ? Array(items.dropFirst(3)) : items
        VStack(spacing: gap) {
            if hasHero {
                HStack(spacing: gap) {
                    tile(items[0]).frame(width: cell * 2 + gap, height: cell * 2 + gap)
                    VStack(spacing: gap) {
                        tile(items[1]).frame(width: cell, height: cell)
                        tile(items[2]).frame(width: cell, height: cell)
                    }
                }
            }
            ForEach(Array(stride(from: 0, to: rest.count, by: 3)), id: \.self) { start in
                HStack(spacing: gap) {
                    ForEach(rest[start..<min(start + 3, rest.count)], id: \.id) { item in
                        tile(item).frame(width: cell, height: cell)
                    }
                    Spacer(minLength: 0)
                }
            }
        }
        .overlay(alignment: .topLeading) {
            Text(label)
                .font(.system(size: 15, weight: .bold))
                .foregroundStyle(.white)
                .padding(.horizontal, 14)
                .padding(.top, 12)
                .padding(.bottom, 24)
                .background(LibraryColors.labelFade)
                .accessibilityAddTraits(.isHeader)
        }
    }

    private func tile(_ item: MediaItem) -> some View {
        MediaTileView(item: item).zoomSource(ZoomID.item(item.id)).onTapGesture { onOpen(item) }
    }
}
