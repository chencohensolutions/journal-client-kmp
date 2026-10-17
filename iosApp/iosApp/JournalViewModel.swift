import Foundation
import SharedLogic

/// Bridges the shared Kotlin `JournalController` into SwiftUI.
final class JournalViewModel: ObservableObject {
    @Published private(set) var state: JournalState

    /// The device registration wizard; a successful enrollment switches this controller's identity.
    let enrollment: EnrollmentViewModel

    private let controller: JournalController
    private var observation: ObservationHandle?

    init() {
        controller = IosJournal.shared.createController()
        enrollment = EnrollmentViewModel(journal: controller)
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

    func updateServerUrl(_ url: String) {
        controller.updateServerUrl(url: url)
    }

    func connect() {
        controller.connect()
    }

    /// Imports a PKCS#12 bundle into the Keychain and selects it. Returns an error message on failure.
    func importIdentity(_ data: Data, password: String) -> String? {
        let result = IosJournal.shared.importIdentity(pkcs12: data, password: password)
        if let success = result as? IdentityImportResultSuccess {
            controller.selectIdentity(alias: success.label)
            return nil
        }
        return (result as? IdentityImportResultFailure)?.message ?? "Unknown error"
    }
}
