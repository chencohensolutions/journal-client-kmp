#if DEBUG

import SwiftUI
import SharedLogic

// Fakes: the scores (SearchLabSorting, made up per photo) and the answer to "concerts" (SearchLabConcerts); the three
// steps' thresholds are guesses to tune on real answers. The order, steps and chips work; photos don't open.
let searchOptionA4 = LabOption(id: "a4", title: "A4 · Relevant | By date ↓↑, three match steps", note: "Tap By date again to turn the order; Close · Good · Loose instead of a slider") {
    SearchOptionA4Lab()
}

private struct SearchOptionA4Lab: View {
    @State private var sort = SearchSort.newest
    @State private var step = SearchMatchStep.good
    @State private var town: String?

    var body: some View {
        SearchScreenA4(
            state: SearchOptionA4State(
                query: "concerts", items: SearchLabSorting.matches(sort: sort, threshold: step.threshold, town: town),
                sort: sort, step: step, town: town,
                counts: Dictionary(uniqueKeysWithValues: SearchMatchStep.allCases.map { ($0, SearchLabSorting.count(threshold: $0.threshold, town: town)) })
            ),
            actions: SearchOptionA4Actions(sort: { sort = $0 }, step: { step = $0 }, town: { town = $0 })
        )
        .safeAreaPadding(.top, 44)
    }
}

#endif
