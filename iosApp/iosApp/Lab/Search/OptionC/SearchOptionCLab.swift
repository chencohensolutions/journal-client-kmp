#if DEBUG

import SwiftUI
import MapKit
import SharedLogic

// Fakes: the answer to "concerts" is every third photo of the places library (SearchLabConcerts); each town's bubble
// sits where its first photo was taken. The map needs the network for its tiles (MapKit), as Explore's map does.
let searchOptionC = LabOption(id: "c", title: "C · Map and feed", note: "A map of the matches' towns on top; tap a bubble to narrow the feed by date") {
    SearchOptionCLab()
}

private struct SearchOptionCLab: View {
    @State private var town: String?

    var body: some View {
        let all = SearchLabConcerts.items
        let chosen = town.map { t in all.filter { SearchLabConcerts.town($0) == t } } ?? all
        SearchScreenC(
            state: SearchOptionCState(query: "concerts", start: start(all), towns: mapTowns(all), chosenTown: town, days: SearchLabConcerts.days(chosen), shown: chosen.count),
            actions: SearchOptionCActions(chooseTown: { town = $0 })
        )
        // Below the lab's back button.
        .safeAreaPadding(.top, 44)
    }

    /// Around the towns of the country with the most matches.
    private func start(_ items: [MediaItem]) -> MKCoordinateRegion {
        let counts = SearchLabConcerts.towns(items)
        let country = counts.first?.country
        let points = mapTowns(items).filter { town in counts.first { $0.town == town.town }?.country == country }
        let lats = points.map(\.latitude), lons = points.map(\.longitude)
        guard let minLat = lats.min(), let maxLat = lats.max(), let minLon = lons.min(), let maxLon = lons.max() else {
            return MKCoordinateRegion(center: CLLocationCoordinate2D(latitude: 32, longitude: 35), span: MKCoordinateSpan(latitudeDelta: 5, longitudeDelta: 5))
        }
        return MKCoordinateRegion(
            center: CLLocationCoordinate2D(latitude: (minLat + maxLat) / 2, longitude: (minLon + maxLon) / 2),
            span: MKCoordinateSpan(latitudeDelta: (maxLat - minLat) * 1.6 + 0.5, longitudeDelta: (maxLon - minLon) * 1.6 + 0.5)
        )
    }

    private func mapTowns(_ items: [MediaItem]) -> [SearchMapTown] {
        SearchLabConcerts.towns(items).compactMap { count in
            guard let item = items.first(where: { SearchLabConcerts.town($0) == count.town }),
                  let point = SearchLabConcerts.places.location(item: item) else { return nil }
            return SearchMapTown(town: count.town, latitude: point.latitude, longitude: point.longitude, count: count.count)
        }
    }
}

#endif
