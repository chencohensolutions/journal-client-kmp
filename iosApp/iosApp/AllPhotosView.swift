import SwiftUI
import SharedLogic

/// Every photo and video, edge to edge, newest first. See docs/screens/all-photos.md.
struct AllPhotosView: View {
    let library: LibraryState
    let health: ServerHealth
    @Binding var filter: LibraryFilter
    let onOpen: (MediaItem) -> Void

    private static let gap: CGFloat = 2

    var body: some View {
        let days = library.days(filter: filter)
        GeometryReader { geometry in
            let cell = (geometry.size.width - 2 * Self.gap) / 3
            ScrollView {
                LazyVStack(spacing: Self.gap) {
                    if days.isEmpty && !library.loading {
                        Text("Nothing here.")
                            .foregroundStyle(LibraryColors.muted)
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .padding(24)
                    }
                    ForEach(days, id: \.day) { day in
                        DayGrid(day: day, label: day.day.label, cell: cell, onOpen: onOpen)
                    }
                }
                .padding(.bottom, 150)
            }
            .scrollIndicators(.hidden)
        }
        .background(LibraryColors.background.ignoresSafeArea())
        .navigationTitle("All photos")
        .navigationBarTitleDisplayMode(.inline)
        .toolbarBackground(LibraryColors.background, for: .navigationBar)
        .toolbar {
            if health == .offline {
                ToolbarItem(placement: .topBarTrailing) { OfflinePill() }
            }
        }
        .overlay(alignment: .bottom) {
            BottomControls(notBackedUp: Int(library.notBackedUpCount)) {
                SegmentedBar(
                    options: [(LibraryFilter.all, "All"), (.notBackedUp, "Not backed up"), (.videos, "Videos")],
                    selection: $filter
                )
            }
        }
    }
}

/// One day's photos under its name, for All photos and the story page. Days with three or more items open with a
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
