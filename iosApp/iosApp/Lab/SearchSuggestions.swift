#if DEBUG

import SwiftUI
import SharedLogic

// Suggestions for a Search tab: the user types a sentence ("a dog on the beach at sunset") and the server answers
// with the photos and videos it describes best, best first (`searchMedia`, see the server's docs/search.md). The
// server also says how many of the user's files it has read so far, so the results can say when some can't be
// found yet. The answers here are made up: photos of the places library, in no real order.

private let data = LabData.shared
private let places = data.placesStories.places
private let query = "a dog on the beach at sunset"

/// The made-up answer, best first.
private let hits: [MediaItem] = Array(
    data.placesLibrary.days.flatMap(\.items).enumerated().filter { $0.offset % 3 == 1 }.map(\.element).prefix(36)
)

private func town(_ item: MediaItem) -> String? {
    places.location(item: item).flatMap { places.name(point: $0)?.town }
}

// MARK: - Field at the bottom, two columns

/// Tapping Search turns the tab bar into the field, near the thumb, with ✕ to go back to the tabs; the photos fill
/// the page from the top in two columns at their own shape.
struct SearchBottomSuggestion: View {
    @State private var text = query

    var body: some View {
        SearchPage(topHeight: 72) { width in
            ResultsNote(count: hits.count)
            Masonry(items: hits, width: width - 16)
                .padding(.horizontal, 8)
        } top: {
            BottomSearchTitle()
        } bottom: {
            BottomSearchBar(text: $text)
        }
    }
}

// MARK: - Field at the bottom, one per row

/// The photos found one under another, best first, each at its own shape across the page, with when and where it was
/// taken under it.
struct SearchRowsCaptionSuggestion: View {
    @State private var text = query

    var body: some View {
        SearchPage(topHeight: 72) { width in
            ResultsNote(count: hits.count)
            ForEach(hits, id: \.id) { item in
                VStack(alignment: .leading, spacing: 6) {
                    MediaTileView(item: item, badgeSize: 22)
                        .frame(width: width - 32, height: (width - 32) / ratio(item))
                        .clipShape(RoundedRectangle(cornerRadius: 14))
                    HStack(alignment: .firstTextBaseline, spacing: 6) {
                        Text(item.day.label).font(.subheadline.weight(.semibold)).foregroundStyle(LibraryColors.onBackground)
                        Text(time(item)).font(.subheadline).foregroundStyle(LibraryColors.muted)
                        Spacer(minLength: 8)
                        if let place = place(item) {
                            Label(place, systemImage: "mappin.and.ellipse")
                                .font(.subheadline)
                                .foregroundStyle(LibraryColors.muted)
                                .lineLimit(1)
                        }
                    }
                }
                .padding(.horizontal, 16)
                .padding(.bottom, 10)
            }
        } top: {
            BottomSearchTitle()
        } bottom: {
            BottomSearchBar(text: $text)
        }
    }
}

/// As above, but each photo is the screen's full width, edge to edge and square-cornered, with only the caption
/// indented.
struct SearchRowsFullSuggestion: View {
    @State private var text = query

    var body: some View {
        SearchPage(topHeight: 72) { width in
            ResultsNote(count: hits.count)
            ForEach(hits, id: \.id) { item in
                VStack(alignment: .leading, spacing: 8) {
                    MediaTileView(item: item, badgeSize: 22)
                        .frame(width: width, height: width / ratio(item))
                        .clipped()
                    HStack(alignment: .firstTextBaseline, spacing: 6) {
                        Text(item.day.label).font(.subheadline.weight(.semibold)).foregroundStyle(LibraryColors.onBackground)
                        Text(time(item)).font(.subheadline).foregroundStyle(LibraryColors.muted)
                        Spacer(minLength: 8)
                        if let place = place(item) {
                            Label(place, systemImage: "mappin.and.ellipse")
                                .font(.subheadline)
                                .foregroundStyle(LibraryColors.muted)
                                .lineLimit(1)
                        }
                    }
                    .padding(.horizontal, 16)
                }
                .padding(.bottom, 14)
            }
        } top: {
            BottomSearchTitle()
        } bottom: {
            BottomSearchBar(text: $text)
        }
    }
}

