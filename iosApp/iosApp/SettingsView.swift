import SwiftUI
import SharedLogic

/// The user's choices, kept on this device. See docs/screens/settings.md.
struct SettingsView: View {
    @ObservedObject var settings: SettingsViewModel
    /// The slider's value while it is dragged; saved when let go.
    @State private var similarity: Double?
    /// The private media slider's value while it is dragged.
    @State private var privateThreshold: Double?

    var body: some View {
        let grouping = settings.state.grouping
        Form {
            storageSection
            Section {
                Picker("Grouping", selection: Binding(get: { grouping.mode }, set: settings.setGroupingMode)) {
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
                    Picker("New row after a break of", selection: Binding(get: { Int(grouping.gapHours) }, set: settings.setGapHours)) {
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
        }
        // Counted on open (and again when the limit changes or Clear is tapped).
        .task { settings.refreshStorage() }
        .scrollContentBackground(.hidden)
        .background(LibraryColors.background.ignoresSafeArea())
        .navigationTitle("Settings")
        .navigationBarTitleDisplayMode(.inline)
        .toolbarBackground(LibraryColors.background, for: .navigationBar)
    }
}

extension SettingsView {
    /// How alike photos must be for a "Look alike" suggestion.
    private var similaritySection: some View {
        let threshold = settings.state.similarity
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
                        settings.setSimilarityPercent(Int(similarity))
                        self.similarity = nil
                    }
                }
                .tint(LibraryColors.onBackground)
                Text("Higher: only photos that look nearly the same are suggested together. Lower: more photos in each suggestion, but less alike.")
                    .font(.footnote)
                    .foregroundStyle(LibraryColors.muted)
            }
            if !threshold.isDefault {
                Button("Reset to \(Int(threshold.defaultPercent))%") { settings.resetSimilarity() }
                    .foregroundStyle(LibraryColors.onBackground)
            }
        } header: {
            Text("Suggestions for similar photos")
        }
        .listRowBackground(LibraryColors.raised)
    }
}

extension SettingsView {
    /// How server photos are shown (previews in a format and quality, or originals) and the space they may take here.
    private var storageSection: some View {
        let options = settings.state.previewOptions
        let limit = Int(settings.state.previewCacheGigabytes)
        return Section {
            Toggle(isOn: Binding(get: { options.enabled }, set: settings.setPreviewsEnabled)) {
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
                    Picker("Format", selection: Binding(get: { options.format }, set: settings.setPreviewFormat)) {
                        ForEach(PreviewFormat.shownInSettings, id: \.self) { Text($0.label).tag($0) }
                    }
                    .foregroundStyle(LibraryColors.onBackground)
                    Text(options.format.hint).font(.footnote).foregroundStyle(LibraryColors.muted)
                }
                VStack(alignment: .leading, spacing: 8) {
                    Text("Quality").foregroundStyle(LibraryColors.onBackground)
                    Picker("Quality", selection: Binding(get: { options.quality }, set: settings.setPreviewQuality)) {
                        ForEach(PreviewQuality.shownInSettings, id: \.self) { Text($0.label).tag($0) }
                    }
                    .pickerStyle(.segmented)
                    .labelsHidden()
                }
            }
            VStack(alignment: .leading, spacing: 2) {
                Text("Kept on this phone").foregroundStyle(LibraryColors.onBackground)
                Group {
                    if let bytes = settings.previewBytes {
                        Text("\(fileSize(bytes)) of \(limit) GB used. Photos from your server are fetched again when you scroll to them.")
                    } else {
                        Text("Counting…")
                    }
                }
                .font(.footnote)
                .foregroundStyle(LibraryColors.muted)
            }
            Picker("Up to", selection: Binding(get: { limit }, set: settings.setPreviewLimit)) {
                ForEach(SettingsViewModel.previewLimitOptions, id: \.self) { Text("\($0) GB").tag($0) }
            }
            .foregroundStyle(LibraryColors.onBackground)
            Button("Clear", action: settings.clearPreviews).foregroundStyle(LibraryColors.onBackground)
        } header: {
            Text("Storage on this phone")
        }
        .listRowBackground(LibraryColors.raised)
    }

    /// "Show private media" (off by default) and, while off, the score from which photos are hidden.
    private var privateMediaSection: some View {
        let privateMedia = settings.state.privateMedia
        let shown = Int(privateThreshold ?? Double(privateMedia.thresholdPercent))
        return Section {
            Toggle(isOn: Binding(get: { privateMedia.show }, set: settings.setShowPrivateMedia)) {
                ModeLabel(
                    title: "Show private media",
                    detail: "When off, photos and videos the server found nudity in are hidden everywhere, and not downloaded to this phone."
                )
            }
            .tint(LibraryColors.accent)
            if !privateMedia.show {
                VStack(alignment: .leading, spacing: 8) {
                    HStack {
                        Text("Hide from").foregroundStyle(LibraryColors.onBackground)
                        Spacer()
                        Text("\(shown)%").monospacedDigit().foregroundStyle(LibraryColors.onBackground)
                    }
                    Slider(
                        value: Binding(get: { privateThreshold ?? Double(privateMedia.thresholdPercent) }, set: { privateThreshold = $0 }),
                        in: SettingsViewModel.privateRange,
                        step: 5
                    ) { editing in
                        if !editing, let privateThreshold {
                            settings.setPrivateThresholdPercent(Int(privateThreshold))
                            self.privateThreshold = nil
                        }
                    }
                    .tint(LibraryColors.onBackground)
                    Text("How sure the server must be that a photo shows nudity to hide it. Lower hides more, including some that show none.")
                        .font(.footnote)
                        .foregroundStyle(LibraryColors.muted)
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
