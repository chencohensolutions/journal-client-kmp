import SwiftUI
import SharedLogic

/// Settings on the live view models: the user's choices, backup, and Free up space (its row and its screen). Counts
/// the storage on open.
struct SettingsView: View {
    @ObservedObject var settings: SettingsViewModel
    @ObservedObject var backup: BackupViewModel
    /// Photos of the chosen sources not on the server yet.
    let notBackedUp: Int
    /// Photos per source on this device and the server, shown or not (Show in the app).
    let sourceCounts: [MediaSource: KotlinInt]
    /// The library reads PhotoKit again once Free up space removed photos.
    var refreshLibrary: () -> Void = {}
    /// Search › On this phone, when the server lets phones keep its indexes; nil: no row.
    var phoneSearch: PhoneSearchViewModel?
    /// Search › On your server, when the server offers it; nil: none.
    var indexing: ServerIndexingViewModel?
    /// The library's counts per source and its sound recordings, for Show in the app.
    var library: LibraryState?
    /// What the server offers: Your cameras' and Sound folders' rows show only when it keeps them.
    var features: ServerFeatures = ServerFeatures.companion.NONE
    @StateObject private var freeUp = FreeUpViewModel()
    /// Your cameras' and Sound folders' last lists, for their rows; their pages keep them current.
    @State private var cameras: CamerasState = CamerasStateLoading.shared
    @State private var soundFolders: SoundFoldersState = SoundFoldersStateLoading.shared

    var body: some View {
        SettingsScreen(
            content: SettingsContent(
                settings: settings.state, backup: backup.state, notBackedUp: notBackedUp, sourceCounts: sourceCounts,
                previewBytes: settings.previewBytes, serverUsage: settings.serverUsage, freeUp: freeUp.state, actions: actions,
                syncProgress: settings.syncProgress, phoneSearch: phoneSearch?.state, indexing: indexing?.state,
                kinds: kinds, cameras: features.cameras ? cameras : nil, soundFolders: features.soundFolders ? soundFolders : nil
            ),
            searchScreen: { [settings, indexing, phoneSearch] in
                AnyView(SearchSettingsView(settings: settings, indexing: indexing, phoneSearch: phoneSearch))
            },
            backupScreen: { [settings, backup, notBackedUp, sourceCounts, actions, features] in
                AnyView(BackupSettingsView(
                    backup: backup, settings: settings, notBackedUp: notBackedUp, sourceCounts: sourceCounts, actions: actions,
                    copiesOffered: features.copies
                ))
            }
        ) {
            FreeUpView(freeUp: freeUp, refreshLibrary: refreshLibrary)
        } hiddenScreen: {
            HiddenView(settings: settings)
        }
        .camerasScreen { AnyView(CamerasView { if $0 is CamerasStateReady { cameras = $0 } }) }
        .soundFoldersScreen { AnyView(SoundFoldersView { if $0 is SoundFoldersStateReady { soundFolders = $0 } }) }
        // Counted on open (and again when the limit changes or Clear is tapped).
        .task {
            settings.refreshStorage()
            freeUp.refreshPlan()
            // Search's row says how the server's reading of this user's photos is going.
            indexing?.refresh()
        }
        // Your cameras' and Sound folders' rows say how many there are and how many are marked.
        .task(id: features.cameras) {
            guard features.cameras, let models = try? await IosJournal.shared.serverLibrary().cameras() else { return }
            cameras = CamerasStateReady(models: models, saving: false, notChanged: false)
        }
        .task(id: features.soundFolders) {
            guard features.soundFolders, let folders = try? await IosJournal.shared.serverLibrary().soundFolders() else { return }
            soundFolders = SoundFoldersStateReady(folders: folders, saving: false, notChanged: false)
        }
    }

    /// Show in the app: every kind with its files and what isn't on the server yet; kinds Private settings hides on
    /// every device only counted.
    private var kinds: KindsStatus {
        guard let library else { return .empty }
        return KindsStatus.companion.of(
            sources: library.sourceTotals(), sounds: library.sounds, soundsNotBackedUp: backup.state.soundsNotBackedUp(),
            hidden: settings.hiddenEverywhere
        )
    }

