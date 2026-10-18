import Foundation
import SharedLogic

/// Bridges the shared Kotlin `JournalController` into SwiftUI.
final class JournalViewModel: ObservableObject {
    @Published private(set) var state: JournalState

    /// The device registration wizard; a successful enrollment switches this controller's identity.
    let enrollment: EnrollmentViewModel

    /// The device photo library, and the stories built on it.
    let library: LibraryViewModel
    let stories: StoriesViewModel
    let settings: SettingsViewModel

    private let controller: JournalController
    private var observation: ObservationHandle?

    init() {
        controller = IosJournal.shared.createController()
        enrollment = EnrollmentViewModel(journal: controller)
        library = LibraryViewModel()
        settings = SettingsViewModel()
        stories = StoriesViewModel(library: library, settings: settings)
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

    /// Connects with the stored identity; progress and result arrive in `state.status`.
    func connect() {
        controller.connect()
    }
}
