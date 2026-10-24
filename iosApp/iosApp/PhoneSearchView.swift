import SwiftUI
import SharedLogic

/// Settings › Search › On this phone on the live `LocalIndexes`: which of the server's indexes this phone keeps, to
/// search without the server, offline too. As Android's PhoneSearchScreen; see docs/screens/settings.md.
struct PhoneSearchView: View {
    @ObservedObject var model: PhoneSearchViewModel

    var body: some View {
        PhoneSearchScreen(
            state: model.state,
            set: model.set, setWifiOnly: model.setWifiOnly, updateNow: model.updateNow, pause: model.pause
        )
        .task { model.refresh() }
    }
}

/// Drawn from the state alone.
struct PhoneSearchScreen: View {
    let state: PhoneSearchState
    let set: (PhoneIndexKind, Bool) -> Void
    let setWifiOnly: (Bool) -> Void
    let updateNow: () -> Void
    let pause: () -> Void

    var body: some View {
        Form {
            Section {
            } footer: {
                Text("Keep what search needs on this phone, to search without your server, offline too. Your server sends it once, then only what’s new.")
            }
            if !state.kept.isEmpty {
                Section("Status") { status }
                    .listRowBackground(LibraryColors.raised)
            }
            Section("Keep on this phone") {
                ForEach(state.indexes.filter(\.available), id: \.kind) { index in
                    Toggle(isOn: Binding(get: { index.on }, set: { set(index.kind, $0) })) {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(index.kind.title).fontWeight(.semibold).foregroundStyle(LibraryColors.onBackground)
                            Text(index.body).font(.footnote).foregroundStyle(LibraryColors.muted)
                        }
                    }
                    .tint(LibraryColors.accent)
                }
            }
            .listRowBackground(LibraryColors.raised)
            Section {
                Toggle(isOn: Binding(get: { state.wifiOnly }, set: setWifiOnly)) {
                    VStack(alignment: .leading, spacing: 2) {
                        Text("Only on Wi-Fi").fontWeight(.semibold).foregroundStyle(LibraryColors.onBackground)
                        Text("Download only on Wi-Fi. Searching works anywhere.").font(.footnote).foregroundStyle(LibraryColors.muted)
                    }
                }
                .tint(LibraryColors.accent)
            } header: {
                Text("Downloading")
            } footer: {
                Text("Uses \(byteText(state.keptBytes)) of this phone · \(byteText(state.freeBytes)) free")
            }
            .listRowBackground(LibraryColors.raised)
        }
        .scrollContentBackground(.hidden)
        .background(LibraryColors.background)
        .navigationTitle("On this phone")
    }

    @ViewBuilder
    private var status: some View {
        if let download = state.download {
            VStack(alignment: .leading, spacing: 6) {
                Text("Downloading \(download.kind.title)").fontWeight(.semibold)
                Text("\(byteText(download.doneBytes)) of \(byteText(download.totalBytes))").font(.footnote).monospacedDigit().foregroundStyle(LibraryColors.muted)
                ProgressView(value: Double(download.doneBytes), total: Double(max(download.totalBytes, 1))).tint(LibraryColors.onBackground)
                Text("Until it’s done, search uses your server.").font(.footnote).foregroundStyle(LibraryColors.muted)
            }
            Button("Pause", action: pause).fontWeight(.bold).foregroundStyle(LibraryColors.onBackground)
        } else if state.waitingForWifi {
            VStack(alignment: .leading, spacing: 4) {
                Label("Waiting for Wi-Fi", systemImage: "wifi").fontWeight(.semibold)
                Text("Search uses your server until then.").font(.footnote).foregroundStyle(LibraryColors.muted)
            }
            Button("Download now", action: updateNow).fontWeight(.bold).foregroundStyle(LibraryColors.onBackground)
        } else {
            VStack(alignment: .leading, spacing: 4) {
                Text(state.updatedLine).fontWeight(.semibold)
                Text(state.newOnServer > 0 ? String(localized: "\(Int(state.newOnServer)) new photos on your server, not here yet") : String(localized: "Everything your server has read is here"))
                    .font(.footnote).foregroundStyle(LibraryColors.muted)
                if let error = state.error {
                    Text("The last update didn’t finish: \(error)").font(.footnote).foregroundStyle(LibraryColors.warning)
                }
            }
            Button("Update now", action: updateNow).fontWeight(.bold).foregroundStyle(LibraryColors.onBackground)
        }
    }
}

private func byteText(_ count: Int64) -> String { ByteCountFormatter.string(fromByteCount: count, countStyle: .file) }

extension PhoneIndexKind {
    var title: String {
        switch self {
        case .words: return String(localized: "Search by words")
        case .people: return String(localized: "People")
        case .text: return String(localized: "Text in photos")
        case .similar: return String(localized: "Similar photos")
        default: return String(localized: "Best-looking photos")
        }
    }

    var what: String {
        switch self {
        case .words: return String(localized: "Find photos by describing them")
        case .people: return String(localized: "Find people and their photos")
        case .text: return String(localized: "Find receipts, signs and screenshots by their words")
        case .similar: return String(localized: "Looks like this, in a photo’s details")
        default: return String(localized: "A set opens on its best photo; Best first")
        }
    }
}

extension PhoneIndex {
    /// "Find photos by describing them · 440 MB + 290 MB model".
    var body: String {
        let size = modelBytes > 0 ? String(localized: "\(byteText(bytes)) + \(byteText(modelBytes)) model") : byteText(bytes)
        return "\(kind.what) · \(size)"
    }
}

extension PhoneSearchState {
    var updatedLine: String {
        guard let updated = updatedAtMillis else { return String(localized: "Not downloaded yet") }
        let date = Date(timeIntervalSince1970: TimeInterval(updated.int64Value) / 1000)
        return String(localized: "Updated \(date.formatted(.relative(presentation: .named)))")
    }

    /// Settings' row: "3 kept · 839 MB · Updated 2 hours ago", or "Off: search needs your server".
    var rowText: String {
        if kept.isEmpty { return String(localized: "Off: search needs your server") }
        if let download { return String(localized: "Downloading: \(Int(download.doneBytes * 100 / max(download.totalBytes, 1)))%") }
        let when = updatedAtMillis.map { Date(timeIntervalSince1970: TimeInterval($0.int64Value) / 1000).formatted(.relative(presentation: .named)) }
            ?? String(localized: "not downloaded yet")
        return String(localized: "\(kept.count) kept · \(byteText(keptBytes)) · Updated \(when)")
    }
}
