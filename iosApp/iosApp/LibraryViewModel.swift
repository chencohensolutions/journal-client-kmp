import Foundation
import Photos
import SharedLogic

/// Bridges the shared Kotlin `LibraryController` (device photo library) into SwiftUI.
final class LibraryViewModel: ObservableObject {
    @Published private(set) var state: LibraryState

    /// Shared with `StoriesViewModel`, which builds the timeline from the same library.
    let controller: LibraryController
    private var observation: ObservationHandle?

    /// With the photos only on the server, the originals downloaded from it, and what "Show private media" hides.
    init(settings: SettingsViewModel) {
        let journal = IosJournal.shared
        controller = journal.createLibraryController(
            server: journal.serverLibrary(),
            originals: journal.keptOriginals(),
            privateMedia: settings.controller.privateMedia
        )
        state = controller.currentState
        // The controller delivers updates on the main thread.
        observation = controller.observe { [weak self] newState in
            self?.state = newState
        }
    }

    deinit {
        observation?.cancel()
        controller.close()
    }

    /// Re-checks the permission and re-reads the library; call when the app comes to the foreground.
    func refresh() { controller.refresh() }

    /// "Not now" on the photo access screen.
    func skipAccess() { controller.skipAccess() }

    /// Shows the system prompt. Once answered, iOS never shows it again; only Settings can change the answer.
    func requestAccess() {
        PHPhotoLibrary.requestAuthorization(for: .readWrite) { _ in
            DispatchQueue.main.async { self.controller.onAccessRequested() }
        }
    }
}
