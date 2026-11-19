#if DEBUG

import SwiftUI
import SharedLogic

// Sorting search results, the round's second step (hen, Nov 19, 2026): Most relevant (best first, every match, no
// threshold), Newest first or Oldest first (by date, only the matches at or over a threshold the user sets). Shared by
// options a2, a3 and a4; deleted with the round.

/// How the matches are listed. (The shape the shared logic would produce: SearchState.order.)
enum SearchSort: CaseIterable {
    case relevant, newest, oldest

    var byDate: Bool { self != .relevant }
}

/// The lab's made-up scores and how a threshold cuts them. The server answers each hit with a score (SearchHit.score,
/// cosine similarity, a good match often 0.1 to 0.2); the app keeps the best 1000 and cuts them here.
enum SearchLabSorting {
    static let lowest = 0.04
    static let highest = 0.26
    /// Where a date order starts: about the closest half.
    static let usual = 0.14

    /// A made-up score per photo, 0.04 to 0.26, steady across runs (Swift's hashValue isn't).
    static func score(_ item: MediaItem) -> Double {
        let n = Int(item.id) ?? item.id.unicodeScalars.reduce(0) { $0 + Int($1.value) }
        return lowest + Double((n * 37) % 23) / 100
    }

    /// The matches in `town` (nil: all), sorted, cut at `threshold` in the date orders.
    static func matches(sort: SearchSort, threshold: Double, town: String?) -> [MediaItem] {
        let all = SearchLabConcerts.items.filter { town == nil || SearchLabConcerts.town($0) == town }
        switch sort {
        case .relevant: return all.sorted { score($0) != score($1) ? score($0) > score($1) : $0.takenAtMillis > $1.takenAtMillis }
        case .newest: return all.filter { score($0) >= threshold }.sorted { $0.takenAtMillis > $1.takenAtMillis }
        case .oldest: return all.filter { score($0) >= threshold }.sorted { $0.takenAtMillis < $1.takenAtMillis }
        }
    }

    /// How many a threshold keeps, in `town`.
    static func count(threshold: Double, town: String?) -> Int {
        SearchLabConcerts.items.filter { (town == nil || SearchLabConcerts.town($0) == town) && score($0) >= threshold }.count
    }

    /// The matches just over the threshold: the weakest still shown, to see where the cut falls.
    static func weakest(threshold: Double, town: String?, count: Int = 4) -> [MediaItem] {
        SearchLabConcerts.items
            .filter { (town == nil || SearchLabConcerts.town($0) == town) && score($0) >= threshold }
            .sorted { score($0) < score($1) }
            .prefix(count).map { $0 }
    }
}

/// The results under a screen's controls: days (date orders, each with its day line) or one list best first, then the
/// photos across the screen. As option A draws them.
struct SearchSortedResults: View {
    let items: [MediaItem]
    let sort: SearchSort
    let width: CGFloat
    var openItem: (MediaItem) -> Void = { _ in }
    @StateObject private var playback = FeedPlayback()

    var body: some View {
        if sort.byDate {
            ForEach(SearchLabConcerts.days(items)) { day in
                DayLine(label: day.day.label).padding(.horizontal, 16).padding(.top, 28)
                dayLine(day).frame(maxWidth: .infinity).padding(.top, 4).padding(.bottom, 10)
                ForEach(day.items, id: \.id) { photo($0) }
            }
        } else {
            ForEach(items, id: \.id) { item in
                photo(item)
                caption(item)
            }
        }
    }

    private func dayLine(_ day: SearchFeedDay) -> some View {
        let towns = day.towns.joined(separator: " → ")
        return Group {
            if towns.isEmpty {
                Text("\(day.items.count) photos", tableName: "SearchSorting")
            } else {
                Text("\(towns) · \(day.items.count) photos", tableName: "SearchSorting")
            }
        }
        .font(.system(size: 12)).foregroundStyle(LibraryColors.muted)
    }

    /// Best first, as search is now: the date and town under each photo, since they jump about.
    private func caption(_ item: MediaItem) -> some View {
        HStack(spacing: 6) {
            Text(verbatim: item.day.label).font(.system(size: 14, weight: .semibold)).foregroundStyle(LibraryColors.onBackground)
            Spacer()
            if let town = SearchLabConcerts.town(item) {
                Image(systemName: "mappin").font(.system(size: 12))
                Text(verbatim: town).font(.system(size: 14))
            }
        }
        .foregroundStyle(LibraryColors.muted)
        .padding(.horizontal, 16).padding(.top, 6).padding(.bottom, 14)
    }

    private func photo(_ item: MediaItem) -> some View {
        let ratio = item.width > 0 && item.height > 0 ? CGFloat(item.width) / CGFloat(item.height) : 4 / 3
        let height = width / min(max(ratio, 0.6), 3)
        return FeedPhoto(item: item, size: CGSize(width: width, height: height), playback: playback)
            .onTapGesture { openItem(item) }
            .padding(.bottom, 2)
    }
}

/// The field as the app has it, with the words searched, and the round ✕ beside it. As option A's.
struct SearchLabField: View {
    let query: String

    var body: some View {
        HStack(spacing: 10) {
            HStack(spacing: 8) {
                Image(systemName: "magnifyingglass").foregroundStyle(LibraryColors.muted)
                Text(verbatim: query).foregroundStyle(LibraryColors.onBackground)
                Spacer()
                Image(systemName: "xmark.circle.fill").foregroundStyle(LibraryColors.muted)
            }
            .font(.system(size: 17)).padding(.horizontal, 14).frame(height: 48)
            .background(LibraryColors.raised, in: Capsule())
            .shadow(color: .black.opacity(0.35), radius: 12, y: 8)
            Image(systemName: "xmark").font(.system(size: 17, weight: .semibold)).foregroundStyle(LibraryColors.onBackground)
                .frame(width: 48, height: 48).background(LibraryColors.raised, in: Circle())
                .shadow(color: .black.opacity(0.35), radius: 12, y: 8)
        }
    }
}

/// "All places 38 · Tel Aviv 14 · …", one town at a time, as option A's chips.
struct SearchLabTownChips: View {
    let chosen: String?
    let choose: (String?) -> Void

    var body: some View {
        ScrollView(.horizontal) {
            HStack(spacing: 8) {
                chip(Text("All places", tableName: "SearchSorting"), count: SearchLabConcerts.items.count, selected: chosen == nil) { choose(nil) }
                ForEach(SearchLabConcerts.towns(SearchLabConcerts.items)) { town in
                    chip(Text(verbatim: town.town), count: town.count, selected: chosen == town.town) {
                        choose(chosen == town.town ? nil : town.town)
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
}

#endif
