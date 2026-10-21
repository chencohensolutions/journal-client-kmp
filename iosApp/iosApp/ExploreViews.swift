import SwiftUI
import SharedLogic

// The Explore tab: every photo by country and town or by year, and the map (see Explore.kt in the shared logic and
// docs/screens/explore.md). A town's or a year's page shows its photos like the Moments tab.

enum ExploreView { case places, years }

struct ExploreActions {
    var openPlace: (_ town: String) -> Void
    /// A year's page, on one of its months when `month` is set.
    var openYear: (_ year: Int, _ month: Int?) -> Void
    var openMap: () -> Void
    /// A source on or off everywhere (the "Showing" sheet, "Show them").
    var toggleSource: (MediaSource) -> Void
}

/// What Explore works from: the library, newest first, without what the sources setting hides.
func exploreItems(_ library: LibraryState, _ stories: StoriesState) -> [MediaItem] {
    library.days.flatMap { $0.items }.filter { stories.shows(item: $0) }
}

/// The photos an Explore page or the map shows, newest first.
func scopeItems(_ library: LibraryState, _ stories: StoriesState, _ scope: ExploreScope) -> [MediaItem] {
    exploreItems(library, stories).filter { scope.matches(item: $0, places: stories.places) }
}

extension ExploreScope {
    /// Kotlin's default arguments, which Swift doesn't get.
    static func of(
        town: String? = nil, year: Int? = nil, month: Int? = nil, fromYear: Int? = nil, toYear: Int? = nil, area: GeoArea? = nil
    ) -> ExploreScope {
        func kotlin(_ value: Int?) -> KotlinInt? { value.map { KotlinInt(int: Int32($0)) } }
        return ExploreScope(town: town, year: kotlin(year), month: kotlin(month), fromYear: kotlin(fromYear), toYear: kotlin(toYear), area: area)
    }
}

/// What changes the Explore summaries: a new library, new places, or other sources shown.
struct ExploreInput: Equatable {
    let library: ObjectIdentifier
    let places: ObjectIdentifier
    let shown: ShownSources

    init(_ library: LibraryState, _ stories: StoriesState) {
        self.library = ObjectIdentifier(library)
        places = ObjectIdentifier(stories.places)
        shown = stories.shownSources
    }
}

/// The towns by country and the years, worked out off the main thread.
struct ExploreSummaries {
    let countries: [CountrySummary]
    let years: [YearSummary]
    let anyLocated: Bool
    /// The whole library per source, shown or not: the "Showing" sheet's "N here".
    let sourceCounts: [MediaSource: KotlinInt]

    static func of(_ library: LibraryState, _ stories: StoriesState) async -> ExploreSummaries {
        await Task.detached(priority: .userInitiated) {
            let all = library.days.flatMap { $0.items }
            var items: [MediaItem] = []
            var hidden: [MediaItem] = []
            for item in all {
                if stories.shows(item: item) { items.append(item) } else { hidden.append(item) }
            }
            let places = stories.places
            return ExploreSummaries(
                countries: ExploreKt.countriesOf(places: ExploreKt.placesOf(library: items, places: places)),
                years: ExploreKt.yearsOf(library: items, places: places, hidden: hidden),
                anyLocated: items.contains { places.location(item: $0) != nil },
                sourceCounts: ExploreKt.sourceCounts(items: all)
            )
        }.value
    }
}

/// Under the top bar: Places or Years, and the map.
struct ExploreBar: View {
    @Binding var view: ExploreView
    let onMap: () -> Void

    var body: some View {
        HStack(spacing: 8) {
            ExploreChip(label: String(localized: "Places"), on: view == .places) { view = .places }
            ExploreChip(label: String(localized: "Years"), on: view == .years) { view = .years }
            Spacer()
            ExploreChip(label: String(localized: "Map"), on: false, symbol: "map", action: onMap)
        }
        .padding(.horizontal, 16)
    }
}

/// A choice like the Moments filter bar's: filled when on, outlined when off.
struct ExploreChip: View {
    let label: String
    let on: Bool
    var symbol: String?
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 6) {
                if let symbol { Image(systemName: symbol).font(.system(size: 13, weight: .semibold)) }
                Text(label).font(.system(size: 13, weight: .semibold))
            }
            .foregroundStyle(on ? LibraryColors.onInverse : LibraryColors.onBackground)
            .padding(.horizontal, 14)
            .frame(height: 34)
            .background {
                if on {
                    Capsule().fill(LibraryColors.inverse)
                } else {
                    Capsule().stroke(LibraryColors.muted.opacity(0.45))
                }
            }
            .contentShape(Capsule())
        }
        .accessibilityAddTraits(on ? [.isSelected] : [])
    }
}

