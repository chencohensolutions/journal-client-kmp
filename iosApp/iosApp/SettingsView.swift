import SwiftUI
import SharedLogic

/// The user's choices, kept on this device. See docs/screens/settings.md.
struct SettingsView: View {
    @ObservedObject var settings: SettingsViewModel
    /// The slider's value while it is dragged; saved when let go.
    @State private var similarity: Double?

    var body: some View {
        let grouping = settings.state.grouping
        Form {
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
