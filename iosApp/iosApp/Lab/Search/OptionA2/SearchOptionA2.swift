#if DEBUG

import SwiftUI
import SharedLogic

// Search, option A2 (Nov 19, 2026): option A with a Sort menu (Most relevant · Newest first · Oldest first) in place of
// its two-way switch, and, in the date orders, a Match slider under it: left keeps only close matches, right lets more
// in, the count beside it following the thumb. Most relevant shows every match best first, with no slider.

struct SearchOptionA2State {
    let query: String
    let items: [MediaItem]
    let sort: SearchSort
    let threshold: Double
    let town: String?
}

struct SearchOptionA2Actions {
    var sort: (SearchSort) -> Void = { _ in }
    var threshold: (Double) -> Void = { _ in }
    var town: (String?) -> Void = { _ in }
    var openItem: (MediaItem) -> Void = { _ in }
}

struct SearchScreenA2: View {
    let state: SearchOptionA2State
    let actions: SearchOptionA2Actions

    var body: some View {
        GeometryReader { geometry in
            ZStack(alignment: .bottom) {
                LibraryColors.background.ignoresSafeArea()
                ScrollView {
                    LazyVStack(alignment: .leading, spacing: 0) {
                        Text("Search", tableName: "SearchOptionA2")
                            .font(.system(size: 30, weight: .bold)).foregroundStyle(LibraryColors.onBackground)
                            .padding(.horizontal, 16).padding(.top, 8)
                        HStack(spacing: 10) {
                            sortMenu
                            Text("\(state.items.count) photos", tableName: "SearchOptionA2")
                                .font(.footnote).foregroundStyle(LibraryColors.muted)
                        }
                        .padding(.horizontal, 16).padding(.top, 10)
                        if state.sort.byDate { slider.padding(.horizontal, 16).padding(.top, 12) }
                        SearchLabTownChips(chosen: state.town, choose: actions.town).padding(.top, 12)
                        SearchSortedResults(items: state.items, sort: state.sort, width: geometry.size.width, openItem: actions.openItem)
                            .padding(.top, state.sort.byDate ? 0 : 16)
                        Color.clear.frame(height: 120)
                    }
                }
                .scrollIndicators(.hidden)
                SearchLabField(query: state.query).padding(.horizontal, 16).padding(.bottom, 8)
            }
        }
    }

    /// "↓ Newest first ⌄": a pill that opens the three orders.
    private var sortMenu: some View {
        Menu {
            Picker(selection: Binding(get: { state.sort }, set: actions.sort)) {
                Label { Text("Most relevant", tableName: "SearchOptionA2") } icon: { Image(systemName: "sparkles") }.tag(SearchSort.relevant)
                Label { Text("Newest first", tableName: "SearchOptionA2") } icon: { Image(systemName: "arrow.down") }.tag(SearchSort.newest)
                Label { Text("Oldest first", tableName: "SearchOptionA2") } icon: { Image(systemName: "arrow.up") }.tag(SearchSort.oldest)
            } label: { EmptyView() }
        } label: {
            HStack(spacing: 6) {
                Image(systemName: icon).font(.system(size: 13, weight: .semibold))
                Text(name, tableName: "SearchOptionA2").font(.system(size: 14, weight: .semibold))
                Image(systemName: "chevron.down").font(.system(size: 11, weight: .semibold))
            }
            .foregroundStyle(LibraryColors.onInverse)
            .padding(.horizontal, 14).frame(height: 36)
            .background(LibraryColors.inverse, in: Capsule())
        }
    }

    private var name: LocalizedStringKey {
        switch state.sort {
        case .relevant: "Most relevant"
        case .newest: "Newest first"
        case .oldest: "Oldest first"
        }
    }

    private var icon: String {
        switch state.sort {
        case .relevant: "sparkles"
        case .newest: "arrow.down"
        case .oldest: "arrow.up"
        }
    }

    /// Match: Close ——●—— More, on a card.
    private var slider: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text("How close a match", tableName: "SearchOptionA2")
                .font(.system(size: 13, weight: .semibold)).foregroundStyle(LibraryColors.muted)
            HStack(spacing: 10) {
                Text("Close", tableName: "SearchOptionA2").font(.system(size: 13)).foregroundStyle(LibraryColors.muted)
                // Right lets more in: the slider runs from the highest threshold to the lowest.
                Slider(
                    value: Binding(
                        get: { SearchLabSorting.highest + SearchLabSorting.lowest - state.threshold },
                        set: { actions.threshold(SearchLabSorting.highest + SearchLabSorting.lowest - $0) }
                    ),
                    in: SearchLabSorting.lowest...SearchLabSorting.highest
                )
                .tint(LibraryColors.accent)
                Text("More", tableName: "SearchOptionA2").font(.system(size: 13)).foregroundStyle(LibraryColors.muted)
            }
        }
        .padding(.horizontal, 14).padding(.vertical, 10)
        .background(LibraryColors.raised, in: RoundedRectangle(cornerRadius: 16))
    }
}

#endif