/// As a list: a thumbnail on the left and when and where on the right, so many show at once.
struct SearchRowsListSuggestion: View {
    @State private var text = query

    var body: some View {
        SearchPage(topHeight: 72) { _ in
            ResultsNote(count: hits.count)
            VStack(spacing: 0) {
                ForEach(hits, id: \.id) { item in
                    HStack(spacing: 14) {
                        MediaTileView(item: item, badgeSize: 16)
                            .frame(width: 96, height: 96)
                            .clipShape(RoundedRectangle(cornerRadius: 12))
                        VStack(alignment: .leading, spacing: 4) {
                            Text(item.day.label).font(.body.weight(.semibold)).foregroundStyle(LibraryColors.onBackground)
                            Text(time(item)).font(.subheadline).foregroundStyle(LibraryColors.muted)
                            if let place = place(item) {
                                Label(place, systemImage: "mappin.and.ellipse")
                                    .font(.subheadline)
                                    .foregroundStyle(LibraryColors.muted)
                                    .lineLimit(1)
                            }
                        }
                        Spacer()
                        Image(systemName: "chevron.right").font(.footnote.weight(.semibold)).foregroundStyle(LibraryColors.muted)
                    }
                    .padding(.horizontal, 16)
                    .padding(.vertical, 6)
                }
            }
        } top: {
            BottomSearchTitle()
        } bottom: {
            BottomSearchBar(text: $text)
        }
    }
}

/// The photo across the page with the date, time and place written over its bottom, as the storyline does.
struct SearchRowsOverlaySuggestion: View {
    @State private var text = query

    var body: some View {
        SearchPage(topHeight: 72) { width in
            ResultsNote(count: hits.count)
            ForEach(hits, id: \.id) { item in
                MediaTileView(item: item, badgeSize: 22)
                    .frame(width: width - 32, height: (width - 32) / max(ratio(item), 0.8))
                    .overlay(alignment: .bottomLeading) {
                        VStack(alignment: .leading, spacing: 2) {
                            Text("\(item.day.label) · \(time(item))").font(.subheadline.weight(.semibold))
                            if let place = place(item) {
                                Label(place, systemImage: "mappin.and.ellipse").font(.footnote)
                            }
                        }
                        .foregroundStyle(LibraryColors.photoText)
                        .padding(.horizontal, 14)
                        .padding(.top, 28)
                        .padding(.bottom, 12)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .background(LibraryColors.photoFade)
                    }
                    .clipShape(RoundedRectangle(cornerRadius: 16))
                    .padding(.horizontal, 16)
                    .padding(.bottom, 4)
            }
        } top: {
            BottomSearchTitle()
        } bottom: {
            BottomSearchBar(text: $text)
        }
    }
}

// MARK: - Shared pieces

/// "Search" alone at the top, when the field is at the bottom.
private struct BottomSearchTitle: View {
    var body: some View {
        HStack {
            Text("Search")
                .font(.system(size: 30, weight: .bold))
                .foregroundStyle(LibraryColors.onBackground)
                .accessibilityAddTraits(.isHeader)
            Spacer()
        }
        .padding(.horizontal, 16)
        .padding(.bottom, 24)
        .background(LibraryColors.topFade.ignoresSafeArea())
    }
}

/// The tab bar turned into the field, with ✕ to go back to the tab the user came from.
private struct BottomSearchBar: View {
    @Binding var text: String

    var body: some View {
        HStack(spacing: 10) {
            SearchField(text: $text)
                .shadow(color: .black.opacity(0.35), radius: 12, y: 8)
            Button {} label: {
                Image(systemName: "xmark")
                    .font(.system(size: 17, weight: .semibold))
                    .foregroundStyle(LibraryColors.onBackground)
                    .frame(width: 48, height: 48)
                    .background(LibraryColors.raised.opacity(0.95), in: Circle())
                    .shadow(color: .black.opacity(0.35), radius: 12, y: 8)
            }
            .accessibilityLabel("Back to the tabs")
        }
        .padding(.horizontal, 16)
    }
}

/// "19:42", or "7:42 PM", as the phone tells time.
private func time(_ item: MediaItem) -> String {
    item.takenAt.formatted(date: .omitted, time: .shortened)
}

