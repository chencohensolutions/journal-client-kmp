import BackgroundTasks
import Network
import SharedLogic
import UIKit

/// When backup runs on iOS. The shared `BackupController` does the work; each piece goes to iOS's background
/// URLSession, which carries it on while the app is suspended and relaunches the app when it is answered, so the run
/// goes on from the server's offset. Runs start when the app comes to the foreground, on "Back up now", 10 s after
/// photos are added, when iOS wakes the app for an answered piece, and in background tasks iOS schedules
/// (BGProcessingTask, BGAppRefreshTask). Each waits for the settings' conditions (Wi-Fi, charging).
final class BackupService: NSObject, BackupScheduler {
    static let shared = BackupService()

    /// Listed in Info.plist (`BGTaskSchedulerPermittedIdentifiers`).
    static let processingTask = "com.chencohensolution.journal.backup.processing"
    static let refreshTask = "com.chencohensolution.journal.backup.refresh"

    private(set) lazy var controller: BackupController = IosJournal.shared.backupController(scheduler: self)

    private var run: ObservationHandle?
    private var whenRunEnds: [() -> Void] = []
    private let pathMonitor = NWPathMonitor()
    private var path: NWPath?
    private var libraryWatch: ObservationHandle?
    private var newPhotos: DispatchWorkItem?
    private var keepRunning = UIBackgroundTaskIdentifier.invalid

    /// From `application(_:didFinishLaunchingWithOptions:)`: background tasks must be registered before it returns.
    func start() {
        _ = controller // Connects the upload session, so iOS can deliver what happened while the app was gone.
        BGTaskScheduler.shared.register(forTaskWithIdentifier: Self.processingTask, using: nil) { [weak self] task in
            self?.runInBackgroundTask(task)
        }
        BGTaskScheduler.shared.register(forTaskWithIdentifier: Self.refreshTask, using: nil) { [weak self] task in
            self?.runInBackgroundTask(task)
        }
        pathMonitor.pathUpdateHandler = { [weak self] path in
            DispatchQueue.main.async { self?.networkChanged(path) }
        }
        pathMonitor.start(queue: .global(qos: .utility))
        UIDevice.current.isBatteryMonitoringEnabled = true
        NotificationCenter.default.addObserver(self, selector: #selector(powerChanged), name: UIDevice.batteryStateDidChangeNotification, object: nil)
        libraryWatch = IosJournal.shared.watchLibrary { [weak self] in
            DispatchQueue.main.async { self?.photosChanged() }
        }
        schedule(controller.currentState.settings)
    }

    /// iOS relaunched or woke the app for the upload session: the run goes on (or starts again) from the server's offset.
    func handleEvents(identifier: String, completion: @escaping () -> Void) {
        guard IosJournal.shared.handleBackgroundUploadEvents(identifier: identifier, completion: completion) else {
            completion()
            return
        }
        runNow()
    }

    func sceneChanged(active: Bool) {
        if active {
            runNow()
        } else {
            // A little time to hand the current piece to iOS; the background session does the rest.
            if run != nil, keepRunning == .invalid {
                keepRunning = UIApplication.shared.beginBackgroundTask(withName: "Backup") { [weak self] in self?.endKeepRunning() }
            }
            schedule(controller.currentState.settings)
        }
    }

    // MARK: BackupScheduler

    func settingsChanged(settings: BackupSettings) {
        schedule(settings)
        if !allowed(settings) { stop() }
    }

    func backUpNow(settings: BackupSettings) { runNow() }

    // MARK: Runs

    /// Starts a run unless one is going or the conditions don't allow it; `then` is called when it ends (or right away).
    func runNow(then: (() -> Void)? = nil) {
        let settings = controller.currentState.settings
        guard settings.enabled, allowed(settings) else {
            then?()
            return
        }
        if let then { whenRunEnds.append(then) }
        guard run == nil else { return }
        run = controller.launchRun { [weak self] _ in
            DispatchQueue.main.async { self?.runEnded() }
        }
    }

    private func runEnded() {
        run = nil
        let waiting = whenRunEnds
        whenRunEnds = []
        waiting.forEach { $0() }
        endKeepRunning()
    }

    /// The conditions changed: the run stops. A piece already with iOS still arrives.
    private func stop() {
        run?.cancel()
    }

    private func endKeepRunning() {
        guard keepRunning != .invalid else { return }
        UIApplication.shared.endBackgroundTask(keepRunning)
        keepRunning = .invalid
    }

    /// Wi-Fi only: not on mobile data or a hotspot (iOS marks both expensive). Charging only: plugged in. The simulator
    /// reports no battery state, which doesn't stop backup.
    private func allowed(_ settings: BackupSettings) -> Bool {
        if settings.wifiOnly, let path, path.status != .satisfied || path.isExpensive { return false }
        if settings.chargingOnly && UIDevice.current.batteryState == .unplugged { return false }
        return true
    }

    private func networkChanged(_ path: NWPath) {
        self.path = path
        let settings = controller.currentState.settings
        if allowed(settings) {
            if UIApplication.shared.applicationState == .active { runNow() }
        } else {
            stop()
        }
    }

    @objc private func powerChanged() {
        DispatchQueue.main.async { self.networkChanged(self.path ?? self.pathMonitor.currentPath) }
    }

    /// Like Android, 10 s after the library changes (new photos, or the file facts of many arriving).
    private func photosChanged() {
        newPhotos?.cancel()
        let work = DispatchWorkItem { [weak self] in self?.runNow() }
        newPhotos = work
        DispatchQueue.main.asyncAfter(deadline: .now() + 10, execute: work)
    }

    // MARK: Background tasks

    private func runInBackgroundTask(_ task: BGTask) {
        DispatchQueue.main.async {
            self.schedule(self.controller.currentState.settings)
            // When iOS ends the task, the run stops; the piece on its way still arrives and wakes the app.
            task.expirationHandler = { DispatchQueue.main.async { self.stop() } }
            self.runNow { task.setTaskCompleted(success: true) }
        }
    }

    /// Asks iOS for the next background runs; with backup off, none.
    private func schedule(_ settings: BackupSettings) {
        let scheduler = BGTaskScheduler.shared
        guard settings.enabled else {
            scheduler.cancelAllTaskRequests()
            return
        }
        let processing = BGProcessingTaskRequest(identifier: Self.processingTask)
        processing.requiresNetworkConnectivity = true
        processing.requiresExternalPower = settings.chargingOnly
        processing.earliestBeginDate = Date(timeIntervalSinceNow: 15 * 60)
        let refresh = BGAppRefreshTaskRequest(identifier: Self.refreshTask)
        refresh.earliestBeginDate = Date(timeIntervalSinceNow: 15 * 60)
        // Refused on the simulator, and when Background App Refresh is off.
        try? scheduler.submit(processing)
        try? scheduler.submit(refresh)
    }
}
