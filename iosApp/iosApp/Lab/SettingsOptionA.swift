#if DEBUG

import SwiftUI
import SharedLogic

/// Settings as a short page of groups (Library, Privacy, Backup and storage, Search), each row with a line that says
/// how it stands and opening a page of its own. The switches used most stay on the page. Built from
/// SettingsOptionParts.swift.
struct SettingsScreenA<FreeUp: View, Hidden: View>: View {
    let content: SettingsContent
    /// Settings › Search, opened from its row.
    var searchScreen: () -> AnyView = { AnyView(EmptyView()) }
    @ViewBuilder let freeUpScreen: () -> FreeUp
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

#endif
