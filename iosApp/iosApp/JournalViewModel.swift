import Foundation
import SharedLogic

/// Bridges the shared Kotlin `JournalController` into SwiftUI.
final class JournalViewModel: ObservableObject {
    @Published private(set) var state: JournalState

    /// The device registration wizard; a successful enrollment switches this controller's identity.
    let enrollment: EnrollmentViewModel

    /// Backup to the server, the device photo library (with each photo's backup status), and the writing, storyline and Moments built on it.
    let backup: BackupViewModel
    let library: LibraryViewModel
    let stories: StoriesViewModel
    let settings: SettingsViewModel
    /// Text search on the server (or this phone).
    let search: SearchViewModel
    /// Explore's People, what the server found in the photo the viewer shows, and Settings › On this phone.
    let people: PeopleViewModel
    let findings: FindingsViewModel
    let phoneSearch: PhoneSearchViewModel
    /// Settings › Search › On your server: the reading of this user's photos for the server's indexes.
    let serverIndexing: ServerIndexingViewModel
    /// What the server can do: People, a photo's text and similar photos, On this phone are offered only when it can.
    @Published private(set) var features = ServerFeatures.companion.NONE
    private var featuresObservation: ObservationHandle?

    private let controller: JournalController
    private var observation: ObservationHandle?

    init() {
        // Before anything asks for the indexes this phone keeps: they run the search model with these.
        IosJournal.shared.textEncoding = OnnxTextEncoding()
        IosJournal.shared.onWifi = { KotlinBoolean(bool: NetworkWatch.shared.onWifi) }
        controller = IosJournal.shared.createController()
        enrollment = EnrollmentViewModel(journal: controller)
        backup = BackupViewModel()
        settings = SettingsViewModel()
        library = LibraryViewModel(backup: backup, settings: settings)
        stories = StoriesViewModel(library: library, settings: settings)
        search = SearchViewModel(library: library, stories: stories, settings: settings)
        people = PeopleViewModel()
        findings = FindingsViewModel(library: library)
        phoneSearch = PhoneSearchViewModel()
        serverIndexing = ServerIndexingViewModel()
        state = controller.currentState
        featuresObservation = ServerImages.shared.files.observeFeatures { [weak self] in self?.features = $0 }
        // The controller delivers updates on the main thread.
        observation = controller.observe { [weak self] newState in
            self?.state = newState
        }
    }

    deinit {
        featuresObservation?.cancel()
        observation?.cancel()
        controller.close()
    }

    /// Connects with the stored identity; progress and result arrive in `state.status`.
    func connect() {
        controller.connect()
    }
}
