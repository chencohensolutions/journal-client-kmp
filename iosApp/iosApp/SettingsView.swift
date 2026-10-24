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
    @StateObject private var freeUp = FreeUpViewModel()

    var body: some View {
        SettingsScreen(
            content: SettingsContent(
                settings: settings.state, backup: backup.state, notBackedUp: notBackedUp, sourceCounts: sourceCounts,
                previewBytes: settings.previewBytes, serverUsage: settings.serverUsage, freeUp: freeUp.state, actions: actions,
                syncProgress: settings.syncProgress, phoneSearch: phoneSearch?.state, indexing: indexing?.state
            ),
            searchScreen: { [settings, indexing, phoneSearch] in
                AnyView(SearchSettingsView(settings: settings, indexing: indexing, phoneSearch: phoneSearch))
            }
        ) {
            FreeUpView(freeUp: freeUp, refreshLibrary: refreshLibrary)
        } hiddenScreen: {
            HiddenView()
        }
        // Counted on open (and again when the limit changes or Clear is tapped).
        .task {
            settings.refreshStorage()
            freeUp.refreshPlan()
            // Search's row says how the server's reading of this user's photos is going.
            indexing?.refresh()
        }
    }

    private var actions: SettingsActions {
        SettingsActions(
            setGroupingMode: settings.setGroupingMode, setGapHours: settings.setGapHours, setSimilarityPercent: settings.setSimilarityPercent,
            resetSimilarity: settings.resetSimilarity, toggleFeedSource: settings.toggleFeedSource, setSayWhatsHidden: settings.setSayWhatsHidden,
            setPreviewsEnabled: settings.setPreviewsEnabled, setPreviewFormat: settings.setPreviewFormat,
            setPreviewQuality: settings.setPreviewQuality, setPreviewLimit: settings.setPreviewLimit, clearPreviews: settings.clearPreviews,
            setShowPrivateMedia: settings.setShowPrivateMedia,
            setShowOnPhoneBadge: settings.setShowOnPhoneBadge, setSearchPageSize: settings.setSearchPageSize,
            setBackupEnabled: backup.setEnabled, toggleBackupSource: backup.toggleSource, setWifiOnly: backup.setWifiOnly,
            setChargingOnly: backup.setChargingOnly, setOrder: backup.setOrder, backUpNow: backup.backUpNow
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
}

/// The user's choices, kept on this device: a short page of groups (Library, Privacy, Backup and storage, Search),
/// each row saying how it stands and opening a page of its own (SettingsSections.swift); the switches used most stay
/// here. Drawn from the states alone, so the UI lab shows it too. See docs/screens/settings.md.
struct SettingsScreen<FreeUp: View, Hidden: View>: View {
    let content: SettingsContent
    /// Settings › Search, opened from its row.
    var searchScreen: () -> AnyView = { AnyView(EmptyView()) }
    /// The Free up space screen, opened from its row.
    @ViewBuilder let freeUpScreen: () -> FreeUp
    /// Hidden collections, opened from Privacy and from Show in the app.
    @ViewBuilder let hiddenScreen: () -> Hidden

    var body: some View {
        SettingsPage(title: "Settings") {
            Section {
                PageRow("Show in the app", detail: content.shownSummary) {
                    SettingsPage(title: "Show in the app") {
                        ShownSourcesSection(content: content)
                        Section {
                            NavigationLink { hiddenScreen() } label: { HiddenCollectionsLabel(syncProgress: content.syncProgress) }
                        } header: {
                            Text("On all your devices")
                        }
                        .listRowBackground(LibraryColors.raised)
                    }
                }
                PageRow("Moments", detail: content.momentsSummary) {
                    SettingsPage(title: "Moments") { MomentsSections(content: content) }
                }
                PhoneBadgeToggle(content: content)
            } header: {
                Text("Library")
            }
            .listRowBackground(LibraryColors.raised)

            Section {
                PrivateMediaToggle(content: content)
                NavigationLink { hiddenScreen() } label: { HiddenCollectionsLabel(syncProgress: content.syncProgress) }
            } header: {
                Text("Privacy")
            }
            .listRowBackground(LibraryColors.raised)

            Section {
                PageRow("Backup", detail: content.backupSummary) {
                    SettingsPage(title: "Backup") { BackupSections(content: content) }
                }
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
        }
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
