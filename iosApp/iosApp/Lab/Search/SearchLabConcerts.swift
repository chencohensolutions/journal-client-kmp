#if DEBUG

import SwiftUI
import SharedLogic

/// The lab's answer to "concerts" for the search round of Nov 19, 2026: every third photo of the places library (its
/// towns and countries named), as the server could answer it. Shared by the round's options; deleted with the round.
enum SearchLabConcerts {
    private static let data = LabData.shared
    static let places = data.placesStories.places

    /// The matches, newest first.
    static let items: [MediaItem] = data.placesLibrary.days
        .flatMap { $0.items }
        .enumerated().filter { $0.offset % 3 == 0 }.map(\.element)
        .sorted { $0.takenAtMillis > $1.takenAtMillis }

    static func name(_ item: MediaItem) -> PlaceName? {
        places.location(item: item).flatMap { places.name(point: $0) }
    }

    static func town(_ item: MediaItem) -> String? { name(item)?.town }

    /// The matches by day, newest first, each with its towns.
    static func days(_ items: [MediaItem]) -> [SearchFeedDay] {
        var order: [String] = []
        var byDay: [String: [MediaItem]] = [:]
        for item in items {
            let key = item.day.description
            if byDay[key] == nil { order.append(key) }
            byDay[key, default: []].append(item)
        }
        return order.map { key in
            let dayItems = byDay[key]!
            var towns: [String] = []
            for town in dayItems.compactMap(town) where !towns.contains(town) { towns.append(town) }
            return SearchFeedDay(id: key, day: dayItems[0].day, towns: towns, items: dayItems)
        }
    }

    /// The towns of the matches with how many each, most first; "Elsewhere" for photos without a named place.
    static func towns(_ items: [MediaItem]) -> [SearchPlaceCount] {
        var counts: [String: Int] = [:]
        var countries: [String: String] = [:]
        for item in items {
            guard let name = name(item), let town = name.town else { continue }
            counts[town, default: 0] += 1
            if let country = name.country { countries[town] = country }
        }
        return counts.map { SearchPlaceCount(town: $0.key, country: countries[$0.key], count: $0.value) }
            .sorted { $0.count != $1.count ? $0.count > $1.count : $0.town < $1.town }
    }
}

/// A day of matches in a feed by date: its towns in order and its photos, newest first. (The shape the shared logic
/// would produce: SearchState.byDate.)
struct SearchFeedDay: Identifiable {
    let id: String
    let day: LocalDay
    let towns: [String]
    let items: [MediaItem]
}

/// A town among the matches, with how many photos: a place filter's choice.
struct SearchPlaceCount: Identifiable, Hashable {
    var id: String { town }
    let town: String
    let country: String?
    let count: Int
}

#endif