/// The Explore tab's rows, for a lazy stack of the screen's: the towns by country, home first, or the years.
struct ExploreRowsView: View {
    let summaries: ExploreSummaries
    let view: ExploreView
    let width: CGFloat
    let actions: ExploreActions
    let shownSources: ShownSources

    @State private var choosing = false

    private enum Row: Identifiable {
        case message(LocalizedStringKey)
        case country(CountrySummary)
        case home(PlaceSummary)
        /// One or two towns side by side.
        case towns([PlaceSummary])
        case year(YearSummary)

        var id: String {
            switch self {
            case .message: return "message"
            case .country(let country): return "country|\(country.country ?? "")"
            case .home(let place): return "home|\(place.town)"
            case .towns(let places): return "towns|\(places[0].town)"
            case .year(let year): return "year|\(year.year)"
            }
        }
    }

    var body: some View {
        ShownSourcesLine(shown: shownSources, counts: summaries.sourceCounts) { choosing = true }
            .padding(.horizontal, 16)
            .padding(.bottom, 8)
            .sheet(isPresented: $choosing) {
                ShowingSheet(shown: shownSources, counts: summaries.sourceCounts, onToggle: actions.toggleSource)
            }
        ForEach(rows) { row in
            switch row {
            case .message(let text):
                Text(text)
                    .font(.system(size: 15))
                    .foregroundStyle(LibraryColors.muted)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.horizontal, 24)
                    .padding(.vertical, 32)
            case .country(let country):
                CountryHeader(country: country).padding(.horizontal, 16).padding(.top, 8).padding(.bottom, 8)
            case .home(let place):
                HomeCard(place: place) { actions.openPlace(place.town) }.padding(.horizontal, 16).padding(.bottom, 16)
            case .towns(let places):
                HStack(alignment: .top, spacing: 10) {
                    ForEach(places, id: \.town) { place in
                        PlaceCard(place: place) { actions.openPlace(place.town) }
                    }
                    if places.count == 1 { Color.clear.frame(height: 1) }
                }
                .padding(.horizontal, 16)
                .padding(.bottom, 16)
            case .year(let year):
                YearCard(year: year, actions: actions)
            }
        }
    }

    private var rows: [Row] {
        if view == .years { return summaries.years.map { .year($0) } }
        if summaries.countries.isEmpty {
            // Places are looked up in the background; until one is named there is nothing to list.
            let finding = summaries.anyLocated || !summaries.years.isEmpty
            return [.message(finding ? "Finding where your photos were taken…" : "None of these photos has a location.")]
        }
        return summaries.countries.flatMap { country -> [Row] in
            var rows: [Row] = [.country(country)]
            rows += country.places.filter(\.home).map { .home($0) }
            let others = country.places.filter { !$0.home }
            rows += stride(from: 0, to: others.count, by: 2).map { .towns(Array(others[$0..<min($0 + 2, others.count)])) }
            return rows
        }
    }
}

/// A country above its towns: "France", "3 places · 1,240 photos".
private struct CountryHeader: View {
    let country: CountrySummary

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            Text(country.country ?? String(localized: "Other places"))
                .font(.system(size: 20, weight: .bold))
                .foregroundStyle(LibraryColors.onBackground)
                .lineLimit(1)
                .accessibilityAddTraits(.isHeader)
            Text("\(String(localized: "\(country.places.count) places")) · \(String(localized: "\(Int(country.photoCount)) photos"))")
                .font(.system(size: 13))
                .foregroundStyle(LibraryColors.muted)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

/// The home town, across the grid: its photo with the name written over it.
private struct HomeCard: View {
    let place: PlaceSummary
    let onOpen: () -> Void

    var body: some View {
        Button(action: onOpen) {
            ExploreThumbnail(item: place.cover)
                .frame(height: 176)
                .overlay(alignment: .bottomLeading) {
                    VStack(alignment: .leading, spacing: 0) {
                        Text(String(localized: "Home").uppercased())
                            .font(.system(size: 12, weight: .semibold))
                            .kerning(0.8)
                            .foregroundStyle(LibraryColors.photoTextMuted)
                        Text(place.town)
                            .font(.system(size: 22, weight: .bold))
                            .foregroundStyle(LibraryColors.photoText)
                            .lineLimit(1)
                        Text(placeLine(count: place.items.count, first: Int(place.firstYear), last: Int(place.lastYear)))
                            .font(.system(size: 13))
                            .foregroundStyle(LibraryColors.photoTextMuted)
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.horizontal, 14)
                    .padding(.top, 28)
                    .padding(.bottom, 14)
                    .background(LibraryColors.photoFade)
                }
                .clipShape(RoundedRectangle(cornerRadius: 14))
        }
        .buttonStyle(.plain)
    }
}

private struct PlaceCard: View {
    let place: PlaceSummary
    let onOpen: () -> Void

