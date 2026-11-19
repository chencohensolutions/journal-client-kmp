#if DEBUG

import SwiftUI
import SharedLogic

// Fakes: the answer to "concerts" is every third photo of the places library (SearchLabConcerts); the app's search
// today answers best first only, and its town filter asks the server again (here the chips filter what is already
// shown). "Best match" shows the same photos in a made-up order. The places come from the lab's named towns.
let searchOptionA = LabOption(id: "a", title: "A · Feed by date, place chips", note: "Newest first like Moments, a row of town chips, Best match a tap away") {
    SearchOptionALab()
}

private struct SearchOptionALab: View {
    @State private var bestFirst = false
    @State private var town: String?

    var body: some View {
        let all = SearchLabConcerts.items
        let chosen = town.map { t in all.filter { SearchLabConcerts.town($0) == t } } ?? all
        let days = bestFirst
            ? [SearchFeedDay(id: "best", day: chosen[0].day, towns: [], items: chosen.sorted { $0.id.hashValue < $1.id.hashValue })]
            : SearchLabConcerts.days(chosen)
        SearchScreenA(
            state: SearchOptionAState(
                query: "concerts", days: days, bestFirst: bestFirst, towns: SearchLabConcerts.towns(all), chosenTown: town,
                total: all.count, shown: chosen.count
            ),
            actions: SearchOptionAActions(setBestFirst: { bestFirst = $0 }, chooseTown: { town = $0 })
        )
        // Below the lab's back button.
        .safeAreaPadding(.top, 44)
    }
}

#endif
