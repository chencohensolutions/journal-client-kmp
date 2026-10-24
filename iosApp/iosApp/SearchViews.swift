import SwiftUI
import SharedLogic

// The home screen's search: the 🔍 beside the tabs turns the bottom bar into a field, and the page lists the photos the
// server finds for the sentence typed, best first, each across the screen with its date, time and place under it, and
// almost identical ones as one photo you swipe (the server's sets, a moment's sets kept together). A
// Filter pill above the field opens a sheet of the matches' years and towns to narrow them. ✕ goes back to the tab,
// keeping the search for next time. See docs/screens/search.md. As Android's SearchViews.kt.

/// What the search can ask its parent to do.
struct SearchActions {
    var search: (String) -> Void = { _ in }
    var clear: () -> Void = {}
    /// A year or town in the filter sheet, or nil to drop it.
    var chooseYear: (Int?) -> Void = { _ in }
    var chooseTown: (String?) -> Void = { _ in }
    /// The next page of results, when the list nears its end.
    var loadMore: () -> Void = {}
    /// A photo of the results; the viewer swipes through the results.
    var openItem: (MediaItem) -> Void = { _ in }
    /// The search screen opened: the server gets its model ready.
    var prepare: () -> Void = {}
    /// A face before anything is searched: the person becomes a chip, and the search their photos.
    var addPerson: (Person) -> Void = { _ in }
    /// A person chip's ×.
    var removePerson: (String) -> Void = { _ in }
}

/// The results page, under `SearchTitleBar` and above `SearchFieldBar`; goes in the home screen's scroll view.
struct SearchResultsView: View {
    let search: SearchState
    let places: Places
    let width: CGFloat
    let actions: SearchActions
    /// Named people to search by, before anything is searched (when the server has face search).
    var faces: [Person] = []

    var body: some View {
        LazyVStack(alignment: .leading, spacing: 0) {
            if !search.people.isEmpty {
                ScrollView(.horizontal) {
                    HStack(spacing: 8) {
                        ForEach(search.people, id: \.id) { person in
                            HStack(spacing: 8) {
                                FaceAvatar(face: person.cover, size: 28)
                                Text(person.name ?? "?").font(.system(size: 14, weight: .semibold)).foregroundStyle(LibraryColors.onBackground)
                                Button { actions.removePerson(person.id) } label: { Image(systemName: "xmark").font(.caption.weight(.bold)) }
                                    .foregroundStyle(LibraryColors.muted).accessibilityLabel("Remove \(person.name ?? "?")")
                            }
                            .padding(.leading, 4).padding(.trailing, 12).frame(height: 36)
                            .background(LibraryColors.raised, in: Capsule())
                        }
                    }
                    .padding(.horizontal, 16)
                }
                .scrollIndicators(.hidden)
                .padding(.vertical, 6)
            }
            switch search.status {
            case .idle:
                SearchMessage(text: "Describe what's in the photo, in your own words: \"a dog on the beach at sunset\", \"birthday cake\", \"snow in the mountains\". Search looks at the pictures themselves, not their names.")
                if !faces.isEmpty {
                    Text("Search by person").font(.system(size: 16, weight: .bold)).foregroundStyle(LibraryColors.onBackground).padding(.horizontal, 16).padding(.top, 8)
                    ScrollView(.horizontal) {
                        HStack(spacing: 0) {
                            ForEach(faces, id: \.id) { person in
                                Button { actions.addPerson(person) } label: {
                                    VStack(spacing: 4) {
                                        FaceAvatar(face: person.cover, size: 56)
                                        Text(person.name ?? "").font(.system(size: 13)).foregroundStyle(LibraryColors.onBackground).lineLimit(1)
                                    }
                                    .frame(width: 76).padding(.vertical, 6)
                                }
                                .buttonStyle(.plain)
                            }
                        }
                        .padding(.horizontal, 8)
                    }
                    .scrollIndicators(.hidden)
                }
            case .searching:
                if search.items.isEmpty && search.yearChips.isEmpty {
                    VStack(spacing: 12) {
                        ProgressView().tint(LibraryColors.onBackground)
                        if search.preparing {
                            Text("Getting search ready…").font(.subheadline).foregroundStyle(LibraryColors.muted)
                        }
                    }
                    .frame(maxWidth: .infinity).padding(48)
                }
            case .failed:
                if search.error == .offline {
                    SearchMessage(text: "Can't reach your server. Search works when the server can be reached.")
                } else if search.error == .off {
                    SearchMessage(text: "Search is off on your server.")
                } else {
                    SearchMessage(text: "The server couldn't search: \(search.errorDetail ?? "")")
                }
            default:
                EmptyView()
            }
            if search.status == .done && search.onPhone {
                Text("Searched on this phone: your server can’t be reached.").font(.footnote).foregroundStyle(LibraryColors.muted)
                    .padding(.horizontal, 16).padding(.vertical, 4)
            }
            if search.status == .done {
                FoundNote(search: search)
            }
            // Kept while a new search runs; the field shows its spinner.
            if search.status == .done || search.status == .searching {
                let loadFrom = max(search.sets.count - loadAheadRows, 0)
                ForEach(Array(search.sets.enumerated()), id: \.element.first!.id) { index, set in
                    Group {
                        if set.count == 1 {
                            FoundPhoto(item: set[0], place: placeLabel(set[0]), width: width) { actions.openItem(set[0]) }
                        } else {
                            FoundSet(items: set, place: placeLabel, width: width, onOpen: actions.openItem)
                        }
                    }
                    .id(set[0].id)
                    // The next page once the last rows come into view: a few ahead, so it is usually there by the end.
                    .onAppear { if index >= loadFrom && search.canLoadMore && !search.loadingMore { actions.loadMore() } }
                }
                if search.loadingMore {
                    ProgressView().tint(LibraryColors.muted).frame(maxWidth: .infinity).padding(24)
                }
            }
        }
        .task { actions.prepare() }
        .followsViewer { id in
            search.sets.first { set in set.contains { $0.id == id } }.map { PhotoPlace(section: $0[0].id, tile: ZoomID.item(id)) }
        }
    }