    var body: some View {
        Button(action: onOpen) {
            VStack(alignment: .leading, spacing: 2) {
                ExploreThumbnail(item: place.cover)
                    .frame(height: 132)
                    .clipShape(RoundedRectangle(cornerRadius: 12))
                Text(place.town)
                    .font(.system(size: 15, weight: .bold))
                    .foregroundStyle(LibraryColors.onBackground)
                    .lineLimit(1)
                    .padding(.top, 6)
                Text(verbatim: "\(place.items.count) · \(yearRange(Int(place.firstYear), Int(place.lastYear)))")
                    .font(.system(size: 12))
                    .foregroundStyle(LibraryColors.muted)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }
}

/// "4,812 photos · 2017 – 2026".
func placeLine(count: Int, first: Int, last: Int) -> String {
    "\(String(localized: "\(count) photos")) · \(yearRange(first, last))"
}

/// "2019 – 2026", or "2024" for one year.
func yearRange(_ first: Int, _ last: Int) -> String {
    // As text: a localized number would be written "2,019".
    first == last ? String(first) : String(localized: "\(String(first)) – \(String(last))")
}

/// "Oct", as the locale writes it.
func monthName(_ month: Int) -> String {
    let formatter = DateFormatter()
    formatter.locale = .current
    return formatter.shortStandaloneMonthSymbols[(month - 1) % 12]
}

/// A year: its count, the towns of most of its photos, a row of its photos, and its months to jump to.
private struct YearCard: View {
    let year: YearSummary
    let actions: ExploreActions

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack(alignment: .lastTextBaseline) {
                Text(verbatim: String(year.year))
                    .font(.system(size: 30, weight: .bold))
                    .foregroundStyle(LibraryColors.onBackground)
                    .accessibilityAddTraits(.isHeader)
                Spacer()
                VStack(alignment: .trailing, spacing: 1) {
                    Text("\(year.items.count) photos")
                        .font(.system(size: 13))
                        .foregroundStyle(LibraryColors.muted)
                    ForEach(hidden, id: \.0) { source, count in
                        Text("+\(count) \(source.title) hidden")
                            .font(.system(size: 12))
                            .foregroundStyle(LibraryColors.muted.opacity(0.8))
                    }
                }
            }
            if year.items.isEmpty {
                onlyHidden
            } else {
                shownPhotos
            }
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 14)
        .contentShape(Rectangle())
        .onTapGesture { if !year.items.isEmpty { actions.openYear(Int(year.year), nil) } }
        .accessibilityAddTraits(year.items.isEmpty ? [] : .isButton)
    }

    /// Its photos the sources setting hides, per source in order.
    private var hidden: [(MediaSource, Int)] { sourceCountList(year.hidden) }

    /// A year with only hidden photos: "Only WhatsApp photos this year (412)", and Show them, which turns them on.
    private var onlyHidden: some View {
        let names = hidden.map(\.0.title).joined(separator: ", ")
        let total = hidden.reduce(0) { $0 + $1.1 }
        return HStack(spacing: 8) {
            Text("Only \(names) photos this year (\(total))")
                .font(.system(size: 14))
                .foregroundStyle(LibraryColors.muted)
                .frame(maxWidth: .infinity, alignment: .leading)
            Button("Show them") { hidden.forEach { actions.toggleSource($0.0) } }
                .font(.system(size: 14, weight: .semibold))
                .foregroundStyle(LibraryColors.onBackground)
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 14)
        .background {
            RoundedRectangle(cornerRadius: 12).strokeBorder(LibraryColors.muted.opacity(0.45), style: StrokeStyle(lineWidth: 1, dash: [5, 4]))
        }
        .padding(.top, 10)
    }

