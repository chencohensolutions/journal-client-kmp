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
            settings: settings.state, backup: backup.state, notBackedUp: notBackedUp, sourceCounts: sourceCounts,
            previewBytes: settings.previewBytes, serverUsage: settings.serverUsage, freeUp: freeUp.state, actions: actions,
            syncProgress: settings.syncProgress,
            phoneSearch: phoneSearch?.state,
            indexing: indexing?.state,
            searchScreen: { [settings, indexing, phoneSearch] in
                AnyView(SearchSettingsView(settings: settings, indexing: indexing, phoneSearch: phoneSearch))
            },
            backupScreen: { [settings, backup, notBackedUp, sourceCounts, actions] in
                AnyView(BackupSettingsView(backup: backup, settings: settings, notBackedUp: notBackedUp, sourceCounts: sourceCounts, actions: actions))
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
            setShowFilterBar: settings.setShowFilterBar,
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
}

/// The user's choices, kept on this device. Drawn from the states alone, so the UI lab shows it too. See
/// docs/screens/settings.md.
struct SettingsScreen<FreeUp: View, Hidden: View>: View {
    let settings: SettingsState
    let backup: BackupState
    /// Photos of the chosen sources not on the server yet.
    let notBackedUp: Int
    /// Photos per source on this device and the server, shown or not (Show in the app).
    let sourceCounts: [MediaSource: KotlinInt]
    /// What previews and downloaded originals of server photos take here; nil while counting.
    let previewBytes: Int64?
    /// What the user's originals take up on the server; nil until it answered.
    let serverUsage: StorageUsage?
    /// For the Free up space row: what it would free, or what it is doing.
    let freeUp: FreeUpState
    let actions: SettingsActions
    /// How far the library's sync is, shown on Hidden collections' row (after a change there); nil when not syncing.
    var syncProgress: SyncProgress?
    /// For Search's row: what this phone keeps, and how the server's reading goes; nil when the server has none.
    var phoneSearch: PhoneSearchState? = nil
    var indexing: ServerIndexingState? = nil
    /// Settings › Search, opened from its row.
    var searchScreen: () -> AnyView = { AnyView(EmptyView()) }
    /// Settings › Backup on the live view models, opened from its row; nil: drawn from these states (the UI lab).
    var backupScreen: (() -> AnyView)? = nil
    /// The Free up space screen, opened from its row.
    @ViewBuilder let freeUpScreen: () -> FreeUp
    /// Hidden collections, opened from Private media.
    @ViewBuilder let hiddenScreen: () -> Hidden
    /// The slider's value while it is dragged; saved when let go.
    @State private var similarity: Double?

    var body: some View {
        let grouping = settings.grouping
        Form {
            shownSourcesSection
            backupSection
            badgesSection
            storageSection
            freeUpSection
            Section {
                Picker("Grouping", selection: Binding(get: { grouping.mode }, set: actions.setGroupingMode)) {
                    ModeLabel(title: "By day", detail: "One row for each calendar day.")
                        .tag(GroupingMode.byDay)
                    ModeLabel(
                        title: "By breaks between photos",
                        detail: "A new row starts after a break without photos, so an evening that runs past midnight stays in one row."
                    )
                    .tag(GroupingMode.byGap)
                }
                .pickerStyle(.inline)
                .labelsHidden()
                if grouping.mode == .byGap {
                    Picker("New row after a break of", selection: Binding(get: { Int(grouping.gapHours) }, set: actions.setGapHours)) {
                        ForEach(SettingsViewModel.gapHoursOptions, id: \.self) { hours in
                            Text("\(hours) hours").tag(hours)
                        }
                    }
                }
            } header: {
                Text("Rows in Moments")
            }
            .listRowBackground(LibraryColors.raised)
            similaritySection
            privateMediaSection
            searchSection
        }
        .scrollContentBackground(.hidden)
        .background(LibraryColors.background.ignoresSafeArea())
        .navigationTitle("Settings")
        .navigationBarTitleDisplayMode(.inline)
        .toolbarBackground(LibraryColors.background, for: .navigationBar)
    }
}

