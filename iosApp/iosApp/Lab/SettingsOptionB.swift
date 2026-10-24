#if DEBUG

import SwiftUI
import SharedLogic

/// Settings as one page, as now, regrouped by purpose: what the library shows, privacy, backup, storage, search.
/// Built from SettingsOptionParts.swift.
struct SettingsScreenB<FreeUp: View, Hidden: View>: View {
    let content: SettingsContent
    /// Settings › Search, opened from its row.
    var searchScreen: () -> AnyView = { AnyView(EmptyView()) }
    @ViewBuilder let freeUpScreen: () -> FreeUp
    @ViewBuilder let hiddenScreen: () -> Hidden

    var body: some View {
        SettingsPage(title: "Settings") {
            ShownSourcesSection(content: content)
            Section {
                PhoneBadgeToggle(content: content)
            }
            .listRowBackground(LibraryColors.raised)
            MomentsSections(content: content)

            Section {
                PrivateMediaToggle(content: content)
                NavigationLink { hiddenScreen() } label: { HiddenCollectionsLabel(syncProgress: content.syncProgress) }
            } header: {
                Text("Privacy")
            }
            .listRowBackground(LibraryColors.raised)

            BackupSections(content: content)
            StorageSections(content: content)
            Section {
                NavigationLink { freeUpScreen() } label: {
                    SettingsLabel(verbatim: String(localized: "Free up space"), detail: content.freeUp.rowText)
                }
                NavigationLink { searchScreen() } label: {
                    SettingsLabel(verbatim: String(localized: "Search"), detail: content.searchSummary)
                }
            }
            .listRowBackground(LibraryColors.raised)
        }
    }
}

#endif