    @ViewBuilder
    private var shownPhotos: some View {
        if !year.towns.isEmpty {
            Text(verbatim: year.towns.joined(separator: " · "))
                .font(.system(size: 13))
                .foregroundStyle(LibraryColors.muted)
                .lineLimit(1)
        }
        HStack(spacing: 3) {
            ForEach(year.covers, id: \.id) { item in
                ExploreThumbnail(item: item)
            }
        }
        .frame(height: 86)
        .clipShape(RoundedRectangle(cornerRadius: 12))
        .padding(.top, 10)
        ScrollView(.horizontal) {
            HStack(spacing: 6) {
                ForEach(year.months.map { Int($0.intValue) }, id: \.self) { month in
                    Button { actions.openYear(Int(year.year), month) } label: {
                        Text(monthName(month))
                            .font(.system(size: 12, weight: .semibold))
                            .foregroundStyle(LibraryColors.onBackground)
                            .padding(.horizontal, 12)
                            .padding(.vertical, 7)
                            .background(LibraryColors.raised, in: Capsule())
                    }
                    .buttonStyle(.plain)
                }
            }
        }
        .scrollIndicators(.hidden)
        .padding(.top, 10)
    }
}

/// A photo cropped to fill its frame, without badges. Size it from outside.
struct ExploreThumbnail: View {
    let item: MediaItem
    @State private var image: UIImage?

    var body: some View {
        GeometryReader { geometry in
            ZStack {
                LibraryColors.raised
                if let image {
                    Image(uiImage: image)
                        .resizable()
                        .scaledToFill()
                        .frame(width: geometry.size.width, height: geometry.size.height)
                        .clipped()
                }
            }
            .task(id: item.id) {
                image = await PhotoLibraryImages.shared.image(item: item, pointSize: geometry.size)
            }
        }
        .accessibilityHidden(true)
    }
}

// MARK: - A town's or a year's page

/// A town's page: its photo and name, the years to narrow it to, and its photos like the Moments tab. `openItem`
/// opens the viewer on a row of it, with the scope shown.
struct PlacePageView: View {
    let town: String
    let library: LibraryState
    let stories: StoriesState
    let openItem: (ExploreScope, FeedSection, MediaItem) -> Void
    let toggleSource: (MediaSource) -> Void

    @State private var year: Int?

    var body: some View {
        let scope = ExploreScope.of(town: town, year: year)
        ExplorePageView(
            library: library,
            stories: stories,
            whole: .of(town: town),
            shown: scope,
            title: town,
            subtitle: { all in
                placeLine(count: all.count, first: Int(all.last!.day.year), last: Int(all.first!.day.year))
            },
            chips: { all in
                let years = Dictionary(grouping: all, by: { Int($0.day.year) }).map { ($0.key, $0.value.count) }.sorted { $0.0 > $1.0 }
                return [(nil, String(localized: "All years"))] + years.map { ($0.0, "\(String($0.0)) · \($0.1)") }
            },
            selected: $year,
            openItem: { openItem(scope, $0, $1) },
            toggleSource: toggleSource
        )
    }
}

/// A year's page: its photos like the Moments tab, narrowed to one of its months.
struct YearPageView: View {
    let year: Int
    let library: LibraryState
    let stories: StoriesState
    let openItem: (ExploreScope, FeedSection, MediaItem) -> Void
    let toggleSource: (MediaSource) -> Void

    @State private var month: Int?

    init(
        year: Int, month: Int?, library: LibraryState, stories: StoriesState,
        openItem: @escaping (ExploreScope, FeedSection, MediaItem) -> Void, toggleSource: @escaping (MediaSource) -> Void
    ) {
        self.year = year
        self.library = library
        self.stories = stories
        self.openItem = openItem
        self.toggleSource = toggleSource
        _month = State(initialValue: month)
    }

    var body: some View {
        let scope = ExploreScope.of(year: year, month: month)
        let places = stories.places
        ExplorePageView(
            library: library,
            stories: stories,
            whole: .of(year: year),
            shown: scope,
            title: String(year),
            subtitle: { all in
                var counts: [String: Int] = [:]
                for item in all {
                    if let town = places.location(item: item).flatMap({ places.name(point: $0)?.town }) { counts[town, default: 0] += 1 }
                }
                let towns = counts.sorted { $0.value > $1.value }.prefix(3).map(\.key)
                return ([String(localized: "\(all.count) photos")] + towns).joined(separator: " · ")
            },
            chips: { all in
                var months: [Int] = []
                for item in all where months.last != Int(item.day.month) { months.append(Int(item.day.month)) }
                return [(nil, String(localized: "Whole year"))] + months.map { ($0, monthName($0)) }
            },
            selected: $month,
            openItem: { openItem(scope, $0, $1) },
            toggleSource: toggleSource
        )
    }
}

/// Everything in `whole` the sources setting shows, the feed rows of what `shown` takes in, and everything in `whole`
/// per source, shown or not ("N here").
private struct ExplorePage {
    let all: [MediaItem]
    let rows: [FeedRow]
    let counts: [MediaSource: KotlinInt]
}

private struct ExplorePageInput: Equatable {
    let explore: ExploreInput
    let shown: ExploreScope
}

/// A cover photo with the title over it, a row of choices that narrow the page, then its photos as the Moments
/// tab shows them, without trip cards. Videos don't play here.
private struct ExplorePageView: View {
    let library: LibraryState
    let stories: StoriesState
    let whole: ExploreScope
    let shown: ExploreScope
    let title: String
    /// Worked out from all the page's photos, when there are any.
    let subtitle: ([MediaItem]) -> String
    let chips: ([MediaItem]) -> [(Int?, String)]
    @Binding var selected: Int?
    let openItem: (FeedSection, MediaItem) -> Void
    let toggleSource: (MediaSource) -> Void

