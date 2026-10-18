package com.chencohensolution.journal

import com.chencohensolution.journal.stories.StoryIds
import com.chencohensolution.journal.stories.StoryTile
import com.chencohensolution.journal.stories.StoriesController
import com.chencohensolution.journal.stories.FilePhotoNoteStore
import com.chencohensolution.journal.stories.FileStoryStore
import com.chencohensolution.journal.stories.StoryText
import com.chencohensolution.journal.stories.findBursts
import com.chencohensolution.journal.stories.groupTileKey
import com.chencohensolution.journal.stories.formatStoryId
import com.chencohensolution.journal.library.LibraryController
import com.chencohensolution.journal.library.LocalDay
import com.chencohensolution.journal.library.MediaItem
import com.chencohensolution.journal.library.MediaLibrary
import com.chencohensolution.journal.library.MediaType
import com.chencohensolution.journal.library.PhotoAccess
import com.chencohensolution.journal.settings.JournalSettings
import com.chencohensolution.journal.settings.SettingsController
import com.chencohensolution.journal.settings.KeyValueStore
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class GroupsTestLibrary(val items: List<MediaItem>) : MediaLibrary {
    override fun access() = PhotoAccess.FULL
    override suspend fun loadItems() = items
}

private class GroupsTestStore : KeyValueStore {
    private val values = mutableMapOf<String, String>()
    override fun getString(key: String) = values[key]
    override fun putString(key: String, value: String?) {
        if (value == null) values.remove(key) else values[key] = value
    }
}

private val DAY = LocalDay(2026, 10, 8)
private val DAY_START = DAY.toEpochDay() * 86_400_000

/** A photo taken [second] seconds into Oct 8. */
private fun shot(id: String, second: Int, type: MediaType = MediaType.IMAGE, burst: String? = null) =
    MediaItem(id, type, DAY_START + second * 1000L, DAY, null, 10, 10, 100, "$id.jpg", burstId = burst)

private val GROUPS_ROOT = "/data/journal".toPath()

class BurstTest {
    @Test
    fun photosWithinTenSecondsFormABurst() {
        val items = listOf(shot("a", 0), shot("b", 4), shot("c", 13), shot("d", 30), shot("e", 31))
        val bursts = findBursts(items, emptySet())
        assertEquals(listOf(listOf("a", "b", "c")), bursts.map { burst -> burst.items.map { it.id } })
        assertEquals(13, bursts.single().seconds)
    }

    @Test
    fun theCamerasBurstIdJoinsLongerGaps() {
        val items = listOf(shot("a", 0, burst = "x"), shot("b", 20, burst = "x"), shot("c", 40, burst = "x"))
        assertEquals(3, findBursts(items, emptySet()).single().count)
    }

    @Test
    fun videosAndDismissedRunsAreLeftOut() {
        val items = listOf(shot("a", 0), shot("v", 1, MediaType.VIDEO), shot("b", 2), shot("c", 3))
        assertEquals(listOf("a", "b", "c"), findBursts(items, emptySet()).single().items.map { it.id })
        assertTrue(findBursts(items, setOf("a")).isEmpty())
    }
}

class StoryTextTest {
    @Test
    fun roundTrip() {
        val markdown = StoryText("We landed at night.", mapOf("g1" to "Tuna auction at 5am.", "g2" to "")).toMarkdown("Japan trip")
        assertEquals(
            "# Japan trip\n\nWe landed at night.\n\n<!-- group:g1 -->\nTuna auction at 5am.\n\n<!-- group:g2 -->\n",
            markdown,
        )
        val parsed = StoryText.parse(markdown)
        assertEquals("We landed at night.", parsed.intro)
        assertEquals(mapOf("g1" to "Tuna auction at 5am.", "g2" to ""), parsed.sections)
    }

    @Test
    fun handEditedFilesAreRead() {
        val parsed = StoryText.parse("Intro without heading\n\nsecond paragraph\n<!--group:g7-->\n  text  \n")
        assertEquals("Intro without heading\n\nsecond paragraph", parsed.intro)
        assertEquals(mapOf("g7" to "text"), parsed.sections)
    }
}

class StoryIdsTest {
    @Test
    fun formatIsAValidFolderName() {
        assertEquals("2026-10-17T12-52-22.257Z", formatStoryId(LocalDay(2026, 10, 17).toEpochDay() * 86_400_000 + 46_342_257))
    }

