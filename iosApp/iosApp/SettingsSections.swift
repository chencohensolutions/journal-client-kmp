import SwiftUI
import SharedLogic

// Settings' pages and rows (SettingsView.swift draws the page of groups from them): what each shows, and the one-line
// summary its row on the first page shows. See docs/screens/settings.md.

/// What Settings draws: the view models' states, or the UI lab's made-up ones.
struct SettingsContent {
    let settings: SettingsState
    let backup: BackupState
    /// Photos of the chosen sources not on the server yet.
    let notBackedUp: Int
    let sourceCounts: [MediaSource: KotlinInt]
    /// What previews and downloaded originals of server photos take here; nil while counting.
    let previewBytes: Int64?
    /// What the user's originals take up on the server; nil until it answered.
    let serverUsage: StorageUsage?
    let freeUp: FreeUpState
    let actions: SettingsActions
    var syncProgress: SyncProgress? = nil
    var phoneSearch: PhoneSearchState? = nil
    var indexing: ServerIndexingState? = nil
}

// MARK: - Row summaries

extension SettingsContent {
    /// "Showing Camera, Other, Petcube +2", the most photos first.
    var shownSummary: String {
        let shownSources = settings.shownSources
        let shown = shownSources.choices(counts: sourceCounts).filter { shownSources.isShown(source: $0) }.map(\.title)
        if shown.isEmpty { return String(localized: "Nothing shown") }
        let first = shown.prefix(3).joined(separator: ", ")
        return String(localized: "Showing \(shown.count > 3 ? "\(first) +\(shown.count - 3)" : first)")
    }

    /// "By day · similar at 60%", "New row after 12 hours · similar at 80%".
    var momentsSummary: String {
        let grouping = settings.grouping
        let rows = grouping.mode == .byGap
            ? String(localized: "New row after \(Int(grouping.gapHours)) hours")
            : String(localized: "By day")
        return rows + " · " + String(localized: "similar at \(Int(settings.similarity.percent))%")
    }

    var backupSummary: String { backup.statusText(waiting: notBackedUp) }

    /// "1.8 GB of 10 GB · WebP, High", or "· Originals" when previews are off.
    var storageSummary: String {
        let limit = Int(settings.previewCacheGigabytes)
        let used = previewBytes.map { String(localized: "\(fileSize($0)) of \(limit) GB") } ?? String(localized: "Counting…")
        let options = settings.previewOptions
        let images = options.enabled ? "\(options.format.label), \(options.quality.label)" : String(localized: "Originals")
        return "\(used) · \(images)"
    }

    var searchSummary: String {
        let kept = phoneSearch?.kept.count ?? 0
        return indexing?.rowText
            ?? (kept > 0 ? String(localized: "\(Int(settings.searchPageSize)) per page · \(kept) kept on this phone")
                : String(localized: "\(Int(settings.searchPageSize)) per page"))
    }
}

// MARK: - Library

/// Show in the app: a pill per source with its count, the most photos first (the same as the Moments filter bar and
/// Explore's "Showing" sheet), "Say what's hidden" (the "+6 WhatsApp" chips in Moments) and "Filter bar in Moments".
/// For this phone only; Hidden collections hides on all devices.
struct ShownSourcesSection: View {
    let content: SettingsContent

    var body: some View {
        let shown = content.settings.shownSources
        let actions = content.actions
        Section {
            SourcePills(
                sources: shown.choices(counts: content.sourceCounts), counts: content.sourceCounts, isOn: { shown.isShown(source: $0) },
                toggle: actions.toggleFeedSource
            )
        } header: {
            Text("Show in the app")
        }
        .listRowBackground(LibraryColors.background)
        Section {
            Toggle(isOn: Binding(get: { shown.sayWhatsHidden }, set: actions.setSayWhatsHidden)) {
                SettingsLabel("Say what's hidden", detail: "“+6 WhatsApp” on a day, tap to see them")
            }
            .tint(LibraryColors.accent)
            Toggle(isOn: Binding(get: { shown.showFilterBar }, set: actions.setShowFilterBar)) {
                SettingsLabel("Filter bar in Moments", detail: "The row of sources above the feed. When off, choose them here.")
            }
            .tint(LibraryColors.accent)
        } footer: {
            VStack(alignment: .leading, spacing: 6) {
                Text("On this phone only. Applies to Moments, Explore and Tags.")
                Text("Photos you wrote about always show in the storyline. Hiding never deletes anything and doesn't change what is backed up.")
            }
        }
        .listRowBackground(LibraryColors.raised)
    }
}

/// "Show the phone badge" (off by default), a row for a group to place.
struct PhoneBadgeToggle: View {
    let content: SettingsContent

    var body: some View {
        Toggle(isOn: Binding(get: { content.settings.showOnPhoneBadge }, set: content.actions.setShowOnPhoneBadge)) {
            SettingsLabel(
                "Show the phone badge",
                detail: "Marks photos that are on this phone and backed up. Photos waiting to upload, uploading or failed always have a badge."
            )
        }
        .tint(LibraryColors.accent)
    }
}

/// Moments: how its rows are made (the break's length right under "By breaks"), and how alike a set's photos are.
struct MomentsSections: View {
    let content: SettingsContent
    /// The slider's value while it is dragged; saved when let go.
    @State private var similarity: Double?

