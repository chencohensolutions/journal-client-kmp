import Foundation
import SharedLogic

/// Bridges the shared Kotlin `StoriesController` (stories kept on this device) into SwiftUI.
final class StoriesViewModel: ObservableObject {
    @Published private(set) var state: StoriesState

    private let controller: StoriesController
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

    /// Returns the new story's id, or nil when the title is blank.
    @discardableResult
    /// A row of Moments or a trip ("Save as story"), by its key; near-duplicate sets become groups.
    func createStory(title: String, feedKey: String) -> String? { controller.createStoryFromFeed(title: title, key: feedKey) }

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

    /// Photos and groups (tile keys) of the story, or of group `parentId`, into a new group inside it.
    @discardableResult
    func groupTiles(_ tileKeys: [String], in storyId: String, parent parentId: String? = nil) -> String? {
        controller.groupTiles(storyId: storyId, tileKeys: tileKeys, parentId: parentId)
    }

    /// Photos and groups (tile keys) of the story, or of group `parentId`, into the first picked group.
    @discardableResult
    func mergeTiles(_ tileKeys: [String], in storyId: String, parent parentId: String? = nil) -> String? {
        controller.mergeTiles(storyId: storyId, tileKeys: tileKeys, parentId: parentId)
    }

    /// Puts a story back as it was, to undo grouping.
    func restore(_ story: Story) { controller.restore(snapshot: story) }

    func dismissSuggestion(_ key: String, in storyId: String) { controller.dismissSuggestion(storyId: storyId, key: key) }
    func resetGroups(in storyId: String) { controller.resetGroups(storyId: storyId) }
    func acceptSuggestion(_ key: String, inGroup groupId: String, of storyId: String) -> String? {
        controller.acceptGroupSuggestion(storyId: storyId, groupId: groupId, key: key)
    }
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

    // MARK: Layout

    /// A tile's (`StoryTile.key`) size on the story's grid; nil goes back to the automatic size.
    func setTileSize(_ size: TileSize?, of tileKey: String, in storyId: String) {
        controller.setTileSize(storyId: storyId, tileKey: tileKey, size: size)
    }
    /// The point of the tile's photo that stays in view, and how much closer than filling the tile to show it.
    func setTileFocus(_ crop: TileCrop, of tileKey: String, in storyId: String) {
        controller.setTileFocus(storyId: storyId, tileKey: tileKey, x: Float(crop.x), y: Float(crop.y), zoom: Float(crop.zoom))
    }
    /// `offset` places later (negative: earlier) among the tiles of its day.
    func moveTile(_ tileKey: String, by offset: Int32, in storyId: String) {
        controller.moveTile(storyId: storyId, tileKey: tileKey, offset: offset)
    }
    /// Back to the automatic layout: sizes, focus points and order.
    func resetLayout(in storyId: String) { controller.resetLayout(storyId: storyId) }

    // MARK: Captions and tags

    func setCaption(_ text: String, of itemId: String) { controller.setCaption(itemId: itemId, text: text) }

    /// The photo's crops, for looking only (the photo isn't changed); empty crops remove them.
    func setCrops(_ crops: PhotoCrops, of itemId: String) { controller.setCrops(itemId: itemId, crops: crops) }

    // MARK: Moments

    /// The photos of the picked sets and photos become one set you swipe.
    func mergeInFeed(_ itemIds: [String]) { controller.mergeInFeed(itemIds: itemIds) }
    func takeOutOfSet(_ itemId: String) { controller.takeOutOfSet(itemId: itemId) }
    /// The set of `itemIds` was turned to `itemId`: it opens there next time.
    func turnSet(_ itemIds: [String], to itemId: String) { controller.turnSet(itemIds: itemIds, itemId: itemId) }

    @discardableResult
    func addTag(_ tag: String, to itemIds: [String]) -> String? { controller.addTag(itemIds: itemIds, tag: tag) }

    func removeTag(_ tag: String, from itemIds: [String]) { controller.removeTag(itemIds: itemIds, tag: tag) }
}