    @Test
    fun idsNeverRepeatEvenIfTheClockGoesBack() {
        var clock = 1_000L
        val ids = StoryIds(JournalSettings(GroupsTestStore())) { clock }
        val first = ids.next()
        val second = ids.next()
        clock = 500
        val third = ids.next()
        assertEquals(listOf(formatStoryId(1000), formatStoryId(1001), formatStoryId(1002)), listOf(first, second, third))
    }
}

class GroupsTest {
    private val items = listOf(
        shot("a", 0), shot("b", 3), shot("c", 6), shot("d", 9),
        shot("e", 100), shot("f", 200),
    )

    private class Setup(val stories: StoriesController, val fileSystem: FakeFileSystem)

    private fun TestScope.setup(fileSystem: FakeFileSystem = FakeFileSystem()): Setup {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val library = LibraryController(GroupsTestLibrary(items), JournalSettings(GroupsTestStore()), dispatcher)
        library.refresh()
        var id = 0
        val stories = StoriesController(
            store = FileStoryStore(fileSystem, GROUPS_ROOT),
            noteStore = FilePhotoNoteStore(fileSystem, GROUPS_ROOT),
            library = library,
            settings = SettingsController(JournalSettings(GroupsTestStore()), dispatcher),
            newId = { "story-${++id}" },
            dispatcher = dispatcher,
            now = { "2026-10-17T12:00:00Z" },
        )
        advanceUntilIdle()
        return Setup(stories, fileSystem)
    }

    @Test
    fun acceptingASuggestionMakesOneTile() = runTest {
        val stories = setup().stories
        val storyId = assertNotNull(stories.createStoryFromDay("Walk", DAY))
        val suggestion = stories.currentState.story(storyId)!!.suggestions.single()
        assertEquals(listOf("a", "b", "c", "d"), suggestion.items.map { it.id })

        val groupId = assertNotNull(stories.acceptSuggestion(storyId, suggestion.key))
        val story = stories.currentState.story(storyId)!!
        assertEquals(3, story.tiles.size)
        val tile = assertIs<StoryTile.Group>(story.tiles.first())
        assertEquals(groupId, tile.group.id)
        assertEquals("a", tile.group.cover.id)
        assertEquals(listOf("a", "e", "f"), story.viewerItems.map { it.id })
        assertTrue(story.suggestions.isEmpty())
        assertEquals(6, story.itemCount)
    }

    @Test
    fun dismissedSuggestionsStayDismissed() = runTest {
        val fileSystem = FakeFileSystem()
        val stories = setup(fileSystem).stories
        val storyId = assertNotNull(stories.createStoryFromDay("Walk", DAY))
        stories.dismissSuggestion(storyId, "a")
        assertTrue(stories.currentState.story(storyId)!!.suggestions.isEmpty())
        assertTrue(setup(fileSystem).stories.currentState.story(storyId)!!.suggestions.isEmpty())
    }

    @Test
    fun groupsAndTheirTextSurviveAReload() = runTest {
        val fileSystem = FakeFileSystem()
        val stories = setup(fileSystem).stories
        val storyId = assertNotNull(stories.createStoryFromDay("Walk", DAY))
        val groupId = assertNotNull(stories.createGroup(storyId, listOf("b", "c"), "Fixing the car"))
        stories.setCover(storyId, groupId, "c")
        stories.setGroupText(storyId, groupId, "The car wouldn't start.")
        stories.setIntro(storyId, "A slow morning.")

        val story = fileSystem.read(GROUPS_ROOT / "stories" / storyId / "story.md") { readUtf8() }
        assertEquals("# Walk\n\nA slow morning.\n\n<!-- group:g1 -->\nThe car wouldn't start.\n", story)
        val json = fileSystem.read(GROUPS_ROOT / "stories" / storyId / "story.json") { readUtf8() }
        assertTrue("\"type\": \"group\"" in json, json)
        assertTrue("\"cover\": \"c\"" in json, json)

        val reloaded = setup(fileSystem).stories.currentState.story(storyId)!!
        val group = assertNotNull(reloaded.group(groupId))
        assertEquals("Fixing the car", group.title)
        assertEquals("c", group.cover.id)
        assertEquals(listOf("b", "c"), group.items.map { it.id })
        assertEquals("The car wouldn't start.", group.text)
        assertEquals("A slow morning.", reloaded.intro)
        assertEquals(6, reloaded.itemCount)
    }

