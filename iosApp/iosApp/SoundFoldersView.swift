import SwiftUI
import SharedLogic

// Settings › Library › Sound folders, as on Android's SoundFoldersScreen.kt (option b, Oct 27, 2026): the folders sound
// recordings came from, grouped by where their recordings go, Calls, Voice recordings, then Kept as folders, each with
// a "Move to…" menu. Picking one asks in the row ("953 files will become Calls", Move or Cancel); while the server moves
// them a band at the top says how far it is. See docs/screens/settings.md.

/// Bridges a `SoundFoldersController` (one per opening of the page) into SwiftUI.
final class SoundFoldersViewModel: ObservableObject {
    @Published private(set) var state: SoundFoldersState
    private let controller: SoundFoldersController
    private var observation: ObservationHandle?

    init() {
        controller = SoundFoldersController(library: IosJournal.shared.serverLibrary())
        state = controller.currentState
        observation = controller.observe { [weak self] in self?.state = $0 }
    }

    deinit {
        observation?.cancel()
        controller.close()
    }

    func load() { controller.load() }
    func retry() { controller.retry() }
    func setMark(_ folder: ServerSoundFolder, _ mark: SoundFolderMark) { controller.setMark(folder: folder, mark: mark) }
}

/// The page on its own view model; `onState` gets each state, for Settings' row.
struct SoundFoldersView: View {
    var onState: (SoundFoldersState) -> Void = { _ in }
    @StateObject private var folders = SoundFoldersViewModel()

    var body: some View {
        SoundFoldersScreen(state: folders.state, setMark: folders.setMark, retry: folders.retry)
            .onAppear { folders.load() }
            .onReceive(folders.$state) { onState($0) }
    }
}

/// A folder and where "Move to…" was asked to take it, waiting for Move or Cancel in its row.
private struct SoundFolderChange: Equatable {
    let folderId: String
    let mark: SoundFolderMark
}

/// Drawn from the state alone.
struct SoundFoldersScreen: View {
    let state: SoundFoldersState
    let setMark: (ServerSoundFolder, SoundFolderMark) -> Void
    let retry: () -> Void
    @State private var asking: SoundFolderChange?

    /// The groups in order: where their recordings go, and what it means.
    private static let groups: [(SoundFolderMark, LocalizedStringKey, LocalizedStringKey)] = [
        (.call, "Calls", "Their recordings are phone calls, in Calls on all your devices."),
        (.recording, "Voice recordings", "Their recordings are in Voice recordings on all your devices."),
        (SoundFolderMark.none, "Kept as folders", "Each stays a folder of its own, until you move it."),
    ]

    var body: some View {
        SettingsPage(title: "Sound folders") {
            if let ready = state as? SoundFoldersStateReady {
                if let moving = ready.folders.first(where: { $0.pending > 0 }) {
                    Section {
                        VStack(alignment: .leading, spacing: 6) {
                            Text("Moving \(Int(ready.pending)) files to \(moving.mark.destination(moving))…")
                                .font(.subheadline.weight(.semibold))
                                .foregroundStyle(LibraryColors.onBackground)
                            Text("On your server. You can leave this page; Moments follows in a moment.")
                                .font(.footnote)
                                .foregroundStyle(LibraryColors.muted)
                            ProgressView(value: 1 - Double(ready.pending) / Double(max(moving.files, 1))).tint(LibraryColors.onBackground)
                        }
                    }
                    .listRowBackground(LibraryColors.muted.opacity(0.15))
                }
                if ready.notChanged {
                    Section {
                        Text("Not changed: can’t reach your server. Try again when connected to it.").foregroundStyle(LibraryColors.warning)
                    }
                    .listRowBackground(LibraryColors.raised)
                }
                ForEach(Self.groups, id: \.0) { mark, heading, about in
                    let folders = ready.folders.filter { $0.mark == mark }
                    Section {
                        if folders.isEmpty {
                            Text("No folder yet").foregroundStyle(LibraryColors.muted)
                        }
                        ForEach(folders, id: \.id) { folder in
                            FolderRow(
                                folder: folder, asked: asking?.folderId == folder.id ? asking?.mark : nil,
                                disabled: ready.saving || asking != nil, groups: Self.groups.map { ($0.0, $0.1) },
                                moveTo: { asking = SoundFolderChange(folderId: folder.id, mark: $0) },
                                confirm: { mark in
                                    setMark(folder, mark)
                                    asking = nil
                                },
                                cancel: { asking = nil }
                            )
                        }
                    } header: {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(heading).font(.subheadline.weight(.semibold)).foregroundStyle(LibraryColors.onBackground)
                            Text(about).font(.footnote).foregroundStyle(LibraryColors.muted)
                        }
                        .textCase(nil)
                    }
                    .listRowBackground(LibraryColors.raised)
                }
                Section {
                } footer: {
                    Text("WhatsApp voice messages always stay voice messages.")
                }
            } else if state is SoundFoldersStateUnreachable {
                Section {
                    Text("Can’t reach your server right now.").foregroundStyle(LibraryColors.onBackground)
                    Button("Try again", action: retry).foregroundStyle(LibraryColors.onBackground)
                }
                .listRowBackground(LibraryColors.raised)
            } else {
                Section {
                    Text("Loading…").foregroundStyle(LibraryColors.muted)
                }
                .listRowBackground(LibraryColors.raised)
            }
        }
    }
}

