import SwiftUI
import SharedLogic

/// The user's choices, kept on this device. See docs/screens/settings.md.
struct SettingsView: View {
    @ObservedObject var settings: SettingsViewModel

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
                Text("Unsorted photos on the Journal")
            }
            .listRowBackground(LibraryColors.raised)
        }
        .scrollContentBackground(.hidden)
        .background(LibraryColors.background.ignoresSafeArea())
        .navigationTitle("Settings")
        .navigationBarTitleDisplayMode(.inline)
        .toolbarBackground(LibraryColors.background, for: .navigationBar)
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