    @State private var page: ExplorePage?
    @State private var selection = Set<String>()
    @State private var choosing = false
    @StateObject private var playback = FeedPlayback()

    var body: some View {
        let all = page?.all ?? []
        let rows = page?.rows ?? []
        GeometryReader { geometry in
            ScrollView {
                LazyVStack(alignment: .leading, spacing: 0) {
                    cover(all, top: geometry.safeAreaInsets.top)
                    ScrollView(.horizontal) {
                        HStack(spacing: 8) {
                            ForEach(chips(all), id: \.0) { value, label in
                                ExploreChip(label: label, on: value == selected) { selected = value }
                            }
                        }
                        .padding(.horizontal, 16)
                    }
                    .scrollIndicators(.hidden)
                    .padding(.top, 14)
                    .padding(.bottom, 8)
                    if let page {
                        ShownSourcesLine(shown: stories.shownSources, counts: page.counts) { choosing = true }
                            .padding(.horizontal, 16)
                            .padding(.bottom, 4)
                    }
                    FeedRowsView(
                        rows: rows,
                        note: { stories.note(itemId: $0) },
                        width: geometry.size.width,
                        actions: FeedActions(openItem: openItem, menus: false),
                        selection: $selection,
                        playback: playback
                    )
                }
                .padding(.bottom, 48)
                .followsViewer { rows.place(of: $0) }
            }
            .scrollIndicators(.hidden)
            .ignoresSafeArea(edges: .top)
        }
        .background(LibraryColors.background.ignoresSafeArea())
        .navigationBarTitleDisplayMode(.inline)
        .toolbarBackground(.hidden, for: .navigationBar)
        .toolbarColorScheme(.dark, for: .navigationBar)
        .sheet(isPresented: $choosing) {
            ShowingSheet(shown: stories.shownSources, counts: page?.counts ?? [:], onToggle: toggleSource)
        }
        .task(id: ExplorePageInput(explore: ExploreInput(library, stories), shown: shown)) {
            let (library, stories, whole, shown, places) = (library, stories, whole, shown, stories.places)
            page = await Task.detached(priority: .userInitiated) {
                let everything = library.days.flatMap { $0.items }.filter { whole.matches(item: $0, places: places) }
                let all = everything.filter { stories.shows(item: $0) }
                return ExplorePage(
                    all: all,
                    rows: stories.feedOf(items: all.filter { shown.matches(item: $0, places: places) }).rows,
                    counts: ExploreKt.sourceCounts(items: everything)
                )
            }.value
        }
    }

    private func cover(_ all: [MediaItem], top: CGFloat) -> some View {
        let photo = all.first { !$0.isVideo } ?? all.first
        return ZStack(alignment: .bottomLeading) {
            LibraryColors.raised
            if let photo { ExploreThumbnail(item: photo) }
            VStack(alignment: .leading, spacing: 0) {
                Text(verbatim: title)
                    .font(.system(size: 28, weight: .bold))
                    .foregroundStyle(LibraryColors.photoText)
                    .accessibilityAddTraits(.isHeader)
                if !all.isEmpty {
                    Text(verbatim: subtitle(all))
                        .font(.system(size: 13))
                        .foregroundStyle(LibraryColors.photoTextMuted)
                        .lineLimit(1)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, 16)
            .padding(.top, 40)
            .padding(.bottom, 16)
            .background(LibraryColors.photoFade)
        }
        .frame(height: 260 + top)
        .clipped()
        // A scrim under the back button, which is over the photo.
        .overlay(alignment: .top) { LibraryColors.labelFade.frame(height: top + 44) }
    }
}
