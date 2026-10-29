import SwiftUI
import UniformTypeIdentifiers
import SharedLogic

// The list of pills Settings › Backup and Free up space share (UI lab round ux/backup/2026-10-25, option b2, as on
// Android's BackupPills.kt): photo sources and the folders of calls and recordings the user picked, side by side, the
// ones that are on first (filled). Backup ends it with "+ Add a folder", which opens the Files folder picker and then
// the add sheet: what was found in it, what its files are (guessed from their names), and Back it up. An iPhone keeps
// call recordings in Notes, and Voice Memos and WhatsApp's voice notes inside their apps: only folders in Files can be
// picked. See docs/screens/settings.md.

/// What the pills of sound folders can ask for; none in the UI lab.
struct SoundFolderActions {
    /// A folder just picked in Files (Backup only): kept, looked into, then offered in the add sheet.
    var add: ((URL) async -> PickedFolder?)? = nil
    /// Back it up, as the user said its files are.
    var confirm: (PickedFolder, AudioKind) -> Void = { _, _ in }
    var toggle: (SoundFolder) -> Void = { _ in }
    /// Stop backing it up (Backup only, a long press).
    var remove: ((SoundFolder) -> Void)? = nil
    var setKeepOnPhone: (KeepOnPhone) -> Void = { _ in }
}

/// A folder just picked in Files, before it is backed up.
struct PickedFolder: Identifiable {
    let folder: SoundFolder
    let look: FolderLook
    var id: String { folder.treeUri }
}

/// The Files picker, the add sheet and the dialogs of the sound folders, kept by the screen (`soundFolderPicking`) rather
/// than by the pills, whose Form row SwiftUI may make again while a sheet is open.
@MainActor
final class FolderPicking: ObservableObject {
    @Published var picking = false
    @Published var picked: PickedFolder?
    @Published var unreadable = false
    @Published var removing: SoundFolder?
    /// A folder the app could no longer read, picked again: the new one takes its place.
    var replacing: SoundFolder?

    func pick(replacing folder: SoundFolder? = nil) {
        replacing = folder
        picking = true
    }
}

extension View {
    /// Presents what `picking` asks for: the Files folder picker, then the add sheet, and the stop dialog.
    func soundFolderPicking(_ picking: FolderPicking, _ folders: SoundFolderActions) -> some View {
        modifier(SoundFolderPicking(picking: picking, folders: folders))
    }
}

private struct SoundFolderPicking: ViewModifier {
    @ObservedObject var picking: FolderPicking
    let folders: SoundFolderActions

    func body(content: Content) -> some View {
        content
            .fileImporter(isPresented: $picking.picking, allowedContentTypes: [.folder]) { result in
                guard case let .success(url) = result, let add = folders.add else { return }
                Task { @MainActor in
                    if let found = await add(url) { picking.picked = found } else { picking.unreadable = true }
                }
            }
            .sheet(item: $picking.picked) { picked in
                AddFolderSheet(picked: picked) { kind in
                    folders.confirm(picked, kind)
                    if let replacing = picking.replacing { folders.remove?(replacing) }
                    picking.replacing = nil
                    picking.picked = nil
                } cancel: {
                    picking.picked = nil
                }
                .presentationDetents([.medium, .large])
            }
            .alert("The app can’t read that folder.", isPresented: $picking.unreadable) { Button("OK", role: .cancel) {} }
            .confirmationDialog(
                picking.removing.map { String(localized: "Stop backing up \($0.name)?") } ?? "",
                isPresented: Binding(get: { picking.removing != nil }, set: { if !$0 { picking.removing = nil } }),
                titleVisibility: .visible
            ) {
                Button("Stop", role: .destructive) {
                    if let removing = picking.removing { folders.remove?(removing) }
                    picking.removing = nil
                }
            } message: {
                Text("What it sent stays on the server.")
            }
    }
}

/// The pills: a tap turns one on or off (a folder the app can no longer read is picked again instead); a long press on a
/// folder asks to stop backing it up. With `picking` (Backup), "+ Add a folder" ends the list. `showBytes` adds each
/// pill's bytes (Free up space).
struct BackupPillsView: View {
    let pills: [BackupPill]
    let toggleSource: (MediaSource) -> Void
    let folders: SoundFolderActions
    var picking: FolderPicking? = nil
    var showBytes = false

    var body: some View {
        FlowLayout(spacing: 8) {
            ForEach(pills, id: \.key) { pill in
                pillView(pill)
            }
            if let picking {
                PillLabel(text: String(localized: "Add a folder"), symbol: "plus", on: false) { picking.pick() }
            }
        }
        .padding(.vertical, 6)
    }

    @ViewBuilder
    private func pillView(_ pill: BackupPill) -> some View {
        if let source = pill as? BackupPillSource {
            PillLabel(text: label(source.source.title, count: Int(source.count), bytes: source.bytes?.int64Value), symbol: nil, on: source.on) {
                toggleSource(source.source)
            }
        } else if let folder = pill as? BackupPillFolder {
            PillLabel(
                text: folder.lost ? String(localized: "\(folder.folder.name) · pick again") : label(folder.folder.name, count: Int(folder.count), bytes: folder.bytes?.int64Value),
                symbol: folder.folder.kind.symbol,
                on: folder.on && !folder.lost,
                warning: folder.lost
            ) {
                if folder.lost, let picking {
                    picking.pick(replacing: folder.folder)
                } else {
                    folders.toggle(folder.folder)
                }
            }
            .simultaneousGesture(LongPressGesture().onEnded { _ in if folders.remove != nil { picking?.removing = folder.folder } })
        }
    }

