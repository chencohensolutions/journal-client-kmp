import Foundation
import SharedLogic

/// Bridges the shared Kotlin `SearchController` (text search on the server, shown as the library has those photos)
/// into SwiftUI.
final class SearchViewModel: ObservableObject {
    @Published private(set) var state: SearchState

    private let controller: SearchController
    private var observation: ObservationHandle?

    init(library: LibraryViewModel, stories: StoriesViewModel, settings: SettingsViewModel) {
        let journal = IosJournal.shared
        controller = journal.createSearchController(
            library: library.controller, server: journal.serverLibrary(), stories: stories.controller, settings: settings.controller
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

    /// Asks the server for the photos `text` describes; blank clears.
    func search(_ text: String) { controller.search(text: text) }

    /// The best matches of `year` (nil: any year) or in `town` (nil: anywhere), keeping the other choice.
    func chooseYear(_ year: Int?) { controller.chooseYear(year: year.map { KotlinInt(int: Int32($0)) }) }
    func chooseTown(_ town: String?) { controller.chooseTown(town: town) }

    /// The next page, when the list nears its end.
    func loadMore() { controller.loadMore() }

    /// Back to nothing searched.
    func clear() { controller.clear() }
}
