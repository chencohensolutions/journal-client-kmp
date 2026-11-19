#if DEBUG

import SwiftUI
import MapKit
import SharedLogic

// Search, option C (Nov 19, 2026): a small map of where the matches were taken on top, a bubble per town with its
// count; tapping one narrows the feed under it to that town (the bubble fills), and "All places" takes it back. The
// feed is by date, newest first, a day line and its photos as small tiles, three across, so a few years fit a scroll.

struct SearchMapTown: Identifiable, Hashable {
    var id: String { town }
    let town: String
    let latitude: Double
    let longitude: Double
    let count: Int
}

struct SearchOptionCState {
    let query: String
    /// Where the map starts: around the country with the most matches (the others a pan or pinch away).
    let start: MKCoordinateRegion
    let towns: [SearchMapTown]
    let chosenTown: String?
    let days: [SearchFeedDay]
    let shown: Int
}

struct SearchOptionCActions {
    var chooseTown: (String?) -> Void = { _ in }
    var openItem: (MediaItem) -> Void = { _ in }
}

struct SearchScreenC: View {
    let state: SearchOptionCState
    let actions: SearchOptionCActions
    @StateObject private var playback = FeedPlayback()

    var body: some View {
        GeometryReader { geometry in
            let side = (geometry.size.width - 4) / 3
            ZStack(alignment: .bottom) {
                LibraryColors.background.ignoresSafeArea()
                ScrollView {
                    LazyVStack(alignment: .leading, spacing: 0) {
                        Text("Search", tableName: "SearchOptionC")
                            .font(.system(size: 30, weight: .bold)).foregroundStyle(LibraryColors.onBackground)
                            .padding(.horizontal, 16).padding(.top, 8)
                        map.frame(height: 230).clipShape(RoundedRectangle(cornerRadius: 16)).padding(.horizontal, 16).padding(.top, 8)
                        HStack {
                            Group {
                                if let town = state.chosenTown {
                                    Text("\(state.shown) photos in \(town), newest first", tableName: "SearchOptionC")
                                } else {
                                    Text("\(state.shown) photos, newest first", tableName: "SearchOptionC")
                                }
                            }
                            .font(.footnote).foregroundStyle(LibraryColors.muted)
                            Spacer()
                            if state.chosenTown != nil {
                                Button { actions.chooseTown(nil) } label: {
                                    Text("All places", tableName: "SearchOptionC").font(.system(size: 14, weight: .semibold))
                                        .foregroundStyle(LibraryColors.onBackground)
                                }
                            }
                        }
                        .padding(.horizontal, 16).padding(.top, 10)
                        ForEach(state.days) { day in
                            DayLine(label: day.day.label).padding(.horizontal, 16).padding(.top, 22).padding(.bottom, 4)
                            Text(verbatim: day.towns.joined(separator: " → "))
                                .font(.system(size: 12)).foregroundStyle(LibraryColors.muted)
                                .frame(maxWidth: .infinity).padding(.bottom, 8)
                            LazyVGrid(columns: Array(repeating: GridItem(.fixed(side), spacing: 2), count: 3), spacing: 2) {
                                ForEach(day.items, id: \.id) { item in
                                    FeedPhoto(item: item, size: CGSize(width: side, height: side), playback: playback)
                                        .onTapGesture { actions.openItem(item) }
                                }
                            }
                        }
                        Color.clear.frame(height: 120)
                    }
                }
                .scrollIndicators(.hidden)
                field.padding(.horizontal, 16).padding(.bottom, 8)
            }
        }
    }

    private var map: some View {
        Map(initialPosition: .region(state.start)) {
            ForEach(state.towns) { town in
                Annotation("", coordinate: CLLocationCoordinate2D(latitude: town.latitude, longitude: town.longitude)) {
                    let chosen = state.chosenTown == town.town
                    Button { actions.chooseTown(chosen ? nil : town.town) } label: {
                        Text(verbatim: "\(town.town) \(town.count)")
                            .font(.system(size: 12, weight: .bold))
                            .foregroundStyle(chosen ? LibraryColors.onInverse : LibraryColors.onBackground)
                            .frame(minWidth: 30, minHeight: 30)
                            .padding(.horizontal, 4)
                            .background(chosen ? LibraryColors.inverse : LibraryColors.raised, in: Capsule())
                            .overlay(Capsule().stroke(LibraryColors.inverse, lineWidth: chosen ? 0 : 1.5))
                            .shadow(color: .black.opacity(0.25), radius: 4, y: 2)
                    }
                    .buttonStyle(.plain)
                }
            }
        }
        .mapStyle(.standard(pointsOfInterest: .excludingAll))
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

#endif
