import Foundation
import SharedLogic

/// Bridges the shared Kotlin `StoriesController` (the writing, tags, crops and sets kept on this device, and the
/// storyline built from them) into SwiftUI.
final class StoriesViewModel: ObservableObject {
    @Published private(set) var state: StoriesState

    /// Shared with `SearchViewModel`, whose town chips use the places worked out here.
    let controller: StoriesController
    private var observation: ObservationHandle?

    init(library: LibraryViewModel, settings: SettingsViewModel) {
        controller = IosJournal.shared.createStoriesController(library: library.controller, settings: settings.controller)
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

    // MARK: Writing, crops and tags

    /// The photo's title and paragraph; what was there before is kept as a version.
    func setWriting(title: String, text: String, of itemId: String) { controller.setWriting(itemId: itemId, title: title, text: text) }
    /// Back to an earlier version; what it replaces is kept too.
    func restoreWriting(_ version: NoteVersion, of itemId: String) { controller.restoreWriting(itemId: itemId, version: version) }
    /// "Remove from storyline" (false): the writing stays and shows in Moments; true puts it back.
    func setInStoryline(_ inStoryline: Bool, of itemId: String) { controller.setInStoryline(itemId: itemId, inStoryline: inStoryline) }

    /// The photo's crops, for looking only (the photo isn't changed); empty crops remove them.
    func setCrops(_ crops: PhotoCrops, of itemId: String) { controller.setCrops(itemId: itemId, crops: crops) }
    /// Where the viewer was zoomed in on the photo as it was left (nil: fitted), so it opens there next time.
    func setZoom(_ zoom: CropRect?, of itemId: String) { controller.setZoom(itemId: itemId, zoom: zoom) }

    // MARK: Moments

    /// The photos of the picked sets and photos become one set you swipe.
    func mergeInFeed(_ itemIds: [String]) { controller.mergeInFeed(itemIds: itemIds) }
    func takeOutOfSet(_ itemId: String) { controller.takeOutOfSet(itemId: itemId) }
    /// The set of `itemIds` was turned to `itemId`: it opens there next time.
    func turnSet(_ itemIds: [String], to itemId: String) { controller.turnSet(itemIds: itemIds, itemId: itemId) }
    /// A hidden source's photos shown on one row (its key), until hidden again; the setting doesn't change.
    func showOnRow(_ sectionKey: String, source: MediaSource) { controller.showOnRow(sectionKey: sectionKey, source: source) }
    func hideOnRow(_ sectionKey: String, source: MediaSource) { controller.hideOnRow(sectionKey: sectionKey, source: source) }

    @discardableResult
    func addTag(_ tag: String, to itemIds: [String]) -> String? { controller.addTag(itemIds: itemIds, tag: tag) }

    func removeTag(_ tag: String, from itemIds: [String]) { controller.removeTag(itemIds: itemIds, tag: tag) }
}