    /// "Haifa, Israel"; nil until the place is looked up, or for a photo without one.
    private func placeLabel(_ item: MediaItem) -> String? {
        guard let name = places.location(item: item).flatMap({ places.name(point: $0) }) else { return nil }
        var parts: [String] = []
        for part in [name.town, name.country].compactMap({ $0 }) where !parts.contains(part) { parts.append(part) }
        return parts.isEmpty ? nil : parts.joined(separator: ", ")
    }
}

/// The next page is asked for when a row this close to the end appears.
private let loadAheadRows = 5

/// "36 photos, best first", or "Nothing found", and how far the server has read the library while it still is.
private struct FoundNote: View {
    let search: SearchState

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            if search.items.isEmpty {
                SearchMessage(text: "Nothing found. Try other words.")
            } else {
                Text("\(search.items.count) photos, best first")
                    .font(.footnote.weight(.semibold))
                    .foregroundStyle(LibraryColors.muted)
                    .padding(.horizontal, 16)
            }
            if search.stillReading {
                HStack(spacing: 8) {
                    ProgressView(value: Double(search.indexed), total: Double(max(search.total, 1)))
                        .tint(LibraryColors.accent)
                        .frame(width: 48)
                    Text("Still reading your photos: \(Int(search.indexed).formatted()) of \(Int(search.total).formatted())")
                        .font(.footnote)
                        .foregroundStyle(LibraryColors.muted)
                }
                .padding(.horizontal, 16)
            }
        }
        .padding(.bottom, 12)
    }
}

/// A photo found: the screen's full width at its own shape (very tall ones kept to about a screen), and when and where
/// under it.
private struct FoundPhoto: View {
    let item: MediaItem
    let place: String?
    let width: CGFloat
    let onOpen: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            MediaTileView(item: item, badgeSize: 22)
                .frame(width: width, height: width / foundShape(item))
                .clipped()
                .zoomSource(ZoomID.item(item.id))
                .contentShape(Rectangle())
                .onTapGesture(perform: onOpen)
            FoundCaption(item: item, place: place)
        }
        .padding(.bottom, 14)
    }
}

