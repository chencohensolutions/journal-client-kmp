import SwiftUI
import SharedLogic

// Settings › Show in the app (a status page), Moments' "What Moments shows", and Advanced › Loading Moments, as on
// Android's ShowInAppPage.kt, SettingsScreen.kt (MomentsPage) and LoadingMomentsScreen.kt. See docs/screens/settings.md.

// MARK: - Show in the app

/// Settings › Show in the app as a status page (option c3 of the UI lab, Oct 27, 2026), with no switches: "87,343 files"
/// with "15 not backed up ›" (or "All backed up"), a card of photo and video kinds and one of sound kinds, each with its
/// total and, in orange, what of it isn't on the server yet (opening Backup). Kinds Private settings hides on every
/// device aren't named, only counted, with "Private settings ›". Hiding on this phone is the Moments filter bar's.
struct ShowInAppPage<Backup: View, Private: View>: View {
    let status: KindsStatus
    @ViewBuilder let backupScreen: () -> Backup
    @ViewBuilder let privateScreen: () -> Private

    var body: some View {
        SettingsPage(title: "Show in the app") {
            Section {
                VStack(alignment: .leading, spacing: 4) {
                    Text("\(Int(status.files).formatted()) files")
                        .font(.title2.bold())
                        .foregroundStyle(LibraryColors.onBackground)
                    if status.notBackedUp > 0 {
                        NotBackedUpLink(count: Int(status.notBackedUp), font: .subheadline, destination: backupScreen)
                    } else {
                        Text("All backed up").font(.subheadline).foregroundStyle(LibraryColors.muted)
                    }
                }
                .listRowInsets(EdgeInsets(top: 4, leading: 16, bottom: 4, trailing: 16))
            }
            .listRowBackground(Color.clear)

            if !status.kinds.isEmpty {
                Section {
                    ForEach(status.kinds, id: \.source.id) { kind in
                        KindRow(
                            name: kind.source.title, symbol: kind.source.symbol, files: Int(kind.files), notBackedUp: Int(kind.notBackedUp),
                            backupScreen: backupScreen
                        )
                    }
                } header: {
                    Text("Photos and videos")
                }
                .listRowBackground(LibraryColors.raised)
            }
            if !status.sounds.isEmpty {
                Section {
                    ForEach(status.sounds, id: \.kind) { sound in
                        KindRow(
                            name: sound.kind.title, symbol: sound.kind.symbol, files: Int(sound.files), notBackedUp: Int(sound.notBackedUp),
                            backupScreen: backupScreen
                        )
                    }
                } header: {
                    Text("Sound")
                }
                .listRowBackground(LibraryColors.raised)
            }
            Section {
                if let hidden = status.hiddenEverywhere?.intValue, hidden > 0 {
                    NavigationLink { privateScreen() } label: {
                        HStack(alignment: .firstTextBaseline, spacing: 10) {
                            Image(systemName: "lock.fill").font(.footnote).foregroundStyle(LibraryColors.muted)
                            (Text("\(hidden) kinds hidden on all your devices") + Text(verbatim: " · ")
                                + Text("Private settings").bold().foregroundColor(LibraryColors.onBackground) + Text(verbatim: " ›"))
                                .font(.footnote)
                                .foregroundStyle(LibraryColors.muted)
                        }
                    }
                }
            } footer: {
                Text("To hide a kind on this phone, or show it again, use the filter bar in Moments. This phone remembers it.")
            }
            .listRowBackground(Color.clear)
        }
    }
}

/// A kind's line: its icon and name, its total at the end, and the orange "not backed up" link under the name.
private struct KindRow<Backup: View>: View {
    let name: String
    let symbol: String
    let files: Int
    let notBackedUp: Int
    @ViewBuilder let backupScreen: () -> Backup

    var body: some View {
        HStack(spacing: 14) {
            Image(systemName: symbol).foregroundStyle(LibraryColors.muted).frame(width: 22)
            VStack(alignment: .leading, spacing: 2) {
                Text(verbatim: name).foregroundStyle(LibraryColors.onBackground)
                if notBackedUp > 0 { NotBackedUpLink(count: notBackedUp, font: .caption, destination: backupScreen) }
            }
            Spacer(minLength: 12)
            Text(verbatim: files.formatted())
                .font(.body.weight(.semibold))
                .monospacedDigit()
                .foregroundStyle(LibraryColors.onBackground)
                .frame(minWidth: 72, alignment: .trailing)
        }
    }
}

/// "☁ 12 not backed up ›" in orange; opens Backup.
private struct NotBackedUpLink<Destination: View>: View {
    let count: Int
    let font: Font
    @ViewBuilder let destination: () -> Destination

    var body: some View {
        NavigationLink { destination() } label: {
            Label {
                Text("\(count.formatted()) not backed up")
            } icon: {
                Image(systemName: "icloud.and.arrow.up")
            }
            .labelStyle(.titleAndIcon)
            .font(font.weight(.semibold))
            .foregroundStyle(LibraryColors.warning)
        }
        // A link inside a row: only its own text opens Backup, not the whole row.
        .buttonStyle(.borderless)
        .fixedSize()
    }
}

