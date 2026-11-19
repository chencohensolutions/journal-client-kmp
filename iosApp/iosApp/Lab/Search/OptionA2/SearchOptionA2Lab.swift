#if DEBUG

import SwiftUI
import SharedLogic

// Fakes: the scores (SearchLabSorting, made up per photo) and the answer to "concerts" (SearchLabConcerts). The app's
// search keeps no scores today and sorts only best first. The menu, slider and chips work; photos don't open.
let searchOptionA2 = LabOption(id: "a2", title: "A2 · Sort menu, Match slider", note: "Most relevant · Newest first · Oldest first; a slider for how close in the date orders") {
    SearchOptionA2Lab()
}

private struct SearchOptionA2Lab: View {
    @State private var sort = SearchSort.newest
    @State private var threshold = SearchLabSorting.usual
    @State private var town: String?

    var body: some View {
        SearchScreenA2(
            state: SearchOptionA2State(
                query: "concerts", items: SearchLabSorting.matches(sort: sort, threshold: threshold, town: town),
                sort: sort, threshold: threshold, town: town
            ),
            actions: SearchOptionA2Actions(sort: { sort = $0 }, threshold: { threshold = $0 }, town: { town = $0 })
        )
        .safeAreaPadding(.top, 44)
    }
}

#endif
