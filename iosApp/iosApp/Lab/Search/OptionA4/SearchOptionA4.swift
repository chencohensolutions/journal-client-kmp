#if DEBUG

import SwiftUI
import SharedLogic

// Search, option A4 (Nov 19, 2026): no menu and no slider. Two halves of one pill, "Most relevant" and "By date ↓"; a
// tap on By date while it is chosen turns its arrow (↓ newest first, ↑ oldest first). In the date orders a row of three
// steps sets the threshold, each with how many photos it keeps: Close · Good · Loose.

/// The three thresholds A4 offers instead of a slider.
enum SearchMatchStep: CaseIterable {
    case close, good, loose

    var threshold: Double {
        switch self {
        case .close: 0.19
        case .good: SearchLabSorting.usual
        case .loose: 0.08
        }
    }
}

struct SearchOptionA4State {
    let query: String
    let items: [MediaItem]
    let sort: SearchSort
    let step: SearchMatchStep
    let town: String?
    /// How many photos each step keeps, in the chosen town.
    let counts: [SearchMatchStep: Int]
}

struct SearchOptionA4Actions {
    var sort: (SearchSort) -> Void = { _ in }
    var step: (SearchMatchStep) -> Void = { _ in }
    var town: (String?) -> Void = { _ in }
    var openItem: (MediaItem) -> Void = { _ in }
}

struct SearchScreenA4: View {
    let state: SearchOptionA4State
    let actions: SearchOptionA4Actions

    var body: some View {
        GeometryReader { geometry in
            ZStack(alignment: .bottom) {
                LibraryColors.background.ignoresSafeArea()
                ScrollView {
                    LazyVStack(alignment: .leading, spacing: 0) {
                        Text("Search", tableName: "SearchOptionA4")
                            .font(.system(size: 30, weight: .bold)).foregroundStyle(LibraryColors.onBackground)
                            .padding(.horizontal, 16).padding(.top, 8)
                        Text("\(state.items.count) photos of “\(state.query)”", tableName: "SearchOptionA4")
                            .font(.footnote).foregroundStyle(LibraryColors.muted)
                            .padding(.horizontal, 16).padding(.top, 2)
                        order.padding(.top, 12)
                        if state.sort.byDate { steps.padding(.top, 10) }
                        SearchLabTownChips(chosen: state.town, choose: actions.town).padding(.top, 10)
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

    private var order: some View {
        HStack(spacing: 0) {
            segment(selected: !state.sort.byDate, action: { actions.sort(.relevant) }) {
                Image(systemName: "sparkles")
                Text("Most relevant", tableName: "SearchOptionA4")
            }
            segment(selected: state.sort.byDate, action: {
                // A second tap turns the arrow.
                actions.sort(state.sort == .newest ? .oldest : .newest)
            }) {
                Text("By date", tableName: "SearchOptionA4")
                Image(systemName: state.sort == .oldest ? "arrow.up" : "arrow.down")
            }
        }
        .padding(3)
        .background(LibraryColors.raised, in: Capsule())
        .padding(.horizontal, 16)
    }

    private func segment<Label: View>(selected: Bool, action: @escaping () -> Void, @ViewBuilder label: () -> Label) -> some View {
        Button(action: action) {
            HStack(spacing: 6) { label() }
                .font(.system(size: 14, weight: .semibold))
                .foregroundStyle(selected ? LibraryColors.onInverse : LibraryColors.onBackground)
                .frame(maxWidth: .infinity).frame(height: 34)
                .background(selected ? LibraryColors.inverse : .clear, in: Capsule())
        }
        .buttonStyle(.plain)
    }

    /// "Match: Close 18 · Good 31 · Loose 44".
    private var steps: some View {
        HStack(spacing: 8) {
            Text("Match", tableName: "SearchOptionA4").font(.system(size: 13, weight: .semibold)).foregroundStyle(LibraryColors.muted)
            ForEach(SearchMatchStep.allCases, id: \.self) { step in
                let selected = step == state.step
                Button { actions.step(step) } label: {
                    HStack(spacing: 5) {
                        Text(name(step), tableName: "SearchOptionA4").font(.system(size: 14, weight: .semibold))
                        Text(verbatim: "\(state.counts[step] ?? 0)").font(.system(size: 14)).opacity(0.7)
                    }
                    .foregroundStyle(selected ? LibraryColors.onInverse : LibraryColors.onBackground)
                    .padding(.horizontal, 12).frame(height: 32)
                    .background(selected ? LibraryColors.inverse : .clear, in: Capsule())
                    .overlay(Capsule().stroke(selected ? .clear : LibraryColors.muted.opacity(0.5), lineWidth: 1))
                }
                .buttonStyle(.plain)
            }
        }
        .padding(.horizontal, 16)
    }

    private func name(_ step: SearchMatchStep) -> LocalizedStringKey {
        switch step {
        case .close: "Close"
        case .good: "Good"
        case .loose: "Loose"
        }
    }
}

#endif
