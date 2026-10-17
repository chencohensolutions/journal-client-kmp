import Foundation
import SharedLogic

/// Bridges the shared Kotlin `StoriesController` (stories kept on this device) into SwiftUI.
final class StoriesViewModel: ObservableObject {
    @Published private(set) var state: StoriesState

    private let controller: StoriesController
    private var observation: ObservationHandle?

    init(library: LibraryViewModel) {
        controller = IosJournal.shared.createStoriesController(library: library.controller)
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

    /// Returns the new story's id, or nil when the title is blank.
    @discardableResult
    func createStory(title: String, day: LocalDay) -> String? { controller.createStoryFromDay(title: title, day: day) }

    @discardableResult
    func createStory(title: String, itemIds: [String]) -> String? { controller.createStory(title: title, itemIds: itemIds) }

    func add(_ itemIds: [String], to storyId: String) { controller.addItems(storyId: storyId, itemIds: itemIds) }
    func remove(_ itemIds: [String], from storyId: String) { controller.removeItems(storyId: storyId, itemIds: itemIds) }
    func addDay(_ day: LocalDay, to storyId: String) { controller.addDay(storyId: storyId, day: day) }
    func rename(_ storyId: String, to title: String) { controller.rename(storyId: storyId, title: title) }
    func delete(_ storyId: String) { controller.delete(storyId: storyId) }
    func setIntro(_ text: String, of storyId: String) { controller.setIntro(storyId: storyId, text: text) }

    // MARK: Groups

    /// Each returns the new group's id.
    @discardableResult
    func acceptSuggestion(_ key: String, in storyId: String) -> String? { controller.acceptSuggestion(storyId: storyId, key: key) }

    @discardableResult
    func createGroup(_ itemIds: [String], in storyId: String) -> String? {
        controller.createGroup(storyId: storyId, itemIds: itemIds, title: nil)
    }

    func dismissSuggestion(_ key: String, in storyId: String) { controller.dismissSuggestion(storyId: storyId, key: key) }
    func renameGroup(_ groupId: String, in storyId: String, to title: String) {
        controller.renameGroup(storyId: storyId, groupId: groupId, title: title)
    }
    func setText(_ text: String, ofGroup groupId: String, in storyId: String) {
        controller.setGroupText(storyId: storyId, groupId: groupId, text: text)
    }
    func setCover(_ itemId: String, ofGroup groupId: String, in storyId: String) {
        controller.setCover(storyId: storyId, groupId: groupId, itemId: itemId)
    }
    func removeFromGroup(_ itemIds: [String], groupId: String, in storyId: String) {
        controller.removeFromGroup(storyId: storyId, groupId: groupId, itemIds: itemIds)
    }
    func ungroup(_ groupId: String, in storyId: String) { controller.ungroup(storyId: storyId, groupId: groupId) }

    // MARK: Captions and tags

    func setCaption(_ text: String, of itemId: String) { controller.setCaption(itemId: itemId, text: text) }

    @discardableResult
    func addTag(_ tag: String, to itemIds: [String]) -> String? { controller.addTag(itemIds: itemIds, tag: tag) }

    func removeTag(_ tag: String, from itemIds: [String]) { controller.removeTag(itemIds: itemIds, tag: tag) }
}