    private var actions: SettingsActions {
        SettingsActions(
            setGroupingMode: settings.setGroupingMode, setGapHours: settings.setGapHours, setSimilarityPercent: settings.setSimilarityPercent,
            resetSimilarity: settings.resetSimilarity, toggleFeedSource: settings.toggleFeedSource, setSayWhatsHidden: settings.setSayWhatsHidden,
            setShowFilterBar: settings.setShowFilterBar,
            setPreviewsEnabled: settings.setPreviewsEnabled, setPreviewFormat: settings.setPreviewFormat,
            setPreviewQuality: settings.setPreviewQuality, setPreviewLimit: settings.setPreviewLimit, clearPreviews: settings.clearPreviews,
            setShowPrivateMedia: settings.setShowPrivateMedia,
            setShowOnPhoneBadge: settings.setShowOnPhoneBadge, setSearchPageSize: settings.setSearchPageSize,
            setBackupEnabled: backup.setEnabled, toggleBackupSource: backup.toggleSource, setWifiOnly: backup.setWifiOnly,
            setChargingOnly: backup.setChargingOnly, setOrder: backup.setOrder, backUpNow: backup.backUpNow,
            setMomentsRowsPerPage: settings.setMomentsRowsPerPage, setMomentsLoadAhead: settings.setMomentsLoadAhead,
            resetMomentsPaging: settings.resetMomentsPaging, setShareJpegXlAsJpeg: settings.setShareJpegXlAsJpeg
        )
    }
}

/// What Settings can ask for: the user's choices (`SettingsViewModel`) and backup's (`BackupViewModel`).
struct SettingsActions {
    var setGroupingMode: (GroupingMode) -> Void
    var setGapHours: (Int) -> Void
    var setSimilarityPercent: (Int) -> Void
    var resetSimilarity: () -> Void
    /// Show in the app: a source on or off everywhere (the Moments filter bar, Explore's sheet), and "Say what's hidden".
    var toggleFeedSource: (MediaSource) -> Void
    var setSayWhatsHidden: (Bool) -> Void
    var setShowFilterBar: (Bool) -> Void
    /// Storage on this phone: how server photos are shown, the space they may take here, and "Clear".
    var setPreviewsEnabled: (Bool) -> Void
    var setPreviewFormat: (PreviewFormat) -> Void
    var setPreviewQuality: (PreviewQuality) -> Void
    var setPreviewLimit: (_ gigabytes: Int) -> Void
    var clearPreviews: () -> Void
    var setShowPrivateMedia: (Bool) -> Void
    var setShowOnPhoneBadge: (Bool) -> Void
    var setSearchPageSize: (Int) -> Void
    // Backup.
    var setBackupEnabled: (Bool) -> Void
    var toggleBackupSource: (MediaSource) -> Void
    var setWifiOnly: (Bool) -> Void
    var setChargingOnly: (Bool) -> Void
    var setOrder: (UploadOrder) -> Void
    var backUpNow: () -> Void
    /// Settings › Loading Moments: rows read at a time, how near the end the next are read, and both back to the defaults.
    var setMomentsRowsPerPage: (Int) -> Void = { _ in }
    var setMomentsLoadAhead: (Int) -> Void = { _ in }
    var resetMomentsPaging: () -> Void = {}
    /// "Share JPEG XL as JPEG".
    var setShareJpegXlAsJpeg: (Bool) -> Void = { _ in }
}

/// The user's choices, kept on this device: a short page of groups (Library, Privacy, Backup and storage, Search),
/// each row saying how it stands and opening a page of its own (SettingsSections.swift); the switches used most stay
/// here. Drawn from the states alone, so the UI lab shows it too. See docs/screens/settings.md.
struct SettingsScreen<FreeUp: View, Hidden: View>: View {
    let content: SettingsContent
    /// Settings › Search, opened from its row.
    var searchScreen: () -> AnyView = { AnyView(EmptyView()) }
    /// Settings › Backup on the live view models, opened from its row; nil: drawn from the content's states (the UI lab).
    var backupScreen: (() -> AnyView)? = nil
    /// The Free up space screen, opened from its row.
    @ViewBuilder let freeUpScreen: () -> FreeUp
    /// Private settings, opened from Privacy and from Show in the app.
    @ViewBuilder let hiddenScreen: () -> Hidden

    /// Settings › Your cameras and Sound folders on the live view models, opened from their rows.
    var camerasScreen: () -> AnyView = { AnyView(EmptyView()) }
    var soundFoldersScreen: () -> AnyView = { AnyView(EmptyView()) }