    var body: some View {
        let grouping = content.settings.grouping
        let actions = content.actions
        Section {
            Picker("Rows", selection: Binding(get: { grouping.mode }, set: actions.setGroupingMode)) {
                SettingsLabel("By day", detail: "One row for each calendar day.").tag(GroupingMode.byDay)
                SettingsLabel(
                    "By breaks between photos",
                    detail: "A new row starts after a break without photos, so an evening that runs past midnight stays in one row."
                )
                .tag(GroupingMode.byGap)
            }
            .pickerStyle(.inline)
            .labelsHidden()
            if grouping.mode == .byGap {
                Picker("New row after a break of", selection: Binding(get: { Int(grouping.gapHours) }, set: actions.setGapHours)) {
                    ForEach(SettingsViewModel.gapHoursOptions, id: \.self) { Text("\($0) hours").tag($0) }
                }
                .foregroundStyle(LibraryColors.onBackground)
            }
        } header: {
            Text("Rows in Moments")
        }
        .listRowBackground(LibraryColors.raised)

        let threshold = content.settings.similarity
        let shown = Int(similarity ?? Double(threshold.percent))
        Section {
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
                Text("Higher: only photos that look nearly the same are set together. Lower: bigger sets, less alike.")
                    .font(.footnote)
                    .foregroundStyle(LibraryColors.muted)
            }
            if !threshold.isDefault {
                Button("Reset to \(Int(threshold.defaultPercent))%") { actions.resetSimilarity() }
                    .foregroundStyle(LibraryColors.onBackground)
            }
        } header: {
            Text("Sets of similar photos")
        }
        .listRowBackground(LibraryColors.raised)
    }
}

// MARK: - Privacy

/// "Show private media" (off by default), a row for a group to place.
struct PrivateMediaToggle: View {
    let content: SettingsContent

    var body: some View {
        Toggle(isOn: Binding(get: { content.settings.privateMedia.show }, set: content.actions.setShowPrivateMedia)) {
            SettingsLabel(
                "Show private media",
                detail: "Photos and videos you marked private. Off: hidden on this phone, and not downloaded."
            )
        }
        .tint(LibraryColors.accent)
    }
}

/// The Hidden collections row, with the library's sync after a change there.
struct HiddenCollectionsLabel: View {
    let syncProgress: SyncProgress?

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack(spacing: 6) {
                Text("Hidden collections").foregroundStyle(LibraryColors.onBackground)
                Image(systemName: "lock.fill").font(.caption).foregroundStyle(LibraryColors.muted)
            }
            Text("Nudity, WhatsApp and screenshots by app, hidden on all your devices")
                .font(.footnote)
                .foregroundStyle(LibraryColors.muted)
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
}

// MARK: - Backup and storage

/// Storage on this phone: the space photos from the server may take here (with Clear), then how they look (previews in
/// a format and quality, or the originals).
struct StorageSections: View {
    let content: SettingsContent

    var body: some View {
        let settings = content.settings
        let actions = content.actions
        let options = settings.previewOptions
        let limit = Int(settings.previewCacheGigabytes)
        Section {
            VStack(alignment: .leading, spacing: 2) {
                Text("Used").foregroundStyle(LibraryColors.onBackground)
                Group {
                    if let bytes = content.previewBytes {
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
            Text("Kept on this phone")
        }
        .listRowBackground(LibraryColors.raised)
        Section {
            Toggle(isOn: Binding(get: { options.enabled }, set: actions.setPreviewsEnabled)) {
                SettingsLabel(
                    "Previews from your server",
                    detail: options.enabled
                        ? "The server makes each photo at the size it is shown, in the format and quality below."
                        : "The original files are downloaded and kept in the space above. Videos are not downloaded."
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
        } header: {
            Text("Image quality")
        }
        .listRowBackground(LibraryColors.raised)
    }
}

// MARK: - Shared

/// A title with a line under it.
struct SettingsLabel: View {
    private let title: Text
    private let detail: Text

    init(_ title: LocalizedStringKey, detail: LocalizedStringKey) {
        self.title = Text(title)
        self.detail = Text(detail)
    }

    init(verbatim title: String, detail: String) {
        self.title = Text(verbatim: title)
        self.detail = Text(verbatim: detail)
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            title.foregroundStyle(LibraryColors.onBackground)
            detail.font(.footnote).monospacedDigit().foregroundStyle(LibraryColors.muted)
        }
    }
}

/// A page of Settings: a form on the library's background, titled.
struct SettingsPage<Content: View>: View {
    let title: LocalizedStringKey
    @ViewBuilder let content: () -> Content

    var body: some View {
        Form { content() }
            .scrollContentBackground(.hidden)
            .background(LibraryColors.background.ignoresSafeArea())
            .navigationTitle(title)
            .navigationBarTitleDisplayMode(.inline)
            .toolbarBackground(LibraryColors.background, for: .navigationBar)
    }
}

/// "2.4 GB · 1,204 photos, 31 videos", what the user's originals take up on the server.
func serverUsageLine(_ usage: StorageUsage?) -> String {
    guard let usage else { return String(localized: "Can’t reach the server right now") }
    let size = ByteCountFormatter.string(fromByteCount: usage.bytes, countStyle: .file)
    let photos = String(localized: "\(Int(usage.photos)) photos")
    if usage.videos == 0 { return "\(size) · \(photos)" }
    return "\(size) · \(photos), " + String(localized: "\(Int(usage.videos)) videos")
}