    @Test
    fun aGroupAndPhotosMergeIntoThatGroup() = runTest {
        val stories = setup().stories
        val storyId = assertNotNull(stories.createStoryFromDay("Walk", DAY))
        val groupId = assertNotNull(stories.createGroup(storyId, listOf("b", "c"), "Car"))
        stories.setCover(storyId, groupId, "c")

        assertEquals(groupId, stories.mergeTiles(storyId, listOf("a", groupTileKey(groupId), "f")))
        val story = stories.currentState.story(storyId)!!
        val group = story.groups.single()
        assertEquals(listOf("a", "b", "c", "f"), group.items.map { it.id })
        assertEquals("Car", group.title)
        assertEquals("c", group.cover.id)
        assertEquals(listOf(groupTileKey(groupId), "d", "e"), story.tiles.map { it.key })
    }

    @Test
    fun twoGroupsMergeIntoTheFirstWithBothTexts() = runTest {
        val stories = setup().stories
        val storyId = assertNotNull(stories.createStoryFromDay("Walk", DAY))
        val first = assertNotNull(stories.createGroup(storyId, listOf("a", "b")))
        val second = assertNotNull(stories.createGroup(storyId, listOf("e", "f"), "Lunch"))
        stories.setGroupText(storyId, first, "Morning.")
        stories.setGroupText(storyId, second, "Noon.")

        assertEquals(first, stories.mergeTiles(storyId, listOf(groupTileKey(second), groupTileKey(first))))
        val group = stories.currentState.story(storyId)!!.groups.single()
        assertEquals(first, group.id)
        assertEquals(listOf("a", "b", "e", "f"), group.items.map { it.id })
        assertEquals("Lunch", group.title)
        assertEquals("Morning.\n\nNoon.", group.text)
        assertEquals("", stories.currentState.story(storyId)!!.intro)
    }

    @Test
    fun restoreUndoesAMerge() = runTest {
        val stories = setup().stories
        val storyId = assertNotNull(stories.createStoryFromDay("Walk", DAY))
        val first = assertNotNull(stories.createGroup(storyId, listOf("a", "b")))
        val second = assertNotNull(stories.createGroup(storyId, listOf("e", "f")))
        val before = stories.currentState.story(storyId)!!.story
        stories.mergeTiles(storyId, listOf(groupTileKey(first), groupTileKey(second)))
        stories.restore(before)
        assertEquals(listOf(first, second), stories.currentState.story(storyId)!!.groups.map { it.id })
    }

    @Test
    fun ungroupingKeepsPhotosAndText() = runTest {
        val stories = setup().stories
        val storyId = assertNotNull(stories.createStoryFromDay("Walk", DAY))
        val groupId = assertNotNull(stories.createGroup(storyId, listOf("e", "f")))
        stories.setIntro(storyId, "Intro.")
        stories.setGroupText(storyId, groupId, "Group text.")

        stories.ungroup(storyId, groupId)
        val story = stories.currentState.story(storyId)!!
        assertNull(story.group(groupId))
        assertEquals(6, story.tiles.size)
        assertEquals("Intro.\n\nGroup text.", story.intro)
    }

    @Test
    fun aGroupLeftWithOnePhotoGoesAway() = runTest {
        val stories = setup().stories
        val storyId = assertNotNull(stories.createStoryFromDay("Walk", DAY))
        val groupId = assertNotNull(stories.createGroup(storyId, listOf("e", "f")))
        stories.removeFromGroup(storyId, groupId, listOf("f"))
        assertNull(stories.currentState.story(storyId)!!.group(groupId))

        val second = assertNotNull(stories.createGroup(storyId, listOf("e", "f")))
        stories.removeItems(storyId, listOf("e"))
        val story = stories.currentState.story(storyId)!!
        assertNull(story.group(second))
        assertEquals(5, story.itemCount)
    }