/// Almost identical photos as one, at the best one's shape: swipe for the others ("2 / 4" and dots), with the date, time
/// and place of the one shown. Turns to the photo the viewer shows, so closing it lands on its own page.
private struct FoundSet: View {
    let items: [MediaItem]
    let place: (MediaItem) -> String?
    let width: CGFloat
    let onOpen: (MediaItem) -> Void

    /// The photo shown; the scroll view tracks it only when it starts on one.
    @State private var current: String?
    @Environment(\.viewerShowing) private var viewerShowing

    init(items: [MediaItem], place: @escaping (MediaItem) -> String?, width: CGFloat, onOpen: @escaping (MediaItem) -> Void) {
        self.items = items
        self.place = place
        self.width = width
        self.onOpen = onOpen
        _current = State(initialValue: items.first?.id)
    }

    var body: some View {
        let index = items.firstIndex { $0.id == current } ?? 0
        let height = width / foundShape(items[0])
        VStack(alignment: .leading, spacing: 8) {
            ScrollView(.horizontal) {
                LazyHStack(spacing: tileGap) {
                    ForEach(items, id: \.id) { item in
                        // The zoom source inside, as Moments' sets have it: it sets its own id, and the page's id must
                        // stay the photo's for the scroll position.
                        ZStack {
                            MediaTileView(item: item, badgeSize: 22)
                                .frame(width: width, height: height)
                                .clipped()
                                .zoomSource(ZoomID.item(item.id))
                        }
                        .contentShape(Rectangle())
                        .onTapGesture { onOpen(item) }
                    }
                }
                .scrollTargetLayout()
            }
            .scrollPosition(id: $current)
            .scrollTargetBehavior(.viewAligned(limitBehavior: .alwaysByOne))
            .scrollIndicators(.hidden)
            .frame(width: width, height: height)
            .overlay(alignment: .topTrailing) {
                HStack(spacing: 5) {
                    Image(systemName: "square.stack").font(.system(size: 11, weight: .semibold))
                    Text("\(index + 1) / \(items.count)").font(.system(size: 12, weight: .semibold))
                }
                .foregroundStyle(LibraryColors.photoText)
                .padding(.horizontal, 9)
                .padding(.vertical, 5)
                .background(LibraryColors.badgeScrim, in: Capsule())
                .padding(10)
                .accessibilityElement(children: .combine)
            }
            .overlay(alignment: .bottom) {
                HStack(spacing: 5) {
                    ForEach(0..<min(items.count, maxSetDots), id: \.self) { dot in
                        let shown = dot == min(index, maxSetDots - 1)
                        Circle().fill(.white.opacity(shown ? 1 : 0.5)).frame(width: shown ? 7 : 6, height: shown ? 7 : 6)
                    }
                }
                .padding(.bottom, 10)
                .accessibilityHidden(true)
            }
            FoundCaption(item: items[index], place: place(items[index]))
        }
        .padding(.bottom, 14)
        .onChange(of: viewerShowing) { _, showing in
            if let id = showing?.itemId, id != current, items.contains(where: { $0.id == id }) { current = id }
        }
    }
}

/// A set longer than eight shows eight dots, the last one standing for the rest.
private let maxSetDots = 8

/// Its shape (width / height), very tall and very wide ones cropped to 0.6 and 3.
private func foundShape(_ item: MediaItem) -> CGFloat { min(max(item.ratio, 0.6), 3) }

/// "Thu, 8 Oct 21:03", and "📍 Herzliya, Israel" at the end.
private struct FoundCaption: View {
    let item: MediaItem
    let place: String?

    var body: some View {
        HStack(alignment: .firstTextBaseline, spacing: 6) {
            Text(item.day.label).font(.subheadline.weight(.semibold)).foregroundStyle(LibraryColors.onBackground)
            Text(item.takenAt.formatted(date: .omitted, time: .shortened)).font(.subheadline).foregroundStyle(LibraryColors.muted)
            Spacer(minLength: 8)
            if let place {
                Label(place, systemImage: "mappin.and.ellipse")
                    .font(.subheadline)
                    .foregroundStyle(LibraryColors.muted)
                    .lineLimit(1)
            }
        }
        .padding(.horizontal, 16)
    }
}

