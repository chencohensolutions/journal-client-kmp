#if DEBUG

import SwiftUI
import SharedLogic

// Fakes: the scores (SearchLabSorting, made up per photo) and the answer to "concerts" (SearchLabConcerts). The app's
// search keeps no scores today and sorts only best first. The pill, sheet, slider and chips work; photos don't open.
let searchOptionA3 = LabOption(id: "a3", title: "A3 · Sort sheet with a preview of the cut", note: "A Sort pill above the field opens the orders and the slider, with the weakest photos kept") {
    SearchOptionA3Lab(sorting: false)
}

let searchOptionA3Sheet = LabOption(id: "a3-sheet", title: "A3 · The sheet open", note: "Newest first, the slider, the weakest four kept") {
    SearchOptionA3Lab(sorting: true)
}

private struct SearchOptionA3Lab: View {
    let sorting: Bool
    @State private var sort = SearchSort.newest
    @State private var threshold = SearchLabSorting.usual
    @State private var town: String?

    var body: some View {
        SearchScreenA3(
            state: SearchOptionA3State(
                query: "concerts", items: SearchLabSorting.matches(sort: sort, threshold: threshold, town: town),
                sort: sort, threshold: threshold, town: town,
                weakest: SearchLabSorting.weakest(threshold: threshold, town: town)
            ),
            actions: SearchOptionA3Actions(sort: { sort = $0 }, threshold: { threshold = $0 }, town: { town = $0 }),
            sorting: sorting
        )
        .safeAreaPadding(.top, 44)
    }
}

#endif
