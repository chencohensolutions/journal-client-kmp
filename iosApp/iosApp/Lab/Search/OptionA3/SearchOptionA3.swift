#if DEBUG

import SwiftUI
import SharedLogic

// Search, option A3 (Nov 19, 2026): the controls leave the page for a sheet. A Sort pill floats above the field, as the
// Filter pill does ("Newest first · 31"); it opens a sheet with the three orders as rows and, for the date orders, the
// Match slider with the photos the cut keeps and, under it, the weakest of them, so you see where the cut falls before
// closing. The page is the results alone, under the title and town chips.

struct SearchOptionA3State {
    let query: String
    let items: [MediaItem]
    let sort: SearchSort
    let threshold: Double
    let town: String?
    /// The matches just over the threshold, weakest first: what the slider would drop next.
    let weakest: [MediaItem]
}

struct SearchOptionA3Actions {
    var sort: (SearchSort) -> Void = { _ in }
    var threshold: (Double) -> Void = { _ in }
    var town: (String?) -> Void = { _ in }
    var openItem: (MediaItem) -> Void = { _ in }
}

struct SearchScreenA3: View {
    let state: SearchOptionA3State
    let actions: SearchOptionA3Actions
    @State var sorting: Bool

    var body: some View {
        GeometryReader { geometry in
            ZStack(alignment: .bottom) {
                LibraryColors.background.ignoresSafeArea()
                ScrollView {
                    LazyVStack(alignment: .leading, spacing: 0) {
                        Text("Search", tableName: "SearchOptionA3")
                            .font(.system(size: 30, weight: .bold)).foregroundStyle(LibraryColors.onBackground)
                            .padding(.horizontal, 16).padding(.top, 8)
                        SearchLabTownChips(chosen: state.town, choose: actions.town).padding(.top, 12)
                        SearchSortedResults(items: state.items, sort: state.sort, width: geometry.size.width, openItem: actions.openItem)
                            .padding(.top, state.sort.byDate ? 0 : 16)
                        Color.clear.frame(height: 160)
                    }
                }
                .scrollIndicators(.hidden)
                VStack(spacing: 10) {
                    sortPill
                    SearchLabField(query: state.query).padding(.horizontal, 16)
                }
                .padding(.bottom, 8)
            }
        }
        .sheet(isPresented: $sorting) {
            // Tall enough for the slider, the weakest photos and the Show button without scrolling.
            sheet.presentationDetents([state.sort.byDate ? .fraction(0.62) : .medium, .large]).presentationDragIndicator(.visible)
        }
    }

    /// "↓ Newest first · 31" or "✦ Most relevant · 46", dark, above the field.
    private var sortPill: some View {
        Button { sorting = true } label: {
            HStack(spacing: 6) {
                Image(systemName: icon(state.sort)).font(.system(size: 13, weight: .semibold))
                Text(name(state.sort), tableName: "SearchOptionA3").font(.system(size: 14, weight: .semibold))
                Text(verbatim: "· \(state.items.count)").font(.system(size: 14, weight: .semibold))
            }
            .foregroundStyle(LibraryColors.onInverse)
            .padding(.horizontal, 16).frame(height: 38)
            .background(LibraryColors.inverse, in: Capsule())
            .shadow(color: .black.opacity(0.3), radius: 10, y: 6)
        }
        .buttonStyle(.plain)
    }

    private var sheet: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                Text("Sort", tableName: "SearchOptionA3")
                    .font(.system(size: 22, weight: .bold)).foregroundStyle(LibraryColors.onBackground)
                    .padding(.horizontal, 24).padding(.top, 24).padding(.bottom, 8)
                ForEach(SearchSort.allCases, id: \.self) { sort in
                    Button { actions.sort(sort) } label: {
                        HStack(spacing: 12) {
                            Image(systemName: icon(sort)).frame(width: 22)
                            VStack(alignment: .leading, spacing: 2) {
                                Text(name(sort), tableName: "SearchOptionA3").font(.system(size: 16, weight: .semibold))
                                Text(hint(sort), tableName: "SearchOptionA3").font(.system(size: 13)).foregroundStyle(LibraryColors.muted)
                            }
                            Spacer()
                            if sort == state.sort { Image(systemName: "checkmark").font(.system(size: 15, weight: .semibold)) }
                        }
                        .foregroundStyle(LibraryColors.onBackground)
                        .padding(.horizontal, 24).padding(.vertical, 10)
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                }
                if state.sort.byDate {
                    Text("How close a match", tableName: "SearchOptionA3")
                        .font(.system(size: 13, weight: .semibold)).foregroundStyle(LibraryColors.muted)
                        .padding(.horizontal, 24).padding(.top, 18)
                    HStack(spacing: 10) {
                        Text("Close", tableName: "SearchOptionA3").font(.system(size: 13)).foregroundStyle(LibraryColors.muted)
                        Slider(
                            value: Binding(
                                get: { SearchLabSorting.highest + SearchLabSorting.lowest - state.threshold },
                                set: { actions.threshold(SearchLabSorting.highest + SearchLabSorting.lowest - $0) }
                            ),
                            in: SearchLabSorting.lowest...SearchLabSorting.highest
                        )
                        .tint(LibraryColors.accent)
                        Text("More", tableName: "SearchOptionA3").font(.system(size: 13)).foregroundStyle(LibraryColors.muted)
                    }
                    .padding(.horizontal, 24)
                    Text("\(state.items.count) photos. The weakest kept:", tableName: "SearchOptionA3")
                        .font(.system(size: 13)).foregroundStyle(LibraryColors.muted)
                        .padding(.horizontal, 24).padding(.top, 10)
                    HStack(spacing: 2) {
                        ForEach(state.weakest, id: \.id) { item in
                            FeedPhoto(item: item, size: CGSize(width: 80, height: 80), playback: FeedPlayback())
                                .frame(width: 80, height: 80).clipped()
                        }
                    }
                    .clipShape(RoundedRectangle(cornerRadius: 12))
                    .padding(.horizontal, 24).padding(.top, 8)
                }
                Button { sorting = false } label: {
                    Text("Show \(state.items.count) photos", tableName: "SearchOptionA3")
                        .font(.system(size: 16, weight: .semibold)).foregroundStyle(LibraryColors.onInverse)
                        .frame(maxWidth: .infinity).frame(height: 50)
                        .background(LibraryColors.inverse, in: Capsule())
                }
                .buttonStyle(.plain)
                .padding(.horizontal, 24).padding(.top, 22).padding(.bottom, 16)
            }
        }
        .background(LibraryColors.background)
    }

    private func name(_ sort: SearchSort) -> LocalizedStringKey {
        switch sort {
        case .relevant: "Most relevant"
        case .newest: "Newest first"
        case .oldest: "Oldest first"
        }
    }

    private func hint(_ sort: SearchSort) -> LocalizedStringKey {
        switch sort {
        case .relevant: "Every match, the closest first"
        case .newest: "Close matches by date, the latest first"
        case .oldest: "Close matches by date, the earliest first"
        }
    }

    private func icon(_ sort: SearchSort) -> String {
        switch sort {
        case .relevant: "sparkles"
        case .newest: "arrow.down"
        case .oldest: "arrow.up"
        }
    }
}

#endif
