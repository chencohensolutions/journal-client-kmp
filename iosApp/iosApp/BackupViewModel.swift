import Foundation
import SharedLogic

/// Bridges the shared Kotlin `BackupController` (backup to the server, run by `BackupService`) into SwiftUI.
final class BackupViewModel: ObservableObject {
    @Published private(set) var state: BackupState

    /// Shared with `LibraryViewModel`, whose photos' badges follow it.
    let controller: BackupController
    private var observation: ObservationHandle?

    init() {
        controller = BackupService.shared.controller
        state = controller.currentState
        // The controller delivers updates on the main thread.
        observation = controller.observe { [weak self] newState in
            self?.state = newState
        }
    }

    deinit {
        observation?.cancel()
    }

    /// The run's progress while one is going.
    var running: BackupProgress? { (state.activity as? BackupActivityRunning)?.progress }

    func setEnabled(_ enabled: Bool) { controller.setEnabled(enabled: enabled) }
    func toggleSource(_ source: MediaSource) { controller.toggleSource(source: source) }
    func setWifiOnly(_ wifiOnly: Bool) { controller.setWifiOnly(wifiOnly: wifiOnly) }
    func setChargingOnly(_ chargingOnly: Bool) { controller.setChargingOnly(chargingOnly: chargingOnly) }
    func setOrder(_ order: UploadOrder) { controller.setOrder(order: order) }
    func backUpNow() { controller.backUpNow() }

    /// Folders of calls and recordings: added from the Files picker, turned on or off, forgotten, and WAV or FLAC kept.
    var folderActions: SoundFolderActions {
        SoundFolderActions(
            add: { [weak self] url in await self?.look(url) },
            confirm: { [weak self] picked, kind in self?.add(picked, kind: kind) },
            toggle: { [weak self] folder in self?.controller.setSoundFolderEnabled(treeUri: folder.treeUri, enabled: !folder.enabled) },
            remove: { [weak self] folder in self?.controller.removeSoundFolder(treeUri: folder.treeUri) },
            setKeepOnPhone: { [weak self] keep in self?.controller.setKeepOnPhone(keep: keep) }
        )
    }

    /// Keeps lasting access to the folder the Files picker returned and looks into it; nil when it can't be read.
    @MainActor
    private func look(_ url: URL) async -> PickedFolder? {
        guard let folder = IosJournal.shared.soundFolders.add(url: url),
              let look = try? await controller.look(folder: folder) else { return nil }
        return PickedFolder(folder: folder, look: look)
    }

    private func add(_ picked: PickedFolder, kind: AudioKind) {
        let folder = picked.folder
        controller.addSoundFolder(
            folder: folder.doCopy(treeUri: folder.treeUri, name: folder.name, path: folder.path, kind: kind, enabled: true),
            look: picked.look
        )
    }
}

extension BackupState {
    /// The line under the switch: off, running, waiting, stopped, or what is left (`waiting`: photos not backed up).
    func statusText(waiting: Int) -> String {
        if !settings.enabled { return String(localized: "Off. Nothing is uploaded.") }
        switch activity {
        case let running as BackupActivityRunning:
            let progress = running.progress
            let bytes = String(localized: "\(formatBytes(progress.bytesDone)) of \(formatBytes(progress.bytesTotal))")
            return backupProgressText(progress) + "\n" + bytes
        case let waitingFor as BackupActivityWaiting:
            switch waitingFor.reason {
            case .wifiNetworkNotAllowed: return String(localized: "Waiting: this Wi-Fi network isn’t on the list")
            case .needsLocation: return String(localized: "Waiting: allow location so the Wi-Fi network can be checked")
            case .notRegistered: return String(localized: "Waiting: register this device again")
            default: return String(localized: "Waiting: allow access to photos")
            }
        case let stopped as BackupActivityStopped:
            return String(localized: "Stopped: \(stopped.message). It tries again later.")
        default:
            return waiting > 0
                ? String(localized: "\(waiting) photos waiting to upload")
                : String(localized: "Everything chosen is backed up")
        }
    }
}

/// "Backing up 3 of 120 · 42% · 5.1 MB/s": the file, the whole run in percent, and the transfer rate once known.
func backupProgressText(_ progress: BackupProgress) -> String {
    let file = min(Int(progress.done) + 1, Int(progress.total))
    var parts = [String(localized: "Backing up \(file) of \(Int(progress.total))"), "\(Int(progress.fraction * 100))%"]
    if let rate = progress.bytesPerSecond { parts.append(String(localized: "\(formatBytes(rate.int64Value))/s")) }
    return parts.joined(separator: " · ")
}

/// The bottom pill's text: the running backup, else how many photos aren't backed up; nil when there's nothing to say.
func backupSummary(running: BackupProgress?, notBackedUp: Int) -> String? {
    if let running, running.total > 0 { return backupProgressText(running) }
    return notBackedUp > 0 ? String(localized: "\(notBackedUp) not backed up") : nil
}

/// "4.2 MB".
func formatBytes(_ bytes: Int64) -> String {
    ByteCountFormatter.string(fromByteCount: bytes, countStyle: .file)
}
