#if DEBUG

import SwiftUI
import SharedLogic

// Search, option A (Nov 19, 2026): the results as a feed by date, Moments-style: a day line, its towns and count, the
// photos across the screen, newest first. "Newest first · Best match" switches the order; a row of place chips under
// it narrows to one town, each with its count.

struct SearchOptionAState {
    let query: String
    /// The matches as a feed by date (or, with `bestFirst`, one list best first).
    let days: [SearchFeedDay]
    let bestFirst: Bool
    /// Every town of the matches, most first; counts within the whole answer.
    let towns: [SearchPlaceCount]
    let chosenTown: String?
    let total: Int
    let shown: Int
}

struct SearchOptionAActions {
    var setBestFirst: (Bool) -> Void = { _ in }
    var chooseTown: (String?) -> Void = { _ in }
    var openItem: (MediaItem) -> Void = { _ in }
    var close: () -> Void = {}
}

struct SearchScreenA: View {
    let state: SearchOptionAState
    let actions: SearchOptionAActions
    @StateObject private var playback = FeedPlayback()

    var body: some View {
        GeometryReader { geometry in
            let width = geometry.size.width
            ZStack(alignment: .bottom) {
                LibraryColors.background.ignoresSafeArea()
                ScrollView {
                    LazyVStack(alignment: .leading, spacing: 0) {
                        Text("Search", tableName: "SearchOptionA")
                            .font(.system(size: 30, weight: .bold)).foregroundStyle(LibraryColors.onBackground)
                            .padding(.horizontal, 16).padding(.top, 8)
                        Text("\(state.shown) photos of “\(state.query)”", tableName: "SearchOptionA")
                            .font(.footnote).foregroundStyle(LibraryColors.muted)
                            .padding(.horizontal, 16).padding(.top, 2)
                        order.padding(.top, 12)
                        townChips.padding(.top, 10)
                        ForEach(state.days) { day in
                            if !state.bestFirst {
                                DayLine(label: day.day.label).padding(.horizontal, 16).padding(.top, 28)
                                dayLine(day).frame(maxWidth: .infinity).padding(.top, 4).padding(.bottom, 10)
                            }
                            ForEach(day.items, id: \.id) { item in
                                photo(item, width: width)
                            }
                        }
                        Color.clear.frame(height: 120)
                    }
                }
                .scrollIndicators(.hidden)
                field.padding(.horizontal, 16).padding(.bottom, 8)
            }
        }
    }

    /// Newest first (the feed) or Best match (the list as today), as one segmented pill.
    private var order: some View {
        HStack(spacing: 0) {
            segment("Newest first", selected: !state.bestFirst) { actions.setBestFirst(false) }
            segment("Best match", selected: state.bestFirst) { actions.setBestFirst(true) }
        }
        .padding(3)
        .background(LibraryColors.raised, in: Capsule())
        .padding(.horizontal, 16)
    }

    private func segment(_ key: LocalizedStringKey, selected: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Text(key, tableName: "SearchOptionA")
                .font(.system(size: 14, weight: .semibold))
                .foregroundStyle(selected ? LibraryColors.onInverse : LibraryColors.onBackground)
                .padding(.horizontal, 16).frame(height: 34)
                .background(selected ? LibraryColors.inverse : .clear, in: Capsule())
        }
        .buttonStyle(.plain)
    }

    /// "All places 38 · Tel Aviv 14 · Paris 9 …": one town at a time; scrolls sideways.
    private var townChips: some View {
        ScrollView(.horizontal) {
            HStack(spacing: 8) {
                chip(Text("All places", tableName: "SearchOptionA"), count: state.total, selected: state.chosenTown == nil) { actions.chooseTown(nil) }
                ForEach(state.towns) { town in
                    chip(Text(verbatim: town.town), count: town.count, selected: state.chosenTown == town.town) {
                        actions.chooseTown(state.chosenTown == town.town ? nil : town.town)
                    }
                }
            }
            .padding(.horizontal, 16)
        }
        .scrollIndicators(.hidden)
    }

    private func chip(_ label: Text, count: Int, selected: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HStack(spacing: 6) {
                if selected { Image(systemName: "mappin.and.ellipse").font(.system(size: 12, weight: .semibold)) }
                label.font(.system(size: 14, weight: .semibold))
                Text(verbatim: "\(count)").font(.system(size: 14)).opacity(0.7)
            }
            .foregroundStyle(selected ? LibraryColors.onInverse : LibraryColors.onBackground)
            .padding(.horizontal, 14).frame(height: 36)
            .background(selected ? LibraryColors.inverse : .clear, in: Capsule())
            .overlay(Capsule().stroke(selected ? .clear : LibraryColors.muted.opacity(0.5), lineWidth: 1))
        }
        .buttonStyle(.plain)
    }

    /// Under the day line: "Tel Aviv → Jaffa · 6 photos".
    private func dayLine(_ day: SearchFeedDay) -> some View {
        let towns = day.towns.joined(separator: " → ")
        return Group {
            if towns.isEmpty {
                Text("\(day.items.count) photos", tableName: "SearchOptionA")
            } else {
                Text("\(towns) · \(day.items.count) photos", tableName: "SearchOptionA")
            }
        }
        .font(.system(size: 12)).foregroundStyle(LibraryColors.muted)
    }

    private func photo(_ item: MediaItem, width: CGFloat) -> some View {
        let ratio = item.width > 0 && item.height > 0 ? CGFloat(item.width) / CGFloat(item.height) : 4 / 3
        let height = width / min(max(ratio, 0.6), 3)
        return FeedPhoto(item: item, size: CGSize(width: width, height: height), playback: playback)
            .onTapGesture { actions.openItem(item) }
            .padding(.bottom, 2)
    }

    /// The field as the app has it, with the words searched.
    private var field: some View {
        HStack(spacing: 10) {
            HStack(spacing: 8) {
                Image(systemName: "magnifyingglass").foregroundStyle(LibraryColors.muted)
                Text(verbatim: state.query).foregroundStyle(LibraryColors.onBackground)
                Spacer()
                Image(systemName: "xmark.circle.fill").foregroundStyle(LibraryColors.muted)
            }
            .font(.system(size: 17)).padding(.horizontal, 14).frame(height: 48)
            .background(LibraryColors.raised, in: Capsule())
            .shadow(color: .black.opacity(0.35), radius: 12, y: 8)
            Button(action: actions.close) {
                Image(systemName: "xmark").font(.system(size: 17, weight: .semibold)).foregroundStyle(LibraryColors.onBackground)
                    .frame(width: 48, height: 48).background(LibraryColors.raised, in: Circle())
                    .shadow(color: .black.opacity(0.35), radius: 12, y: 8)
            }
        }
    }
}

#endif
