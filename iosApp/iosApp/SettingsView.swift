import SwiftUI
import SharedLogic

/// The user's choices, kept on this device. See docs/screens/settings.md.
struct SettingsView: View {
    @ObservedObject var settings: SettingsViewModel
    @ObservedObject var backup: BackupViewModel
    /// Photos of the chosen sources not on the server yet.
    let notBackedUp: Int
    /// The slider's value while it is dragged; saved when let go.
    @State private var similarity: Double?

    var body: some View {
        let grouping = settings.state.grouping
        Form {
            backupSections
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
        }
        .scrollContentBackground(.hidden)
        .background(LibraryColors.background.ignoresSafeArea())
        .navigationTitle("Settings")
        .navigationBarTitleDisplayMode(.inline)
        .toolbarBackground(LibraryColors.background, for: .navigationBar)
    }
}

extension SettingsView {
    /// Backup: on or off with its status, then what uploads, when, and in which order. "On your server" (storage
    /// used there) and "Only on these Wi-Fi networks" are Android only for now; see docs/screens/settings.md.
    @ViewBuilder private var backupSections: some View {
        let state = backup.state.settings
        Section {
            Toggle(isOn: Binding(get: { state.enabled }, set: backup.setEnabled)) {
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
                if state.enabled && backup.running == nil {
                    Button("Back up now", action: backup.backUpNow).foregroundStyle(LibraryColors.onBackground)
                }
            }
        } header: {
            Text("Backup")
        }
        .listRowBackground(LibraryColors.raised)
        if state.enabled {
            Section {
                ForEach(MediaSource.entries, id: \.self) { source in
                    Toggle(source.title, isOn: Binding(get: { state.sources.contains(source) }, set: { _ in backup.toggleSource(source) }))
                        .tint(LibraryColors.accent)
                }
            } header: {
                Text("What to back up")
            }
            .listRowBackground(LibraryColors.raised)
            Section {
                Toggle(isOn: Binding(get: { state.wifiOnly }, set: backup.setWifiOnly)) {
                    ModeLabel(title: "Only on Wi-Fi", detail: "Never on mobile data.")
                }
                .tint(LibraryColors.accent)
                Toggle("Only while charging", isOn: Binding(get: { state.chargingOnly }, set: backup.setChargingOnly))
                    .tint(LibraryColors.accent)
            } header: {
                Text("When")
            }
            .listRowBackground(LibraryColors.raised)
            Section {
                Picker("Order", selection: Binding(get: { state.order }, set: backup.setOrder)) {
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