private struct SearchMessage: View {
    let text: LocalizedStringKey

    var body: some View {
        Text(text)
            .font(.subheadline)
            .foregroundStyle(LibraryColors.muted)
            .padding(16)
    }
}

/// "Search" at the top of the page; the field is at the bottom.
struct SearchTitleBar: View {
    var body: some View {
        HStack {
            Text("Search")
                .font(.system(size: 30, weight: .bold))
                .foregroundStyle(LibraryColors.onBackground)
                .accessibilityAddTraits(.isHeader)
            Spacer()
        }
        .padding(.horizontal, 16)
        .padding(.bottom, 8)
    }
}

/// The tab bar turned into the field: the search on the keyboard's search key, ✕ (`onClose`) back to the tabs, and above
/// it the Filter pill once there are matches to narrow. It opens with the keyboard up when nothing was searched yet.
struct SearchFieldBar: View {
    let search: SearchState
    let actions: SearchActions
    let onClose: () -> Void

    @State private var text: String
    @State private var filtering = false
    @FocusState private var focused: Bool

    init(search: SearchState, actions: SearchActions, onClose: @escaping () -> Void) {
        self.search = search
        self.actions = actions
        self.onClose = onClose
        _text = State(initialValue: search.query)
    }

    var body: some View {
        VStack(spacing: 10) {
            if search.canFilter {
                FilterPill(search: search) {
                    focused = false
                    filtering = true
                }
            }
            field
        }
        .sheet(isPresented: $filtering) {
            FilterSheet(search: search, actions: actions) { filtering = false }
                .presentationDetents([.medium, .large])
        }
    }

    private var field: some View {
        HStack(spacing: 10) {
            HStack(spacing: 8) {
                Image(systemName: "magnifyingglass").foregroundStyle(LibraryColors.muted)
                TextField("Describe a photo", text: $text)
                    .focused($focused)
                    .submitLabel(.search)
                    .onSubmit { actions.search(text) }
                    .foregroundStyle(LibraryColors.onBackground)
                if search.status == .searching {
                    ProgressView().controlSize(.small)
                } else if !text.isEmpty {
                    Button {
                        text = ""
                        actions.clear()
                        focused = true
                    } label: { Image(systemName: "xmark.circle.fill") }
                        .foregroundStyle(LibraryColors.muted)
                        .accessibilityLabel("Clear")
                }
            }
            .font(.system(size: 17))
            .padding(.horizontal, 14)
            .frame(height: 48)
            .background(LibraryColors.raised, in: Capsule())
            .shadow(color: .black.opacity(0.35), radius: 12, y: 8)
            RoundBarButton(systemImage: "xmark", label: "Back to the tabs") {
                focused = false
                onClose()
            }
        }
        .padding(.horizontal, 16)
        .onAppear { if search.query.isEmpty && !UiLab.isOn { focused = true } }
    }
}

extension SearchState {
    /// Whether there is a year or a town to choose: more than one of either among the matches, or one chosen already.
    fileprivate var canFilter: Bool { (status == .done || status == .searching) && (showsYears || showsTowns) }
    fileprivate var showsYears: Bool { yearChips.count > 1 || year != nil }
    fileprivate var showsTowns: Bool { townChips.count > 1 || town != nil }
}

/// "Filter", or "Filter · 2" with a year and a town chosen, dark while any is.
private struct FilterPill: View {
    let search: SearchState
    let action: () -> Void

    var body: some View {
        let chosen = (search.year == nil ? 0 : 1) + (search.town == nil ? 0 : 1)
        Button(action: action) {
            Label(chosen == 0 ? "Filter" : "Filter · \(chosen)", systemImage: "line.3.horizontal.decrease")
                .font(.subheadline.weight(.semibold))
                .foregroundStyle(chosen == 0 ? LibraryColors.onBackground : LibraryColors.onInverse)
                .padding(.horizontal, 14)
                .frame(height: 36)
                .background(chosen == 0 ? LibraryColors.raised : LibraryColors.inverse, in: Capsule())
                .shadow(color: .black.opacity(0.25), radius: 8, y: 4)
        }
    }
}

