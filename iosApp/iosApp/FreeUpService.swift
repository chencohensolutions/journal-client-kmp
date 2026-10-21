import SharedLogic
import UIKit
import UserNotifications

/// When "Free up space" runs on iOS. Android runs it in a background job that can move checked photos to the trash
/// itself; iOS can't delete a photo without the user (PhotoKit always asks), so the run (plan, hashing on the phone and
/// on the server) happens in the app, goes on for the little time iOS gives after the app leaves the screen, and every
/// photo that passed waits for Move to trash and iOS's prompt. A run that ends with the app away posts a notification,
/// as Android's does at the end.
final class FreeUpService {
    static let shared = FreeUpService()

    private(set) lazy var freeUp: IosFreeUp = IosJournal.shared.freeUp(backup: BackupService.shared.controller) { [weak self] in
        self?.runNow()
    }

    var controller: FreeUpController { freeUp.controller }

    private var run: ObservationHandle?
    private var keepRunning = UIBackgroundTaskIdentifier.invalid
    private static let confirmNotification = "free-up-confirm"

    /// Starts a run unless one is going.
    private func runNow() {
        guard run == nil else { return }
        // A run stops when iOS suspends the app; what was checked by then waits.
        keepRunning = UIApplication.shared.beginBackgroundTask(withName: "Free up space") { [weak self] in
            self?.run?.cancel()
            self?.endKeepRunning()
        }
        run = freeUp.launchRun { [weak self] in self?.runEnded() }
    }

    private func runEnded() {
        run = nil
        let state = controller.currentState
        if state.waitingCount > 0, UIApplication.shared.applicationState != .active {
            notifyWaiting(count: Int(state.waitingCount), bytes: state.waitingBytes)
        }
        endKeepRunning()
    }

    private func endKeepRunning() {
        guard keepRunning != .invalid else { return }
        UIApplication.shared.endBackgroundTask(keepRunning)
        keepRunning = .invalid
    }

    // MARK: Notification

    /// Asked when Free up is tapped, as on Android: the notification is only for a run that ends with the app away.
    func askNotificationPermission() {
        UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound]) { _, _ in }
    }

    /// "120 files checked: tap to move them to the trash"; tapping it opens the app.
    private func notifyWaiting(count: Int, bytes: Int64) {
        let content = UNMutableNotificationContent()
        content.title = String(localized: "\(count) files checked: tap to move them to the trash")
        content.body = String(localized: "\(formatBytes(bytes)) can leave this phone. Your server has every one of them.")
        let request = UNNotificationRequest(identifier: Self.confirmNotification, content: content, trigger: nil)
        UNUserNotificationCenter.current().add(request)
    }

    /// The user answered iOS's prompt in the app: the notification has nothing left to say.
    func dismissNotification() {
        UNUserNotificationCenter.current().removeDeliveredNotifications(withIdentifiers: [Self.confirmNotification])
    }
}