    private func label(_ name: String, count: Int, bytes: Int64?) -> String {
        var text = name
        if count > 0 { text += " · \(count.formatted())" }
        if showBytes, let bytes, bytes > 0 { text += " · \(formatBytes(bytes))" }
        return text
    }
}

/// A pill in the look of the app's chips: filled with a check when on, outlined when off.
private struct PillLabel: View {
    let text: String
    let symbol: String?
    let on: Bool
    var warning = false
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 6) {
                if on {
                    Image(systemName: "checkmark").font(.system(size: 12, weight: .semibold))
                } else if let symbol {
                    Image(systemName: symbol).font(.system(size: 12, weight: .semibold))
                }
                Text(verbatim: text).font(.system(size: 14, weight: .semibold))
            }
            .foregroundStyle(on ? LibraryColors.onInverse : warning ? LibraryColors.warning : LibraryColors.onBackground)
            .padding(.horizontal, 14)
            .frame(height: 34)
            .background {
                if on {
                    Capsule().fill(LibraryColors.inverse)
                } else {
                    Capsule().stroke(LibraryColors.muted.opacity(0.45))
                }
            }
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(on ? [.isSelected] : [])
    }
}

extension AudioKind {
    var symbol: String {
        switch self {
        case .call: return "phone"
        case .voiceMessage: return "bubble.left.and.text.bubble.right"
        default: return "mic"
        }
    }

    var folderName: String {
        switch self {
        case .call: return String(localized: "Phone calls")
        case .voiceMessage: return String(localized: "Voice messages")
        default: return String(localized: "Recordings")
        }
    }
}

/// After the Files picker: the folder's name and place, what was found in it, what its files are (the app's guess
/// chosen), and Back it up.
private struct AddFolderSheet: View {
    let picked: PickedFolder
    let add: (AudioKind) -> Void
    let cancel: () -> Void
    @State private var kind: AudioKind?

    var body: some View {
        let chosen = kind ?? picked.look.guess
        NavigationStack {
            Form {
                Section {
                    VStack(alignment: .leading, spacing: 8) {
                        Image(systemName: chosen.symbol)
                            .font(.system(size: 20))
                            .foregroundStyle(LibraryColors.photoText)
                            .frame(width: 44, height: 44)
                            .background(LibraryColors.accent, in: Circle())
                        Text(verbatim: picked.folder.path).font(.footnote).foregroundStyle(LibraryColors.muted)
                        Text(verbatim: "\(String(localized: "\(Int(picked.look.files)) sound files")) · \(formatBytes(picked.look.bytes))")
                            .font(.headline)
                            .foregroundStyle(LibraryColors.onBackground)
                    }
                    .padding(.vertical, 4)
                }
                .listRowBackground(LibraryColors.raised)
                Section {
                    Picker("What are they?", selection: Binding(get: { chosen }, set: { kind = $0 })) {
                        ForEach([AudioKind.call, AudioKind.recording, AudioKind.voiceMessage], id: \.self) { option in
                            Label(option.folderName, systemImage: option.symbol).tag(option)
                        }
                    }
                    .pickerStyle(.inline)
                    .labelsHidden()
                } header: {
                    Text("What are they?")
                } footer: {
                    Text("New files in it are backed up as they come, even while hidden. Calls keep who they were with, as the phone named each file.")
                }
                .listRowBackground(LibraryColors.raised)
            }
            .scrollContentBackground(.hidden)
            .background(LibraryColors.background.ignoresSafeArea())
            .navigationTitle(String(localized: "Back up \(picked.folder.name)?"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel", action: cancel) }
                ToolbarItem(placement: .confirmationAction) { Button("Back it up") { add(chosen) } }
            }
        }
    }
}

/// Under Backup's pills: WAVs go as FLAC, and which of the two stays on the phone. Always shown, as the owner asked
/// (Oct 25, 2026), also before a sound folder is added.
struct KeepOnPhoneSection: View {
    let keep: KeepOnPhone
    let setKeep: (KeepOnPhone) -> Void

    // A switch per kind, as Android's card. "Photos as JPEG XL" isn't offered: it would put the JPEG XL in the photo
    // library in place of the JPEG, and this app never writes there.
    var body: some View {
        let flac = keep == .flac
        Section {
            Toggle(isOn: Binding(get: { flac }, set: { setKeep($0 ? .flac : .wav) })) {
                SettingsLabel(
                    "Recordings as FLAC",
                    detail: flac ? "FLAC replaces each WAV once the server has it: about half the space." : "WAVs stay as they are."
                )
            }
            .tint(LibraryColors.accent)
        } header: {
            Text("Keep on this phone")
        } footer: {
            Text("The server keeps recordings as FLAC and photos as JPEG XL, from which the very same WAV or JPEG comes back.")
        }
        .listRowBackground(LibraryColors.raised)
    }
}