    var body: some View {
        SettingsPage(title: "Settings") {
            Section {
                PageRow("Show in the app", detail: content.kinds.rowText) {
                    ShowInAppPage(status: content.kinds, backupScreen: backupDestination, privateScreen: hiddenScreen)
                }
                if let cameras = content.cameras {
                    PageRow("Your cameras", detail: cameras.rowText, destination: camerasScreen)
                }
                if let soundFolders = content.soundFolders {
                    PageRow("Sound folders", detail: soundFolders.rowText, destination: soundFoldersScreen)
                }
                PageRow("Moments", detail: content.momentsSummary) {
                    SettingsPage(title: "Moments") {
                        MomentsSections(content: content)
                        MomentsFilterSection(content: content)
                    }
                }
                PhoneBadgeToggle(content: content)
                ShareJpegXlToggle(content: content)
            } header: {
                Text("Library")
            }
            .listRowBackground(LibraryColors.raised)

            Section {
                NavigationLink { hiddenScreen() } label: { HiddenCollectionsLabel(syncProgress: content.syncProgress) }
            } header: {
                Text("Privacy")
            }
            .listRowBackground(LibraryColors.raised)

            Section {
                PageRow("Backup", detail: content.backupSummary, destination: backupDestination)
                PageRow("Storage on this phone", detail: content.storageSummary) {
                    SettingsPage(title: "Storage on this phone") { StorageSections(content: content) }
                }
                PageRow("Free up space", detail: content.freeUp.rowText) { freeUpScreen() }
            } header: {
                Text("Backup and storage")
            }
            .listRowBackground(LibraryColors.raised)

            Section {
                PageRow("Search", detail: content.searchSummary) { searchScreen() }
            }
            .listRowBackground(LibraryColors.raised)

            Section {
                PageRow("Loading Moments", detail: content.settings.momentsPaging.rowText) {
                    LoadingMomentsPage(paging: content.settings.momentsPaging, actions: content.actions)
                }
            } header: {
                Text("Advanced")
            }
            .listRowBackground(LibraryColors.raised)
        }
    }

    /// Settings › Backup: on the live view models, or drawn from the content's states (the UI lab).
    @ViewBuilder private func backupDestination() -> some View {
        if let backupScreen {
            backupScreen()
        } else {
            BackupSettingsScreen(
                backup: content.backup, notBackedUp: content.notBackedUp, sourceCounts: content.sourceCounts,
                serverUsage: content.serverUsage, actions: content.actions
            )
        }
    }
}

extension SettingsScreen {
    func camerasScreen(_ screen: @escaping () -> AnyView) -> Self {
        var copy = self
        copy.camerasScreen = screen
        return copy
    }

    func soundFoldersScreen(_ screen: @escaping () -> AnyView) -> Self {
        var copy = self
        copy.soundFoldersScreen = screen
        return copy
    }
}

/// A row that opens a page: its title and how it stands.
private struct PageRow<Destination: View>: View {
    let title: LocalizedStringKey
    let detail: String
    @ViewBuilder let destination: () -> Destination

    init(_ title: LocalizedStringKey, detail: String, @ViewBuilder destination: @escaping () -> Destination) {
        self.title = title
        self.detail = detail
        self.destination = destination
    }

    var body: some View {
        NavigationLink {
            destination()
        } label: {
            VStack(alignment: .leading, spacing: 2) {
                Text(title).foregroundStyle(LibraryColors.onBackground)
                Text(verbatim: detail).font(.footnote).monospacedDigit().foregroundStyle(LibraryColors.muted)
            }
        }
    }
}

extension PreviewFormat {
    static let shownInSettings: [PreviewFormat] = [.webp, .heif, .avif, .jpegXl, .jpeg]

    var label: String {
        switch self {
        case .heif: return "HEIF"
        case .avif: return "AVIF"
        case .jpegXl: return "JPEG XL"
        case .jpeg: return "JPEG"
        default: return "WebP"
        }
    }

    /// What a format is good for.
    var hint: String {
        switch self {
        case .heif: return String(localized: "About a third smaller than WebP at the same quality.")
        case .avif: return String(localized: "The smallest files; the server takes longer to make them.")
        case .jpegXl: return String(localized: "The most detail for its size.")
        case .jpeg: return String(localized: "Works everywhere; the largest files.")
        default: return String(localized: "Quick to make and to show.")
        }
    }
}

extension PreviewQuality {
    static let shownInSettings: [PreviewQuality] = [.low, .medium, .high, .max]

    var label: String {
        switch self {
        case .low: return String(localized: "Low")
        case .medium: return String(localized: "Medium")
        case .max: return String(localized: "Max")
        default: return String(localized: "High")
        }
    }
}
