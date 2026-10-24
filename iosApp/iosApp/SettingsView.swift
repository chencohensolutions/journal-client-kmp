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
            backupSections
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
    /// Show in the app: a switch per source (the same as the Moments filter bar and Explore's "Showing" sheet), and
    /// "Say what's hidden" (the "+6 WhatsApp" chips in Moments).
    private var shownSourcesSection: some View {
        let shown = settings.shownSources
        return Section {
            ForEach(MediaSource.entries, id: \.self) { source in
                Toggle(isOn: Binding(get: { shown.isShown(source: source) }, set: { _ in actions.toggleFeedSource(source) })) {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(source.title).foregroundStyle(LibraryColors.onBackground)
                        Text(verbatim: sourceDetail(source, count: sourceCounts[source]?.intValue ?? 0))
                            .font(.footnote)
                            .monospacedDigit()
                            .foregroundStyle(LibraryColors.muted)
                    }
                }
                .tint(LibraryColors.accent)
            }
            Toggle(isOn: Binding(get: { shown.sayWhatsHidden }, set: actions.setSayWhatsHidden)) {
                ModeLabel(title: "Say what's hidden", detail: "“+6 WhatsApp” on a day, tap to see them")
            }
            .tint(LibraryColors.accent)
        } header: {
            Text("Show in the app")
        } footer: {
            VStack(alignment: .leading, spacing: 6) {
                Text("Applies to Moments, All photos, Explore and Tags.")
                Text("Photos you wrote about always show in the storyline. Hiding never deletes anything and doesn't change what is backed up.")
            }
        }
        .listRowBackground(LibraryColors.raised)
    }

    /// "48,210 photos", "18,458 photos and videos", "Screenshots and screen recordings · 2,388".
    private func sourceDetail(_ source: MediaSource, count: Int) -> String {
        switch source {
        case .whatsapp: return String(localized: "\(count) photos and videos")
        case .screenshots: return String(localized: "Screenshots and screen recordings · \(count)")
        case .other: return String(localized: "Downloads, other apps · \(count)")
        default: return String(localized: "\(count) photos")
        }
    }

    /// Backup: on or off with its status, then what uploads, when, and in which order. "On your server" (storage
    /// used there) and "Only on these Wi-Fi networks" are Android only for now; see docs/screens/settings.md.
    @ViewBuilder private var backupSections: some View {
        let state = backup.settings
        Section {
            Toggle(isOn: Binding(get: { state.enabled }, set: actions.setBackupEnabled)) {
                ModeLabel(
                    title: "Back up to your server",
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
                Text(serverUsageText(serverUsage))
                    .font(.footnote)
                    .monospacedDigit()
                    .foregroundStyle(LibraryColors.muted)
            }
        } header: {
            Text("Backup")
        }
        .listRowBackground(LibraryColors.raised)
        if state.enabled {
            Section {
                ForEach(MediaSource.entries, id: \.self) { source in
                    Toggle(source.title, isOn: Binding(get: { state.sources.contains(source) }, set: { _ in actions.toggleBackupSource(source) }))
                        .tint(LibraryColors.accent)
                }
            } header: {
                Text("What to back up")
            }
            .listRowBackground(LibraryColors.raised)
            Section {
                Toggle(isOn: Binding(get: { state.wifiOnly }, set: actions.setWifiOnly)) {
                    ModeLabel(title: "Only on Wi-Fi", detail: "Never on mobile data.")
                }
                .tint(LibraryColors.accent)
                Toggle("Only while charging", isOn: Binding(get: { state.chargingOnly }, set: actions.setChargingOnly))
                    .tint(LibraryColors.accent)
            } header: {
                Text("When")
            }
            .listRowBackground(LibraryColors.raised)
            Section {
                Picker("Order", selection: Binding(get: { state.order }, set: actions.setOrder)) {
                    ModeLabel(title: "Oldest first", detail: "Starts with your earliest photos.").tag(UploadOrder.oldestFirst)
                    ModeLabel(title: "Newest first", detail: "Your latest photos are safe soonest.").tag(UploadOrder.newestFirst)
                }
                .pickerStyle(.inline)
                .labelsHidden()
            } header: {
                Text("Order")
            }
            .listRowBackground(LibraryColors.raised)
        }
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

private struct ModeLabel: View {
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
private func serverUsageText(_ usage: StorageUsage?) -> String {
    guard let usage else { return String(localized: "Can’t reach the server right now") }
    let size = ByteCountFormatter.string(fromByteCount: usage.bytes, countStyle: .file)
    let photos = String(localized: "\(Int(usage.photos)) photos")
    if usage.videos == 0 { return "\(size) · \(photos)" }
    return "\(size) · \(photos), " + String(localized: "\(Int(usage.videos)) videos")
}
