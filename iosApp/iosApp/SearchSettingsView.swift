import Combine
import SwiftUI
import SharedLogic

/// Settings › Search on the live view models: Indexing, the reading of this user's photos on the server (start, follow,
/// pause); photos per page of results; and On device search, the indexes kept on this phone. As Android's SearchSettingsScreen; see docs/screens/settings.md.
struct SearchSettingsView: View {
    @ObservedObject var settings: SettingsViewModel
    /// Indexing, when the server offers it; nil: no section.
    var indexing: ServerIndexingViewModel?
    /// On device search, when the server lets phones keep its indexes; nil: no section.
    var phoneSearch: PhoneSearchViewModel?
    @State private var indexingState: ServerIndexingState?
    @State private var phoneSearchState: PhoneSearchState?

    var body: some View {
        SearchSettingsScreen(
            searchPageSize: Int(settings.state.searchPageSize),
            indexing: indexingState,
            phoneSearch: phoneSearchState,
            setSearchPageSize: settings.setSearchPageSize,
            startIndexing: { indexing?.start() },
            pauseIndexing: { indexing?.pause() },
            phoneSearchActions: PhoneSearchActions(
                set: { phoneSearch?.set($0, $1) }, setWifiOnly: { phoneSearch?.setWifiOnly($0) },
                updateNow: { phoneSearch?.updateNow() }, pause: { phoneSearch?.pause() }
            )
        )
        .onReceive(indexing.map { $0.$state.map(Optional.some).eraseToAnyPublisher() } ?? Just(nil).eraseToAnyPublisher()) { indexingState = $0 }
        .onReceive(phoneSearch.map { $0.$state.map(Optional.some).eraseToAnyPublisher() } ?? Just(nil).eraseToAnyPublisher()) { phoneSearchState = $0 }
        .task { phoneSearch?.refresh() }
        // Asked every 2 s while a run goes, every 30 s otherwise, while the screen shows.
        .onAppear { indexing?.startWatching() }
        .onDisappear { indexing?.stopWatching() }
    }
}

/// What On device search can ask for.
struct PhoneSearchActions {
    var set: (PhoneIndexKind, Bool) -> Void = { _, _ in }
    var setWifiOnly: (Bool) -> Void = { _ in }
    var updateNow: () -> Void = {}
    var pause: () -> Void = {}
}

/// Drawn from the states alone.
struct SearchSettingsScreen: View {
    let searchPageSize: Int
    /// The server's reading of this user's photos; nil when the server offers none.
    let indexing: ServerIndexingState?
    let phoneSearch: PhoneSearchState?
    let setSearchPageSize: (Int) -> Void
    let startIndexing: () -> Void
    let pauseIndexing: () -> Void
    var phoneSearchActions = PhoneSearchActions()

    var body: some View {
        Form {
            if let indexing {
                Section {
                    indexingRows(indexing)
                } header: {
                    Text("Indexing")
                } footer: {
                    VStack(alignment: .leading, spacing: 6) {
                        Text("Your server reads each new photo once so search can find it: by words, people, text in the photo, and alike photos. It does this every night; you can start it now, and pause it.")
                        if let next = indexing.nextNightlyMillis {
                            Text("Next nightly run: \(date(next.int64Value).formatted(.dateTime.weekday(.wide).hour().minute()))")
                        }
                    }
                }
                .listRowBackground(LibraryColors.raised)
            }
            Section {
                Picker("Photos per page", selection: Binding(get: { searchPageSize }, set: setSearchPageSize)) {
                    ForEach(SettingsViewModel.searchPageOptions, id: \.self) { size in
                        Text("\(size)").tag(size)
                    }
                }
                .foregroundStyle(LibraryColors.onBackground)
            } header: {
                Text("Results")
            } footer: {
                Text("How many photos a search shows at first. Scrolling near the end shows the next page.")
            }
            .listRowBackground(LibraryColors.raised)
            if let phoneSearch {
                PhoneSearchSections(
                    state: phoneSearch, set: phoneSearchActions.set, setWifiOnly: phoneSearchActions.setWifiOnly,
                    updateNow: phoneSearchActions.updateNow, pause: phoneSearchActions.pause
                )
            }
        }
        .scrollContentBackground(.hidden)
        .background(LibraryColors.background)
        .navigationTitle("Search")
    }