extension SettingsScreen {
    /// Show in the app: a pill per source with its count, the most photos first (the same as the Moments filter bar
    /// and Explore's "Showing" sheet), "Say what's hidden" (the "+6 WhatsApp" chips in Moments) and "Filter bar in
    /// Moments".
    private var shownSourcesSection: some View {
        let shown = settings.shownSources
        return Group {
            Section {
                SourcePills(sources: shown.choices(counts: sourceCounts), counts: sourceCounts, isOn: { shown.isShown(source: $0) }, toggle: actions.toggleFeedSource)
            } header: {
                Text("Show in the app")
            }
            .listRowBackground(LibraryColors.background)
            Section {
                Toggle(isOn: Binding(get: { shown.sayWhatsHidden }, set: actions.setSayWhatsHidden)) {
                    ModeLabel(title: "Say what's hidden", detail: "“+6 WhatsApp” on a day, tap to see them")
                }
                .tint(LibraryColors.accent)
                Toggle(isOn: Binding(get: { shown.showFilterBar }, set: actions.setShowFilterBar)) {
                    ModeLabel(title: "Filter bar in Moments", detail: "The row of sources above the feed. When off, choose them here.")
                }
                .tint(LibraryColors.accent)
            } footer: {
                VStack(alignment: .leading, spacing: 6) {
                    Text("Applies to Moments, All photos, Explore and Tags.")
                    Text("Photos you wrote about always show in the storyline. Hiding never deletes anything and doesn't change what is backed up.")
                }
            }
            .listRowBackground(LibraryColors.raised)
        }
    }

    /// Backup's row: its status ("Off. Nothing is uploaded.", what is left), opening Settings › Backup.
    private var backupSection: some View {
        Section {
            NavigationLink {
                if let backupScreen {
                    backupScreen()
                } else {
                    BackupSettingsScreen(backup: backup, notBackedUp: notBackedUp, sourceCounts: sourceCounts, serverUsage: serverUsage, actions: actions)
                }
            } label: {
                VStack(alignment: .leading, spacing: 2) {
                    Text("Backup").fontWeight(.semibold).foregroundStyle(LibraryColors.onBackground)
                    Text(backup.statusText(waiting: notBackedUp))
                        .font(.footnote)
                        .monospacedDigit()
                        .foregroundStyle(LibraryColors.muted)
                }
            }
        }
        .listRowBackground(LibraryColors.raised)
    }

    /// How alike photos must be for a "Look alike" suggestion.
    private var similaritySection: some View {
        let threshold = settings.similarity
        let shown = Int(similarity ?? Double(threshold.percent))
        return Section {
            VStack(alignment: .leading, spacing: 8) {
                HStack {
                    Text("How alike").foregroundStyle(LibraryColors.onBackground)
                    Spacer()
                    Text("\(shown)%").monospacedDigit().foregroundStyle(LibraryColors.onBackground)
                }
                Slider(
                    value: Binding(get: { similarity ?? Double(threshold.percent) }, set: { similarity = $0 }),
                    in: SettingsViewModel.similarityRange,
                    step: 1
                ) { editing in
                    if !editing, let similarity {
                        actions.setSimilarityPercent(Int(similarity))
                        self.similarity = nil
                    }
                }
                .tint(LibraryColors.onBackground)
                Text("Higher: only photos that look nearly the same are suggested together. Lower: more photos in each suggestion, but less alike.")
                    .font(.footnote)
                    .foregroundStyle(LibraryColors.muted)
            }
            if !threshold.isDefault {
                Button("Reset to \(Int(threshold.defaultPercent))%") { actions.resetSimilarity() }
                    .foregroundStyle(LibraryColors.onBackground)
            }
        } header: {
            Text("Suggestions for similar photos")
        }
        .listRowBackground(LibraryColors.raised)
    }
}

extension SettingsScreen {
    /// How server photos are shown (previews in a format and quality, or originals) and the space they may take here.
    private var storageSection: some View {
        let options = settings.previewOptions
        let limit = Int(settings.previewCacheGigabytes)
        return Section {
            Toggle(isOn: Binding(get: { options.enabled }, set: actions.setPreviewsEnabled)) {
                ModeLabel(
                    title: "Previews from your server",
                    detail: options.enabled
                        ? "The server makes each photo at the size it is shown, in the format and quality below."
                        : "The original files are downloaded and kept in the space below. Videos are not downloaded."
                )
            }
            .tint(LibraryColors.accent)
            if options.enabled {
                VStack(alignment: .leading, spacing: 8) {
                    Picker("Format", selection: Binding(get: { options.format }, set: actions.setPreviewFormat)) {
                        ForEach(PreviewFormat.shownInSettings, id: \.self) { Text($0.label).tag($0) }
                    }
                    .foregroundStyle(LibraryColors.onBackground)
                    Text(options.format.hint).font(.footnote).foregroundStyle(LibraryColors.muted)
                }
                VStack(alignment: .leading, spacing: 8) {
                    Text("Quality").foregroundStyle(LibraryColors.onBackground)
                    Picker("Quality", selection: Binding(get: { options.quality }, set: actions.setPreviewQuality)) {
                        ForEach(PreviewQuality.shownInSettings, id: \.self) { Text($0.label).tag($0) }
                    }
                    .pickerStyle(.segmented)
                    .labelsHidden()
                }
            }
            VStack(alignment: .leading, spacing: 2) {
                Text("Kept on this phone").foregroundStyle(LibraryColors.onBackground)
                Group {
                    if let bytes = previewBytes {
                        Text("\(fileSize(bytes)) of \(limit) GB used. Photos from your server are fetched again when you scroll to them.")
                    } else {
                        Text("Counting…")
                    }
                }
                .font(.footnote)
                .foregroundStyle(LibraryColors.muted)
            }
            Picker("Up to", selection: Binding(get: { limit }, set: actions.setPreviewLimit)) {
                ForEach(SettingsViewModel.previewLimitOptions, id: \.self) { Text("\($0) GB").tag($0) }
            }
            .foregroundStyle(LibraryColors.onBackground)
            Button("Clear", action: actions.clearPreviews).foregroundStyle(LibraryColors.onBackground)
        } header: {
            Text("Storage on this phone")
        }
        .listRowBackground(LibraryColors.raised)
    }

