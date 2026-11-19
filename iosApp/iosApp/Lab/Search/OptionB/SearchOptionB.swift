#if DEBUG

import SwiftUI
import SharedLogic

// Search, option B (Nov 19, 2026): the results as events, newest first. Each day in one town is one card (a concert):
// its cover across the card, a strip of a few more, the town and the date, how many photos. A tap opens it in place,
// every photo of it as a grid. Years head the list. A Place pill at the top opens a sheet of towns by country, with
// counts.

struct SearchEvent: Identifiable {
    let id: String
    let town: String?
    let country: String?
    let day: LocalDay
    let items: [MediaItem]
}

struct SearchOptionBState {
    let query: String
    /// Newest first.
    let events: [SearchEvent]
    let towns: [SearchPlaceCount]
    let chosenTown: String?
    let shown: Int
    /// The events opened in place.
    let opened: Set<String>
}

struct SearchOptionBActions {
    var chooseTown: (String?) -> Void = { _ in }
    var toggleEvent: (String) -> Void = { _ in }
    var openItem: (MediaItem) -> Void = { _ in }
}

struct SearchScreenB: View {
    let state: SearchOptionBState
    let actions: SearchOptionBActions
    @State private var choosingPlace = false
    @StateObject private var playback = FeedPlayback()

    var body: some View {
        GeometryReader { geometry in
            let width = geometry.size.width - 32
            ZStack(alignment: .bottom) {
                LibraryColors.background.ignoresSafeArea()
                ScrollView {
                    LazyVStack(alignment: .leading, spacing: 0) {
                        Text("Search", tableName: "SearchOptionB")
                            .font(.system(size: 30, weight: .bold)).foregroundStyle(LibraryColors.onBackground)
                            .padding(.top, 8)
                        HStack {
                            Text("\(state.events.count) events · \(state.shown) photos", tableName: "SearchOptionB")
                                .font(.footnote).foregroundStyle(LibraryColors.muted)
                            Spacer()
                            placePill
                        }
                        .padding(.top, 4)
                        ForEach(Array(state.events.enumerated()), id: \.element.id) { index, event in
                            if index == 0 || state.events[index - 1].day.year != event.day.year {
                                Text(verbatim: "\(event.day.year)")
                                    .font(.system(size: 22, weight: .bold)).foregroundStyle(LibraryColors.onBackground)
                                    .padding(.top, 24).padding(.bottom, 8)
                            }
                            card(event, width: width).padding(.bottom, 14)
                        }
                        Color.clear.frame(height: 120)
                    }
                    .padding(.horizontal, 16)
                }
                .scrollIndicators(.hidden)
                field.padding(.horizontal, 16).padding(.bottom, 8)
            }
        }
        .sheet(isPresented: $choosingPlace) {
            PlaceSheetB(towns: state.towns, chosen: state.chosenTown) { town in
                actions.chooseTown(town)
                choosingPlace = false
            }
            .presentationDetents([.medium, .large])
        }
    }

    /// "📍 All places ▾", or the town chosen, filled.
    private var placePill: some View {
        Button { choosingPlace = true } label: {
            HStack(spacing: 6) {
                Image(systemName: "mappin.and.ellipse").font(.system(size: 12, weight: .semibold))
                if let town = state.chosenTown { Text(verbatim: town) } else { Text("All places", tableName: "SearchOptionB") }
                Image(systemName: "chevron.down").font(.system(size: 11, weight: .bold))
            }
            .font(.system(size: 14, weight: .semibold))
            .foregroundStyle(state.chosenTown != nil ? LibraryColors.onInverse : LibraryColors.onBackground)
            .padding(.horizontal, 14).frame(height: 36)
            .background(state.chosenTown != nil ? LibraryColors.inverse : .clear, in: Capsule())
            .overlay(Capsule().stroke(state.chosenTown != nil ? .clear : LibraryColors.muted.opacity(0.5), lineWidth: 1))
        }
        .buttonStyle(.plain)
    }