    /// Reading (with a bar and Pause), waiting (with Pause), paused (with Resume), or the last run (with Read new photos now).
    @ViewBuilder
    private func indexingRows(_ state: ServerIndexingState) -> some View {
        switch state.run {
        case .running:
            VStack(alignment: .leading, spacing: 6) {
                Text(state.nightly ? "Nightly run: reading your photos" : "Reading your photos").fontWeight(.semibold)
                Text("\(count(state.done)) of \(count(state.total))").font(.footnote).monospacedDigit().foregroundStyle(LibraryColors.muted)
                ProgressView(value: Double(state.fraction)).tint(LibraryColors.onBackground)
                if !state.indexes.isEmpty {
                    Text(state.countsLine).font(.footnote).foregroundStyle(LibraryColors.muted)
                }
            }
            button("Pause", state, pauseIndexing)
        case .queued:
            VStack(alignment: .leading, spacing: 4) {
                Text("Waiting for other runs").fontWeight(.semibold)
                Text("\(state.queuedBehind?.intValue ?? 1) ahead of yours. Only one runs at a time, to keep the server’s memory free.")
                    .font(.footnote).foregroundStyle(LibraryColors.muted)
            }
            button("Pause", state, pauseIndexing)
        case .paused:
            VStack(alignment: .leading, spacing: 6) {
                Text("Paused at \(count(state.done)) of \(count(state.total))").fontWeight(.semibold)
                if state.total > 0 {
                    ProgressView(value: Double(state.fraction)).tint(LibraryColors.muted)
                }
                // Only a nightly server runs its readings in a process of their own, whose models leave with it.
                Text(state.nextNightlyMillis != nil
                     ? "The server let go of its models while paused. Resume goes on from here."
                     : "New photos wait to be read until you resume.")
                    .font(.footnote).foregroundStyle(LibraryColors.muted)
            }
            button("Resume", state, startIndexing)
        default:
            VStack(alignment: .leading, spacing: 4) {
                if let last = state.lastRun {
                    let when = date(last.finishedAtMillis).formatted(.relative(presentation: .named))
                    Text(last.stopped ? "Stopped \(when) after \(count(last.files)) photos" : "Last read \(when): \(count(last.files)) photos")
                        .fontWeight(.semibold)
                    Text("New photos are read every night.").font(.footnote).foregroundStyle(LibraryColors.muted)
                    if let error = last.error {
                        Text("It couldn’t go on: \(error)").font(.footnote).foregroundStyle(LibraryColors.warning)
                    }
                } else {
                    Text("No run yet").fontWeight(.semibold)
                    Text("New photos are read every night.").font(.footnote).foregroundStyle(LibraryColors.muted)
                }
            }
            button("Read new photos now", state, startIndexing)
        }
        if let error = state.error {
            Text("Can’t reach the server: \(error)").font(.footnote).foregroundStyle(LibraryColors.warning)
        }
    }

    private func button(_ title: LocalizedStringKey, _ state: ServerIndexingState, _ action: @escaping () -> Void) -> some View {
        Button(title, action: action)
            .fontWeight(.bold)
            .foregroundStyle(state.acting ? LibraryColors.muted : LibraryColors.onBackground)
            .disabled(state.acting)
    }
}

private func count(_ n: Int32) -> String { Int(n).formatted() }

private func date(_ millis: Int64) -> Date { Date(timeIntervalSince1970: TimeInterval(millis) / 1000) }

extension ServerIndexingState {
    /// "Search by words 40 · People 38 · 2 couldn't be read".
    var countsLine: String {
        var parts = indexes.map { "\($0.title) \(Int($0.read).formatted())" }
        let failed = indexes.reduce(0) { $0 + Int($1.failed) }
        if failed > 0 { parts.append(String(localized: "\(failed) couldn’t be read")) }
        return parts.joined(separator: " · ")
    }

    /// Settings' row: "Server: reading 40 of 200"; nil when nothing is going.
    var rowText: String? {
        switch run {
        case .running: return String(localized: "Server: reading \(count(done)) of \(count(total))")
        case .paused: return String(localized: "Server: paused at \(count(done)) of \(count(total))")
        case .queued: return String(localized: "Server: waiting to read your photos")
        default: return nil
        }
    }
}

extension IndexingCount {
    var title: String {
        switch name {
        case "search": return String(localized: "Search by words")
        case "faces": return String(localized: "People")
        case "ocr": return String(localized: "Text in photos")
        case "similar": return String(localized: "Similar photos")
        case "aesthetic": return String(localized: "Best-looking photos")
        default: return String(localized: "Nudity scan")
        }
    }
}