    /// The Free up space row: what it would free, or what it is doing; it opens its own screen.
    private var freeUpSection: some View {
        Section {
            NavigationLink {
                freeUpScreen()
            } label: {
                VStack(alignment: .leading, spacing: 2) {
                    Text("Free up space").fontWeight(.semibold).foregroundStyle(LibraryColors.onBackground)
                    Text(freeUp.rowText).font(.footnote).monospacedDigit().foregroundStyle(LibraryColors.muted)
                }
            }
        } header: {
            Text("Free up space")
        }
        .listRowBackground(LibraryColors.raised)
    }

    /// "Show the phone badge" (off by default): the phone on photos that are on this phone and backed up.
    private var badgesSection: some View {
        Section {
            Toggle(isOn: Binding(get: { settings.showOnPhoneBadge }, set: actions.setShowOnPhoneBadge)) {
                ModeLabel(
                    title: "Show the phone badge",
                    detail: "Marks photos that are on this phone and backed up. Photos waiting to upload, uploading or failed always have a badge."
                )
            }
            .tint(LibraryColors.accent)
        } header: {
            Text("Badges")
        }
        .listRowBackground(LibraryColors.raised)
    }

    /// Search's row: how the server's reading goes, or photos per page and what this phone keeps.
    private var searchSection: some View {
        let kept = phoneSearch?.kept.count ?? 0
        let detail = indexing?.rowText
            ?? (kept > 0 ? String(localized: "\(Int(settings.searchPageSize)) per page · \(kept) kept on this phone")
                : String(localized: "\(Int(settings.searchPageSize)) per page"))
        return Section {
            NavigationLink {
                searchScreen()
            } label: {
                VStack(alignment: .leading, spacing: 2) {
                    Text("Search").fontWeight(.semibold).foregroundStyle(LibraryColors.onBackground)
                    Text(detail).font(.footnote).foregroundStyle(LibraryColors.muted)
                }
            }
        }
        .listRowBackground(LibraryColors.raised)
    }

    /// "Show private media" (off by default), and Hidden collections (where nudity is hidden too).
    private var privateMediaSection: some View {
        let privateMedia = settings.privateMedia
        return Section {
            Toggle(isOn: Binding(get: { privateMedia.show }, set: actions.setShowPrivateMedia)) {
                ModeLabel(
                    title: "Show private media",
                    detail: "When off, photos and videos you marked private are hidden everywhere, and not downloaded to this phone."
                )
            }
            .tint(LibraryColors.accent)
            NavigationLink {
                hiddenScreen()
            } label: {
                VStack(alignment: .leading, spacing: 6) {
                    ModeLabel(title: "Hidden collections", detail: "Nudity, WhatsApp and screenshots by app, hidden on all your devices")
                    if let syncProgress {
                        Text("Updating your library: \(Int(syncProgress.done).formatted()) of \(Int(syncProgress.total).formatted())")
                            .font(.footnote)
                            .monospacedDigit()
                            .foregroundStyle(LibraryColors.onBackground)
                        ProgressView(value: Double(syncProgress.done), total: Double(max(syncProgress.total, 1)))
                            .tint(LibraryColors.onBackground)
                    }
                }
            }
        } header: {
            Text("Private media")
        }
        .listRowBackground(LibraryColors.raised)
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

struct ModeLabel: View {
    let title: LocalizedStringKey
    let detail: LocalizedStringKey

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(title).foregroundStyle(LibraryColors.onBackground)
            Text(detail).font(.footnote).foregroundStyle(LibraryColors.muted)
        }
    }
}

/// "2.4 GB · 1,204 photos, 31 videos", what the user's originals take up on the server.
func serverUsageText(_ usage: StorageUsage?) -> String {
    guard let usage else { return String(localized: "Can’t reach the server right now") }
    let size = ByteCountFormatter.string(fromByteCount: usage.bytes, countStyle: .file)
    let photos = String(localized: "\(Int(usage.photos)) photos")
    if usage.videos == 0 { return "\(size) · \(photos)" }
    return "\(size) · \(photos), " + String(localized: "\(Int(usage.videos)) videos")
}