/// A folder: its name and files, "Move to…"; when a move is asked about, the question and Move or Cancel in the row.
private struct FolderRow: View {
    let folder: ServerSoundFolder
    let asked: SoundFolderMark?
    let disabled: Bool
    let groups: [(SoundFolderMark, LocalizedStringKey)]
    let moveTo: (SoundFolderMark) -> Void
    let confirm: (SoundFolderMark) -> Void
    let cancel: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                VStack(alignment: .leading, spacing: 2) {
                    Text(verbatim: folder.shownName).fontWeight(.semibold).foregroundStyle(LibraryColors.onBackground)
                    Text(verbatim: filesLine).font(.footnote).monospacedDigit().foregroundStyle(LibraryColors.muted)
                    if folder.pending > 0 {
                        Text("\(Int(folder.pending)) left to move").font(.footnote).foregroundStyle(LibraryColors.onBackground)
                    }
                }
                Spacer(minLength: 8)
                Menu {
                    ForEach(groups.filter { $0.0 != folder.mark }, id: \.0) { mark, heading in
                        Button { moveTo(mark) } label: { Text(heading) }
                    }
                } label: {
                    Text("Move to…").foregroundStyle(LibraryColors.onBackground)
                }
                .disabled(disabled)
            }
            if let asked {
                VStack(alignment: .leading, spacing: 4) {
                    question(asked).font(.subheadline.weight(.semibold)).foregroundStyle(LibraryColors.onBackground)
                    Text("Only how they are labelled changes, on your server: the recordings themselves stay as they are.")
                        .font(.footnote)
                        .foregroundStyle(LibraryColors.muted)
                    HStack {
                        Spacer()
                        Button("Cancel", action: cancel).foregroundStyle(LibraryColors.muted)
                        Button("Move") { confirm(asked) }.fontWeight(.semibold).foregroundStyle(LibraryColors.onBackground)
                    }
                    .buttonStyle(.borderless)
                }
                .padding(12)
                .background(LibraryColors.muted.opacity(0.15), in: RoundedRectangle(cornerRadius: 10))
            }
        }
        .padding(.vertical, 2)
    }

    private func question(_ mark: SoundFolderMark) -> Text {
        let files = Int(folder.files)
        switch mark {
        case .call: return Text("\(files) files will become Calls")
        case .recording: return Text("\(files) files will become Voice recordings")
        default: return Text("\(files) files will go back to \(folder.shownName)")
        }
    }

    /// "1,623 files · from your phones", "1,520 files · imported", "168 files · 61 imported".
    private var filesLine: String {
        let files = String(localized: "\(Int(folder.files)) files")
        let from: String
        switch folder.imported {
        case 0: from = String(localized: "from your phones")
        case folder.files: from = String(localized: "imported")
        default: from = String(localized: "\(Int(folder.imported).formatted()) imported")
        }
        return "\(files) · \(from)"
    }
}

private extension SoundFolderMark {
    /// Where a folder marked so goes: Calls, Voice recordings, or back to the folder itself.
    func destination(_ folder: ServerSoundFolder) -> String {
        switch self {
        case .call: return String(localized: "Calls")
        case .recording: return String(localized: "Voice recordings")
        default: return folder.shownName
        }
    }
}

extension SoundFoldersState {
    /// Sound folders' row: "4 folders · 1 as Calls · 1 as Voice recordings", "Moving 953 files…", or "Which folders are
    /// calls or voice recordings" before the list came.
    var rowText: String {
        guard let ready = self as? SoundFoldersStateReady else { return String(localized: "Which folders are calls or voice recordings") }
        if ready.pending > 0 { return String(localized: "Moving \(Int(ready.pending)) files…") }
        let calls = ready.folders.filter { $0.mark == .call }.count
        let recordings = ready.folders.filter { $0.mark == .recording }.count
        var parts = [String(localized: "\(ready.folders.count) folders")]
        if calls > 0 { parts.append(String(localized: "\(calls) as Calls")) }
        if recordings > 0 { parts.append(String(localized: "\(recordings) as Voice recordings")) }
        return parts.joined(separator: " · ")
    }
}
