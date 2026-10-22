import SwiftUI
import UIKit

@main
struct iOSApp: App {
    @UIApplicationDelegateAdaptor(AppDelegate.self) private var appDelegate
    @Environment(\.scenePhase) private var scenePhase

    var body: some Scene {
        WindowGroup {
            #if DEBUG
            if UiLab.isOn {
                UiLabView()
            } else {
                ContentView()
            }
            #else
            ContentView()
            #endif
        }
        .onChange(of: scenePhase) { _, phase in
            if !UiLab.isOn { BackupService.shared.sceneChanged(active: phase == .active) }
        }
    }
}

/// Backup's part of the app's life: its background tasks, and the upload session's news while the app was away.
final class AppDelegate: NSObject, UIApplicationDelegate {
    func application(_ application: UIApplication, didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil) -> Bool {
        if !UiLab.isOn { BackupService.shared.start() }
        return true
    }

    func application(_ application: UIApplication, handleEventsForBackgroundURLSession identifier: String, completionHandler: @escaping () -> Void) {
        BackupService.shared.handleEvents(identifier: identifier, completion: completionHandler)
    }
}