extension KindsStatus {
    /// Show in the app's row: "87,343 files · 15 not backed up", or "… · All backed up".
    var rowText: String {
        let files = String(localized: "\(Int(self.files).formatted()) files")
        let backup = notBackedUp > 0 ? String(localized: "\(Int(notBackedUp).formatted()) not backed up") : String(localized: "All backed up")
        return "\(files) · \(backup)"
    }

    static let empty = KindsStatus(kinds: [], sounds: [], hiddenEverywhere: nil)
}

extension AudioKind {
    /// Calls, Voice recordings, Voice messages.
    var title: String {
        switch self {
        case .call: return String(localized: "Calls")
        case .voiceMessage: return String(localized: "Voice messages")
        default: return String(localized: "Voice recordings")
        }
    }
}

// MARK: - Moments

/// Settings › Moments › What Moments shows: the filter bar and "Say what's hidden" (moved from Show in the app when it
/// became a status page, Oct 27, 2026). On this phone only.
struct MomentsFilterSection: View {
    let content: SettingsContent

    var body: some View {
        let shown = content.settings.shownSources
        let actions = content.actions
        Section {
            Toggle(isOn: Binding(get: { shown.showFilterBar }, set: actions.setShowFilterBar)) {
                SettingsLabel("Filter bar in Moments", detail: "The row of sources above the feed. When off, choose them here.")
            }
            .tint(LibraryColors.accent)
            Toggle(isOn: Binding(get: { shown.sayWhatsHidden }, set: actions.setSayWhatsHidden)) {
                SettingsLabel("Say what's hidden", detail: "“+6 WhatsApp” on a day, tap to see them")
            }
            .tint(LibraryColors.accent)
        } header: {
            Text("What Moments shows")
        } footer: {
            Text("Photos you wrote about always show in the storyline. Hiding never deletes anything and doesn't change what is backed up.")
        }
        .listRowBackground(LibraryColors.raised)
    }
}

// MARK: - Advanced

extension MomentsPaging {
    /// "30 rows at a time · 40 ahead".
    var rowText: String { String(localized: "\(Int(rowsPerPage)) rows at a time · \(Int(loadAhead)) ahead") }
}

/// Settings › Loading Moments (UI lab round ux/settings/2026-10-26): how many rows Moments reads at a time and how near
/// the end it reads the next, each with the default marked, and a reset once either is changed.
struct LoadingMomentsPage: View {
    let paging: MomentsPaging
    let actions: SettingsActions

    var body: some View {
        let companion = MomentsPaging.companion
        SettingsPage(title: "Loading while scrolling") {
            Section {
                PagingChoice(
                    title: "Rows loaded at a time", detail: "More: fewer pauses when scrolling far, more memory.",
                    options: companion.ROWS_PER_PAGE_OPTIONS.map(\.intValue), value: Int(paging.rowsPerPage),
                    defaultValue: Int(companion.DEFAULT_ROWS_PER_PAGE), pick: actions.setMomentsRowsPerPage
                )
                PagingChoice(
                    title: "Load ahead",
                    detail: "How many rows before the end of what is loaded the next rows start loading, and how many rows ahead search loads pictures. More: smoother fast scrolling.",
                    options: companion.LOAD_AHEAD_OPTIONS.map(\.intValue), value: Int(paging.loadAhead),
                    defaultValue: Int(companion.DEFAULT_LOAD_AHEAD), pick: actions.setMomentsLoadAhead
                )
            } footer: {
                Text("Only on this phone. Applies as you scroll, in Moments and in search.")
            }
            .listRowBackground(LibraryColors.raised)
            if !paging.isDefault {
                Section {
                    Button("Reset to \(Int(companion.DEFAULT_ROWS_PER_PAGE)) and \(Int(companion.DEFAULT_LOAD_AHEAD))", action: actions.resetMomentsPaging)
                        .foregroundStyle(LibraryColors.onBackground)
                }
                .listRowBackground(LibraryColors.raised)
            }
        }
    }
}

/// A title, what it trades, and a chip per value; the default's chip says so.
private struct PagingChoice: View {
    let title: LocalizedStringKey
    let detail: LocalizedStringKey
    let options: [Int]
    let value: Int
    let defaultValue: Int
    let pick: (Int) -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            SettingsLabel(title, detail: detail)
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 8) {
                    ForEach(options, id: \.self) { n in
                        let on = n == value
                        Button { pick(n) } label: {
                            Group {
                                if n == defaultValue { Text("\(n) (default)") } else { Text(verbatim: "\(n)") }
                            }
                            .font(.system(size: 14, weight: .semibold))
                            .padding(.horizontal, 14)
                            .padding(.vertical, 7)
                            .foregroundStyle(on ? LibraryColors.onInverse : LibraryColors.onBackground)
                            .background(on ? LibraryColors.inverse : Color.clear, in: Capsule())
                            .overlay(Capsule().stroke(LibraryColors.muted.opacity(on ? 0 : 0.5)))
                        }
                        .buttonStyle(.plain)
                    }
                }
            }
        }
        .padding(.vertical, 4)
    }
}
