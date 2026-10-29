import SwiftUI
import SharedLogic

/// Settings › Backup on the live view models, a screen of its own since Oct 24, 2026. As Android's
/// BackupSettingsScreen; see docs/screens/settings.md.
struct BackupSettingsView: View {
    @ObservedObject var backup: BackupViewModel
    @ObservedObject var settings: SettingsViewModel
    /// Photos of the chosen sources not on the server yet.
    let notBackedUp: Int
    /// Photos per source on this device and the server: a pill each, the most first.
    let sourceCounts: [MediaSource: KotlinInt]
    let actions: SettingsActions
    /// Whether the server keeps copies on other disks: Backup's Copies row.
    var copiesOffered = false
    @ObservedObject private var copies = CopiesViewModel.shared

    var body: some View {
        BackupSettingsScreen(
            backup: backup.state, notBackedUp: notBackedUp, sourceCounts: sourceCounts, serverUsage: settings.serverUsage,
            actions: actions, folders: backup.folderActions,
            copies: copiesOffered && copies.state.loaded ? copies.state : nil,
            copiesScreen: { [copies] in AnyView(CopiesView(copies: copies)) }
        )
        .task(id: copiesOffered) { if copiesOffered { copies.refresh() } }
    }
}

/// Backup: on or off with its status, then what uploads (a pill per source and per folder of calls and recordings, the
/// ones on first, "+ Add a folder" last), which of a WAV and its FLAC the phone keeps, when, and in which order. "Only on
/// these Wi-Fi networks" is Android only for now. Drawn from the states alone.
struct BackupSettingsScreen: View {
    let backup: BackupState
    let notBackedUp: Int
    let sourceCounts: [MediaSource: KotlinInt]
    /// What the user's originals take up on the server; nil until it answered.
    let serverUsage: StorageUsage?
    let actions: SettingsActions
    /// The sound folders' actions; none in the UI lab.
    var folders = SoundFolderActions()
    /// Copies on other disks, once the server answered; nil: no row.
    var copies: ServerCopiesState? = nil
    var copiesScreen: () -> AnyView = { AnyView(EmptyView()) }
    @StateObject private var picking = FolderPicking()

    var body: some View {
        let state = backup.settings
        Form {
            Section {
                Toggle(isOn: Binding(get: { state.enabled }, set: actions.setBackupEnabled)) {
                    SettingsLabel("Back up to your server",
                        detail: "Photos keep uploading in the background. Swiping the app away stops it until you open it again."
                    )
                }
                .tint(LibraryColors.accent)
                HStack {
                    Text(backup.statusText(waiting: notBackedUp))
                        .font(.subheadline)
                        .monospacedDigit()
                        .foregroundStyle(LibraryColors.muted)
                    Spacer()
                    if state.enabled && !(backup.activity is BackupActivityRunning) {
                        Button("Back up now", action: actions.backUpNow).foregroundStyle(LibraryColors.onBackground)
                    }
                }
                VStack(alignment: .leading, spacing: 2) {
                    Text("On your server").foregroundStyle(LibraryColors.onBackground)
                    Text(serverUsageLine(serverUsage))
                        .font(.footnote)
                        .monospacedDigit()
                        .foregroundStyle(LibraryColors.muted)
                }
                if let copies { CopiesRow(copies: copies, destination: copiesScreen) }
            }
            .listRowBackground(LibraryColors.raised)
            if state.enabled {
                Section {
                    BackupPillsView(
                        pills: backup.pills(counts: sourceCounts), toggleSource: actions.toggleBackupSource, folders: folders,
                        picking: folders.add == nil ? nil : picking
                    )
                } header: {
                    Text("What to back up")
                } footer: {
                    if !state.soundFolders.isEmpty {
                        Text("Folders of calls and recordings are backed up even while they are hidden in Private settings.")
                    }
                }
                .listRowBackground(LibraryColors.background)
                KeepOnPhoneSection(keep: state.keepOnPhone, setKeep: folders.setKeepOnPhone)
                Section {
                    Toggle(isOn: Binding(get: { state.wifiOnly }, set: actions.setWifiOnly)) {
                        SettingsLabel("Only on Wi-Fi", detail: "Never on mobile data.")
                    }
                    .tint(LibraryColors.accent)
                    Toggle(isOn: Binding(get: { state.chargingOnly }, set: actions.setChargingOnly)) {
                        SettingsLabel("Only while charging", detail: "Saves the battery; uploads wait for the charger.")
                    }
                    .tint(LibraryColors.accent)
                } header: {
                    Text("When")
                }
                .listRowBackground(LibraryColors.raised)
                Section {
                    Picker("Order", selection: Binding(get: { state.order }, set: actions.setOrder)) {
                        SettingsLabel("Oldest first", detail: "Starts with your earliest photos.").tag(UploadOrder.oldestFirst)
                        SettingsLabel("Newest first", detail: "Your latest photos are safe soonest.").tag(UploadOrder.newestFirst)
                    }
                    .pickerStyle(.inline)
                    .labelsHidden()
                } header: {
                    Text("Order")
                }
                .listRowBackground(LibraryColors.raised)
            }
        }
        .scrollContentBackground(.hidden)
        .background(LibraryColors.background.ignoresSafeArea())
        .soundFolderPicking(picking, folders)
        .navigationTitle("Backup")
        .navigationBarTitleDisplayMode(.inline)
        .toolbarBackground(LibraryColors.background, for: .navigationBar)
    }
}

/// A pill per source, "Petcube · 5" (its photos in `counts`), in the order given: Settings › Show in the app and Backup.
struct SourcePills: View {
    let sources: [MediaSource]
    let counts: [MediaSource: KotlinInt]
    let isOn: (MediaSource) -> Bool
    let toggle: (MediaSource) -> Void

    var body: some View {
        FlowLayout(spacing: 8) {
            ForEach(sources, id: \.self) { source in
                SourcePill(source: source, count: counts[source]?.intValue ?? 0, on: isOn(source)) { toggle(source) }
            }
        }
        .padding(.vertical, 6)
    }
}

/// A source on or off: "Petcube · 5", filled with a ✓ when on, outlined when off.
private struct SourcePill: View {
    let source: MediaSource
    let count: Int
    let on: Bool
    let toggle: () -> Void

    var body: some View {
        Button(action: toggle) {
            HStack(spacing: 6) {
                if on { Image(systemName: "checkmark").font(.system(size: 12, weight: .semibold)) }
                Text(verbatim: count > 0 ? "\(source.title) · \(count.formatted())" : source.title).font(.system(size: 14, weight: .semibold))
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
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(on ? [.isSelected] : [])
    }
}
