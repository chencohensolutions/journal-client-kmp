import SwiftUI
import SharedLogic

// The home screen's search: the 🔍 beside the tabs turns the bottom bar into a field, and the page lists the photos the
// server finds for the sentence typed, best first, each across the screen with its date, time and place under it. ✕ goes
// back to the tab, keeping the search for next time. See docs/screens/search.md. As Android's SearchViews.kt.

/// What the search can ask its parent to do.
struct SearchActions {
    var search: (String) -> Void = { _ in }
    var clear: () -> Void = {}
    /// A photo of the results; the viewer swipes through the results.
    var openItem: (MediaItem) -> Void = { _ in }
}

/// The results page, under `SearchTitleBar` and above `SearchFieldBar`; goes in the home screen's scroll view.
struct SearchResultsView: View {
    let search: SearchState
    let places: Places
    let width: CGFloat
    let openItem: (MediaItem) -> Void

    var body: some View {
        LazyVStack(alignment: .leading, spacing: 0) {
            switch search.status {
            case .idle:
                SearchMessage(text: "Describe what's in the photo, in your own words: \"a dog on the beach at sunset\", \"birthday cake\", \"snow in the mountains\". Search looks at the pictures themselves, not their names.")
            case .searching:
                if search.items.isEmpty {
                    ProgressView().tint(LibraryColors.onBackground).frame(maxWidth: .infinity).padding(48)
                }
            case .failed:
                if search.error == .offline {
                    SearchMessage(text: "Can't reach your server. Search works when the server can be reached.")
                } else {
                    SearchMessage(text: "The server couldn't search: \(search.errorDetail ?? "")")
                }
            default:
                FoundNote(search: search)
            }
            // Kept while a new search runs; the field shows its spinner.
            if search.status == .done || search.status == .searching {
                ForEach(search.items, id: \.id) { item in
                    FoundPhoto(item: item, place: placeLabel(item), width: width) { openItem(item) }
                        .id(item.id)
                }
            }
        }
        .followsViewer { id in
            search.items.contains { $0.id == id } ? PhotoPlace(section: id, tile: ZoomID.item(id)) : nil
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
                .frame(width: width, height: width / min(max(item.ratio, 0.6), 3))
                .clipped()
                .zoomSource(ZoomID.item(item.id))
                .contentShape(Rectangle())
                .onTapGesture(perform: onOpen)
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
        .padding(.bottom, 14)
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
        .padding(.bottom, 24)
        .background(LibraryColors.topFade.ignoresSafeArea())
    }
}

/// The tab bar turned into the field: `onSearch` on the keyboard's search key, ✕ (`onClose`) back to the tabs. It opens
/// with the keyboard up when nothing was searched yet.
struct SearchFieldBar: View {
    let search: SearchState
    let onSearch: (String) -> Void
    let onClear: () -> Void
    let onClose: () -> Void

    @State private var text: String
    @FocusState private var focused: Bool

    init(search: SearchState, onSearch: @escaping (String) -> Void, onClear: @escaping () -> Void, onClose: @escaping () -> Void) {
        self.search = search
        self.onSearch = onSearch
        self.onClear = onClear
        self.onClose = onClose
        _text = State(initialValue: search.query)
    }

    var body: some View {
        HStack(spacing: 10) {
            HStack(spacing: 8) {
                Image(systemName: "magnifyingglass").foregroundStyle(LibraryColors.muted)
                TextField("Describe a photo", text: $text)
                    .focused($focused)
                    .submitLabel(.search)
                    .onSubmit { onSearch(text) }
                    .foregroundStyle(LibraryColors.onBackground)
                if search.status == .searching {
                    ProgressView().controlSize(.small)
                } else if !text.isEmpty {
                    Button {
                        text = ""
                        onClear()
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
