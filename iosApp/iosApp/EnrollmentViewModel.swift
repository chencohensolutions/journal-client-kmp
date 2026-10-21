import Foundation
import SharedLogic

/// Bridges the shared Kotlin `EnrollmentController` (device registration wizard) into SwiftUI.
final class EnrollmentViewModel: ObservableObject {
    @Published private(set) var state: EnrollmentState

    private let controller: EnrollmentController
    private var observation: ObservationHandle?

    init(journal: JournalController) {
        controller = IosJournal.shared.createEnrollmentController(journal: journal)
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

    func start() { controller.start() }
    func back() { controller.back() }
    func reset() { controller.reset() }
    func checkCode() { controller.checkCode() }
    func register() { controller.register() }
    func openLink(_ link: EnrollmentLink) { controller.openLink(link: link) }
    func openScanned(_ text: String) { controller.openScanned(text: text) }

    func updateServerAddress(_ address: String) { controller.updateServerAddress(address: address) }
    func updateCode(_ code: String) { controller.updateCode(code: code) }
    func updateDeviceName(_ name: String) { controller.updateDeviceName(name: name) }
}

extension EnrollmentController {
    static var identityAliasPrefix: String { EnrollmentController.Companion.shared.IDENTITY_ALIAS_PREFIX }
}