    @Test
    fun aPhotoMovesBetweenGroups() = runTest {
        val stories = setup().stories
        val storyId = assertNotNull(stories.createStoryFromDay("Walk", DAY))
        val first = assertNotNull(stories.createGroup(storyId, listOf("a", "b", "c")))
        val second = assertNotNull(stories.createGroup(storyId, listOf("c", "d")))
        val story = stories.currentState.story(storyId)!!
        assertEquals(listOf("a", "b"), story.group(first)!!.items.map { it.id })
        assertEquals(listOf("c", "d"), story.group(second)!!.items.map { it.id })
        assertNull(stories.createGroup(storyId, listOf("e")))
    }

    @Test
    fun groupingTilesOfAGroupMakesASubgroup() = runTest {
        val stories = setup().stories
        val storyId = assertNotNull(stories.createStoryFromDay("Walk", DAY))
        val outer = assertNotNull(stories.createGroup(storyId, listOf("a", "b", "c", "d"), "Morning"))

        val inner = assertNotNull(stories.groupTiles(storyId, listOf("b", "c"), parentId = outer))
        val story = stories.currentState.story(storyId)!!
        assertEquals(listOf(groupTileKey(outer), "e", "f"), story.tiles.map { it.key })
        val group = story.group(outer)!!
        assertEquals(listOf("a", groupTileKey(inner), "d"), group.tiles.map { it.key })
        assertEquals(listOf("a", "b", "c", "d"), group.items.map { it.id })
        assertEquals(listOf("a", "b", "d"), group.viewerItems.map { it.id })
        assertEquals(inner, group.coverStop("b")?.id)
        assertEquals(outer, story.group(inner)!!.parentId)
        assertEquals(listOf(outer), story.parentsOf(inner).map { it.id })
        // Wrapping every tile of a group again would change nothing worth having.
        assertNull(stories.groupTiles(storyId, listOf("a", groupTileKey(inner), "d"), parentId = outer))
    }

    @Test
    fun groupingGroupsNestsThem() = runTest {
        val stories = setup().stories
        val storyId = assertNotNull(stories.createStoryFromDay("Walk", DAY))
        val first = assertNotNull(stories.createGroup(storyId, listOf("a", "b")))
        val second = assertNotNull(stories.createGroup(storyId, listOf("c", "d")))

        val outer = assertNotNull(stories.groupTiles(storyId, listOf(groupTileKey(first), groupTileKey(second), "e")))
        val story = stories.currentState.story(storyId)!!
        assertEquals(listOf(groupTileKey(outer), "f"), story.tiles.map { it.key })
        assertEquals(listOf(groupTileKey(first), groupTileKey(second), "e"), story.group(outer)!!.tiles.map { it.key })
        assertEquals("a", story.group(outer)!!.cover.id)
        assertEquals(listOf(outer, first, second), story.groups.map { it.id })
    }

    @Test
    fun nestedGroupsAndTheirTextSurviveAReload() = runTest {
        val fileSystem = FakeFileSystem()
        val stories = setup(fileSystem).stories
        val storyId = assertNotNull(stories.createStoryFromDay("Walk", DAY))
        val outer = assertNotNull(stories.createGroup(storyId, listOf("a", "b", "c", "d"), "Morning"))
        val inner = assertNotNull(stories.groupTiles(storyId, listOf("c", "d"), parentId = outer))
        stories.renameGroup(storyId, inner, "Car")
        stories.setCover(storyId, outer, "d")
        stories.setGroupText(storyId, outer, "Outer.")
        stories.setGroupText(storyId, inner, "Inner.")

        val markdown = fileSystem.read(GROUPS_ROOT / "stories" / storyId / "story.md") { readUtf8() }
        assertEquals("# Walk\n\n<!-- group:g1 -->\nOuter.\n\n<!-- group:g2 -->\nInner.\n", markdown)

        val reloaded = setup(fileSystem).stories.currentState.story(storyId)!!
        val group = reloaded.group(outer)!!
        assertEquals("d", group.cover.id)
        assertEquals(listOf("a", "b", groupTileKey(inner)), group.tiles.map { it.key })
        val sub = reloaded.group(inner)!!
        assertEquals("Car", sub.title)
        assertEquals("Inner.", sub.text)
        assertEquals(outer, sub.parentId)
        assertEquals(6, reloaded.itemCount)
    }

