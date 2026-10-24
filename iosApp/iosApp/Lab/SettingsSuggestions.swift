#if DEBUG

import SwiftUI
import SharedLogic

// Suggestions for Settings: the same settings regrouped by purpose (docs/screens/settings.md). UI only, on LabData's
// Settings states; every switch does nothing. Search's row opens nothing, as in `current`.

private let data = LabData.shared

/// Settings' states as the lab's `current` shows them; `offline`: no answer from the server yet.
func labSettingsContent(offline: Bool = false) -> SettingsContent {
    SettingsContent(
        settings: data.settings, backup: data.backup, notBackedUp: Int(data.notBackedUp), sourceCounts: data.sourceCounts,
        previewBytes: data.previewBytes, serverUsage: offline ? nil : data.serverUsage, freeUp: data.freeUp, actions: .lab,
        syncProgress: offline ? nil : data.syncProgress
    )
}

/// Option a: a short page of groups, each row opening its page.
func labSettingsA(offline: Bool = false) -> some View {
    SettingsScreenA(content: labSettingsContent(offline: offline)) {
        FreeUpScreen(state: data.freeUp, actions: .lab)
    } hiddenScreen: {
        HiddenScreen(state: data.hiddenCollections, actions: .lab)
    }
}

/// Option b: one page, regrouped.
func labSettingsB() -> some View {
    SettingsScreenB(content: labSettingsContent()) {
        FreeUpScreen(state: data.freeUp, actions: .lab)
    } hiddenScreen: {
        HiddenScreen(state: data.hiddenCollections, actions: .lab)
    }
}

#endif