/// The years of the matches as a grid and their towns as a list, each with how many; a tap narrows to it at once (the
/// server finds the best of that year or town) and a second drops it. "Show N photos" closes the sheet.
private struct FilterSheet: View {
    let search: SearchState
    let actions: SearchActions
    let done: () -> Void

    var body: some View {
        let year = search.year.map { String($0.intValue) }
        VStack(alignment: .leading, spacing: 14) {
            HStack {
                Text("Filter").font(.title3.weight(.bold)).accessibilityAddTraits(.isHeader)
                Spacer()
                Button("Clear") {
                    actions.chooseYear(nil)
                    actions.chooseTown(nil)
                }
                .disabled(search.year == nil && search.town == nil)
            }
            if search.showsYears {
                Text("Year").font(.headline)
                LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: 8), count: 4), spacing: 8) {
                    ForEach(search.yearChips, id: \.value) { chip in
                        let selected = chip.value == year
                        Button { actions.chooseYear(selected ? nil : Int(chip.value)) } label: {
                            VStack(spacing: 0) {
                                Text(chip.value).font(.subheadline.weight(.semibold))
                                Text("\(chip.count)").font(.caption2).opacity(0.7)
                            }
                            .frame(maxWidth: .infinity, minHeight: 44)
                            .foregroundStyle(selected ? LibraryColors.onInverse : LibraryColors.onBackground)
                            .background(selected ? LibraryColors.inverse : LibraryColors.raised, in: RoundedRectangle(cornerRadius: 10))
                        }
                        .accessibilityAddTraits(selected ? [.isSelected] : [])
                    }
                }
            }
            if search.showsTowns {
                Text("Place").font(.headline)
                ScrollView {
                    VStack(spacing: 0) {
                        ForEach(search.townChips, id: \.value) { chip in
                            let selected = chip.value == search.town
                            Button { actions.chooseTown(selected ? nil : chip.value) } label: {
                                HStack {
                                    Text(chip.value).foregroundStyle(LibraryColors.onBackground)
                                    Text("\(chip.count)").foregroundStyle(LibraryColors.muted)
                                    Spacer()
                                    if selected { Image(systemName: "checkmark").foregroundStyle(LibraryColors.accent) }
                                }
                                .padding(.vertical, 10)
                                .contentShape(Rectangle())
                            }
                            .buttonStyle(.plain)
                            .accessibilityAddTraits(selected ? [.isSelected] : [])
                            Divider()
                        }
                    }
                }
            }
            Button(action: done) {
                Group {
                    if search.status == .searching {
                        ProgressView().tint(.white)
                    } else {
                        Text("Show \(search.items.count) photos").font(.headline)
                    }
                }
                .frame(maxWidth: .infinity, minHeight: 48)
            }
            .buttonStyle(.borderedProminent)
            .tint(LibraryColors.accent)
        }
        .padding(20)
        .frame(maxHeight: .infinity, alignment: .top)
        .background(LibraryColors.background)
    }
}

/// 🔍 beside the tabs: opens search.
struct SearchButton: View {
    let action: () -> Void

    var body: some View {
        RoundBarButton(systemImage: "magnifyingglass", label: "Search", action: action)
    }
}

/// A round button that floats with the bottom bar, in its colors.
private struct RoundBarButton: View {
    let systemImage: String
    let label: LocalizedStringKey
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Image(systemName: systemImage)
                .font(.system(size: 17, weight: .semibold))
                .foregroundStyle(LibraryColors.onBackground)
                .frame(width: 48, height: 48)
                .background(LibraryColors.raised.opacity(0.95), in: Circle())
                .shadow(color: .black.opacity(0.35), radius: 12, y: 8)
        }
        .accessibilityLabel(label)
    }
}
