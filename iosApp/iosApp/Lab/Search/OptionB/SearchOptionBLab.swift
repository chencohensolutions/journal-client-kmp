#if DEBUG

import SwiftUI
import SharedLogic

// Fakes: the answer to "concerts" is every third photo of the places library (SearchLabConcerts). An event is a day
// in one town; the app would need the server's moments (or the phone's places) for a real night out. The covers are
// each event's newest photo, not its best. The places come from the lab's named towns.
let searchOptionB = LabOption(id: "b", title: "B · Events", note: "Each concert a card (town, date, count); tap to open it; Place pill and sheet") {
    SearchOptionBLab()
}

private struct SearchOptionBLab: View {
    @State private var town: String?
    @State private var opened: Set<String> = []

    var body: some View {
        let all = SearchLabConcerts.items
        let chosen = town.map { t in all.filter { SearchLabConcerts.town($0) == t } } ?? all
        SearchScreenB(
            state: SearchOptionBState(
                query: "concerts", events: events(chosen), towns: SearchLabConcerts.towns(all), chosenTown: town, shown: chosen.count, opened: opened
            ),
            actions: SearchOptionBActions(
                chooseTown: { town = $0 },
                toggleEvent: { id in if opened.contains(id) { opened.remove(id) } else { opened.insert(id) } }
            )
        )
        // Below the lab's back button.
        .safeAreaPadding(.top, 44)
    }

    private func events(_ items: [MediaItem]) -> [SearchEvent] {
        var order: [String] = []
        var groups: [String: [MediaItem]] = [:]
        for item in items {
            let key = "\(item.day.description)|\(SearchLabConcerts.town(item) ?? "")"
            if groups[key] == nil { order.append(key) }
            groups[key, default: []].append(item)
        }
        return order.map { key in
            let members = groups[key]!
            let name = SearchLabConcerts.name(members[0])
            return SearchEvent(id: key, town: name?.town, country: name?.country, day: members[0].day, items: members)
        }
    }
}

#endif