    private func card(_ event: SearchEvent, width: CGFloat) -> some View {
        let open = state.opened.contains(event.id)
        return VStack(alignment: .leading, spacing: 0) {
            FeedPhoto(item: event.items[0], size: CGSize(width: width, height: width * 0.62), playback: playback)
            if open {
                let side = (width - 4) / 3
                LazyVGrid(columns: Array(repeating: GridItem(.fixed(side), spacing: 2), count: 3), spacing: 2) {
                    ForEach(event.items.dropFirst(), id: \.id) { item in
                        FeedPhoto(item: item, size: CGSize(width: side, height: side), playback: playback)
                            .onTapGesture { actions.openItem(item) }
                    }
                }
                .padding(.top, 2)
            } else if event.items.count > 1 {
                let more = Array(event.items.dropFirst().prefix(4))
                let side = (width - CGFloat(more.count - 1) * 2) / CGFloat(max(more.count, 1))
                HStack(spacing: 2) {
                    ForEach(more, id: \.id) { item in
                        FeedPhoto(item: item, size: CGSize(width: side, height: 64), playback: playback)
                    }
                }
                .padding(.top, 2)
            }
            HStack(alignment: .firstTextBaseline) {
                VStack(alignment: .leading, spacing: 2) {
                    Text(verbatim: [event.town, event.country].compactMap { $0 }.joined(separator: ", ").ifEmpty("—"))
                        .font(.system(size: 16, weight: .semibold)).foregroundStyle(LibraryColors.onBackground)
                    Text("\(event.day.label) · \(event.items.count) photos", tableName: "SearchOptionB")
                        .font(.system(size: 13)).foregroundStyle(LibraryColors.muted)
                }
                Spacer()
                Image(systemName: open ? "chevron.up" : "chevron.down").foregroundStyle(LibraryColors.muted)
            }
            .padding(14)
        }
        .background(LibraryColors.raised)
        .clipShape(RoundedRectangle(cornerRadius: 16))
        .contentShape(RoundedRectangle(cornerRadius: 16))
        .onTapGesture { actions.toggleEvent(event.id) }
    }

    private var field: some View {
        HStack(spacing: 8) {
            Image(systemName: "magnifyingglass").foregroundStyle(LibraryColors.muted)
            Text(verbatim: state.query).foregroundStyle(LibraryColors.onBackground)
            Spacer()
            Image(systemName: "xmark.circle.fill").foregroundStyle(LibraryColors.muted)
        }
        .font(.system(size: 17)).padding(.horizontal, 14).frame(height: 48)
        .background(LibraryColors.raised, in: Capsule())
        .shadow(color: .black.opacity(0.35), radius: 12, y: 8)
    }
}

/// The towns of the matches by country, each with its count; ✓ on the chosen one; All places on top.
private struct PlaceSheetB: View {
    let towns: [SearchPlaceCount]
    let chosen: String?
    let choose: (String?) -> Void

    var body: some View {
        let countries = Dictionary(grouping: towns) { $0.country ?? "" }
            .sorted { $0.value.reduce(0) { $0 + $1.count } > $1.value.reduce(0) { $0 + $1.count } }
        List {
            row(Text("All places", tableName: "SearchOptionB"), count: towns.reduce(0) { $0 + $1.count }, checked: chosen == nil) { choose(nil) }
            ForEach(countries, id: \.key) { country, towns in
                Section(country) {
                    ForEach(towns) { town in
                        row(Text(verbatim: town.town), count: town.count, checked: chosen == town.town) { choose(town.town) }
                    }
                }
            }
        }
        .listStyle(.insetGrouped)
        .scrollContentBackground(.hidden)
        .background(LibraryColors.background)
    }

    private func row(_ label: Text, count: Int, checked: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HStack {
                label.foregroundStyle(LibraryColors.onBackground)
                Spacer()
                Text(verbatim: "\(count)").foregroundStyle(LibraryColors.muted)
                if checked { Image(systemName: "checkmark").foregroundStyle(LibraryColors.accent) }
            }
        }
        .listRowBackground(LibraryColors.raised)
    }
}

private extension String {
    func ifEmpty(_ other: String) -> String { isEmpty ? other : self }
}

#endif