    @Test
    fun ungroupingASubgroupMovesItsPhotosAndTextUpOneLevel() = runTest {
        val stories = setup().stories
        val storyId = assertNotNull(stories.createStoryFromDay("Walk", DAY))
        val outer = assertNotNull(stories.createGroup(storyId, listOf("a", "b", "c", "d")))
        val inner = assertNotNull(stories.groupTiles(storyId, listOf("c", "d"), parentId = outer))
        stories.setGroupText(storyId, outer, "Outer.")
        stories.setGroupText(storyId, inner, "Inner.")

        stories.ungroup(storyId, inner)
        val story = stories.currentState.story(storyId)!!
        assertNull(story.group(inner))
        assertEquals(listOf("a", "b", "c", "d"), story.group(outer)!!.tiles.map { it.key })
        assertEquals("Outer.\n\nInner.", story.group(outer)!!.text)
        assertEquals("", story.intro)
    }

    @Test
    fun ungroupingAParentKeepsItsSubgroups() = runTest {
        val stories = setup().stories
        val storyId = assertNotNull(stories.createStoryFromDay("Walk", DAY))
        val outer = assertNotNull(stories.createGroup(storyId, listOf("a", "b", "c", "d")))
        val inner = assertNotNull(stories.groupTiles(storyId, listOf("c", "d"), parentId = outer))

        stories.ungroup(storyId, outer)
        val story = stories.currentState.story(storyId)!!
        assertNull(story.group(outer))
        assertNull(story.group(inner)!!.parentId)
        assertEquals(listOf("a", "b", groupTileKey(inner), "e", "f"), story.tiles.map { it.key })
    }

    @Test
    fun takingPhotosOutOfASubgroupPutsThemInTheGroupAround() = runTest {
        val stories = setup().stories
        val storyId = assertNotNull(stories.createStoryFromDay("Walk", DAY))
        val outer = assertNotNull(stories.createGroup(storyId, listOf("a", "b", "c", "d")))
        val inner = assertNotNull(stories.groupTiles(storyId, listOf("b", "c", "d"), parentId = outer))

        stories.removeFromGroup(storyId, inner, listOf("d"))
        var story = stories.currentState.story(storyId)!!
        assertEquals(listOf("a", groupTileKey(inner), "d"), story.group(outer)!!.tiles.map { it.key })

        // Left with one photo, the subgroup goes; its last photo stays in the group around it.
        stories.removeFromGroup(storyId, inner, listOf("c"))
        story = stories.currentState.story(storyId)!!
        assertNull(story.group(inner))
        assertEquals(listOf("a", "b", "c", "d"), story.group(outer)!!.tiles.map { it.key })
    }

    @Test
    fun aGroupLeftWithOnlyASubgroupGoesAway() = runTest {
        val stories = setup().stories
        val storyId = assertNotNull(stories.createStoryFromDay("Walk", DAY))
        val outer = assertNotNull(stories.createGroup(storyId, listOf("a", "b", "c")))
        val inner = assertNotNull(stories.groupTiles(storyId, listOf("b", "c"), parentId = outer))

        stories.removeFromGroup(storyId, outer, listOf("a"))
        val story = stories.currentState.story(storyId)!!
        assertNull(story.group(outer))
        assertNull(story.group(inner)!!.parentId)
        assertEquals(listOf("a", groupTileKey(inner), "d", "e", "f"), story.tiles.map { it.key })
    }

    @Test
    fun subgroupsMergeInsideTheirGroup() = runTest {
        val stories = setup().stories
        val storyId = assertNotNull(stories.createStoryFromDay("Walk", DAY))
        val outer = assertNotNull(stories.createGroup(storyId, listOf("a", "b", "c", "d", "e")))
        val first = assertNotNull(stories.groupTiles(storyId, listOf("a", "b"), parentId = outer))
        val second = assertNotNull(stories.groupTiles(storyId, listOf("c", "d"), parentId = outer))

        assertEquals(first, stories.mergeTiles(storyId, listOf(groupTileKey(first), groupTileKey(second)), parentId = outer))
        val story = stories.currentState.story(storyId)!!
        assertNull(story.group(second))
        assertEquals(listOf(groupTileKey(first), "e"), story.group(outer)!!.tiles.map { it.key })
        assertEquals(listOf("a", "b", "c", "d"), story.group(first)!!.items.map { it.id })
    }
}
