import Foundation
import SharedLogic

/// Bridges the shared Kotlin `FreeUpController` ("Free up space", run by `FreeUpService`) into SwiftUI.
final class FreeUpViewModel: ObservableObject {
    @Published private(set) var state: FreeUpState
    /// iOS's prompt for the waiting files is open.
    @Published private(set) var confirming = false

    private let service = FreeUpService.shared
    private var controller: FreeUpController { service.controller }
    private var observation: ObservationHandle?

    init() {
        state = service.controller.currentState
        // The controller delivers updates on the main thread.
        observation = service.controller.observe { [weak self] newState in
            self?.state = newState
        }
    }

    deinit {
        observation?.cancel()
    }

    var running: Bool { state.activity is FreeUpActivityRunning }

    func refreshPlan() { controller.refreshPlan() }
    func setRule(_ rule: FreeUpRule) { controller.setRule(rule: rule) }
    func toggleSource(_ source: MediaSource) { controller.toggleSource(source: source) }

    /// "Free up 14 GB": checks every file on the phone and on the server; those that pass wait for Move to trash.
    func freeUp() {
        service.askNotificationPermission()
        controller.start()
    }

    /// Move to trash: one iOS prompt for every checked file still as it was, then nothing waits any more (either answer).
    @MainActor
    func moveWaitingToTrash(refreshLibrary: @escaping () -> Void) async {
        guard !confirming else { return }
        confirming = true
        let items: [MediaItem] = await awaiting { done in self.service.freeUp.waitingItems(onResult: done) }
        if !items.isEmpty {
            _ = await PhotoRemoval.moveToRecentlyDeleted(checked: items)
            refreshLibrary()
        }
        controller.confirmationDone()
        service.dismissNotification()
        confirming = false
    }

    /// The Settings row ([FreeUpState.summary]): what Free up space would free, or what it is doing.
    var rowText: String {
        switch state.summary {
        case let checking as FreeUpSummaryChecking:
            return String(localized: "Checking \(Int(checking.number)) of \(Int(checking.total))")
        case let waiting as FreeUpSummaryWaiting:
            return String(localized: "\(Int(waiting.count)) files (\(formatBytes(waiting.bytes))) checked, waiting for you")
        case is FreeUpSummaryNothing:
            return String(localized: "Nothing to free up right now")
        case let canFree as FreeUpSummaryCanFree:
            return String(localized: "\(formatBytes(canFree.bytes)) can be freed")
        default:
            return String(localized: "Counting…")
        }
    }

    /// [FreeUpState.statusLines] as text: the run going on, why it stopped, or what the last one did; and what waits.
    var statusText: String? {
        let lines = state.statusLines.map { line -> String in
            switch line {
            case let checking as FreeUpLineChecking:
                let text = String(localized: "Checking \(Int(checking.number)) of \(Int(checking.total))")
                return checking.fileName.map { "\(text) · \($0)" } ?? text
            case let stopped as FreeUpLineStopped:
                return String(localized: "Stopped: \(stopped.message)")
            case let removed as FreeUpLineRemoved:
                return String(localized: "\(Int(removed.count)) files (\(formatBytes(removed.bytes))) moved to the trash")
            case let kept as FreeUpLineKept:
                let count = Int(kept.count)
                switch kept.reason {
                case .changedOnPhone: return String(localized: "\(count) changed on this phone: backed up again")
                case .missingOnServer: return String(localized: "\(count) not on your server: backed up again")
                default: return String(localized: "\(count) damaged on your server: kept on this phone")
                }
            case let waiting as FreeUpLineWaiting:
                return String(localized: "\(Int(waiting.count)) files (\(formatBytes(waiting.bytes))) checked, waiting for you")
            default:
                return ""
            }
        }
        return lines.isEmpty ? nil : lines.joined(separator: "\n")
    }
}