/// "Haifa, Israel".
private func place(_ item: MediaItem) -> String? {
    guard let name = places.location(item: item).flatMap({ places.name(point: $0) }) else { return nil }
    let parts = [name.town, name.country].compactMap { $0 }
    return parts.isEmpty ? nil : parts.joined(separator: ", ")
}

/// The home screen's layout: content scrolling under a floating top bar and bottom bar.
private struct SearchPage<Content: View, Top: View, Bottom: View>: View {
    let topHeight: CGFloat
    @ViewBuilder let content: (CGFloat) -> Content
    @ViewBuilder let top: Top
    @ViewBuilder let bottom: Bottom

    var body: some View {
        GeometryReader { geometry in
            ScrollView {
                VStack(alignment: .leading, spacing: 12) { content(geometry.size.width) }
                    .padding(.top, topHeight)
                    .padding(.bottom, 150)
            }
            .scrollIndicators(.hidden)
            .scrollDismissesKeyboard(.immediately)
        }
        .background(LibraryColors.background.ignoresSafeArea())
        .overlay(alignment: .top) { top }
        .overlay(alignment: .bottom) {
            bottom
                .padding(.top, 48)
                .padding(.bottom, 8)
                .frame(maxWidth: .infinity)
                .background(LibraryColors.bottomFade.ignoresSafeArea())
        }
        .toolbar(.hidden, for: .navigationBar)
    }
}

private struct SearchField: View {
    @Binding var text: String

    var body: some View {
        HStack(spacing: 8) {
            Image(systemName: "magnifyingglass").foregroundStyle(LibraryColors.muted)
            TextField("Describe a photo", text: $text)
                .submitLabel(.search)
                .foregroundStyle(LibraryColors.onBackground)
            if !text.isEmpty {
                Button { text = "" } label: { Image(systemName: "xmark.circle.fill") }
                    .foregroundStyle(LibraryColors.muted)
                    .accessibilityLabel("Clear")
            }
        }
        .font(.system(size: 17))
        .padding(.horizontal, 14)
        .frame(height: 48)
        .background(LibraryColors.raised, in: Capsule())
    }
}

/// "36 photos, best first", and how far the server has got reading the library.
private struct ResultsNote: View {
    let count: Int

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text("\(count) photos, best first").font(.footnote.weight(.semibold)).foregroundStyle(LibraryColors.muted)
            IndexingLine()
        }
        .padding(.horizontal, 16)
    }
}

/// While the server hasn't read every photo yet, the ones it hasn't can't be found.
private struct IndexingLine: View {
    var body: some View {
        HStack(spacing: 8) {
            ProgressView(value: 0.88).tint(LibraryColors.accent).frame(width: 48)
            Text("Still reading your photos: 12,480 of 14,200").font(.footnote).foregroundStyle(LibraryColors.muted)
        }
    }
}

/// Two columns of photos at their own shape, each photo going under the shorter column so the best stay near the top.
private struct Masonry: View {
    let items: [MediaItem]
    let width: CGFloat

    var body: some View {
        let spacing: CGFloat = 6
        let column = (width - spacing) / 2
        let split = columns(column)
        HStack(alignment: .top, spacing: spacing) {
            ForEach(0..<2, id: \.self) { c in
                VStack(spacing: spacing) {
                    ForEach(split[c], id: \.id) { item in
                        MediaTileView(item: item, badgeSize: 18)
                            .frame(width: column, height: column / ratio(item))
                            .clipShape(RoundedRectangle(cornerRadius: 12))
                    }
                }
            }
        }
    }

    private func columns(_ column: CGFloat) -> [[MediaItem]] {
        var split: [[MediaItem]] = [[], []]
        var heights: [CGFloat] = [0, 0]
        for item in items {
            let c = heights[0] <= heights[1] ? 0 : 1
            split[c].append(item)
            heights[c] += column / ratio(item)
        }
        return split
    }
}

/// Width over height.
private func ratio(_ item: MediaItem) -> CGFloat {
    item.width > 0 && item.height > 0 ? CGFloat(item.width) / CGFloat(item.height) : 1
}

#endif
