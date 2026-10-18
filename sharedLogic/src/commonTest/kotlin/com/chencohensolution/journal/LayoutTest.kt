package com.chencohensolution.journal

import com.chencohensolution.journal.library.LibraryController
import com.chencohensolution.journal.library.LocalDay
import com.chencohensolution.journal.library.MediaItem
import com.chencohensolution.journal.library.MediaLibrary
import com.chencohensolution.journal.library.MediaType
import com.chencohensolution.journal.library.PhotoAccess
import com.chencohensolution.journal.settings.JournalSettings
import com.chencohensolution.journal.settings.KeyValueStore
import com.chencohensolution.journal.settings.SettingsController
import com.chencohensolution.journal.stories.FilePhotoNoteStore
import com.chencohensolution.journal.stories.FileStoryStore
import com.chencohensolution.journal.stories.FocusPoint
import com.chencohensolution.journal.stories.StoriesController
import com.chencohensolution.journal.stories.StoryTile
import com.chencohensolution.journal.stories.TileLayout
import com.chencohensolution.journal.stories.TileSize
import com.chencohensolution.journal.stories.arrangeTiles
import com.chencohensolution.journal.stories.groupTileKey
import com.chencohensolution.journal.stories.packDay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class LayoutTestLibrary(val items: List<MediaItem>) : MediaLibrary {
    override fun access() = PhotoAccess.FULL
    override suspend fun loadItems() = items
}

private class LayoutTestStore : KeyValueStore {
    private val values = mutableMapOf<String, String>()
    override fun getString(key: String) = values[key]
    override fun putString(key: String, value: String?) {
        if (value == null) values.remove(key) else values[key] = value
    }
}

private val DAY = LocalDay(2026, 10, 8)
private val NEXT_DAY = LocalDay(2026, 10, 9)
private val ROOT = "/data/journal".toPath()

/** A photo taken [minute] minutes into [day]; far enough apart not to be a burst. */
private fun photo(id: String, minute: Int, day: LocalDay = DAY) =
    MediaItem(id, MediaType.IMAGE, day.toEpochDay() * 86_400_000 + minute * 60_000L, day, null, 10, 10, 100, "$id.jpg")

private fun tiles(vararg ids: String) = ids.mapIndexed { index, id -> StoryTile.Media(photo(id, index)) }

/** "id@column,row:size" for each tile, band by band. */
private fun describe(bands: List<com.chencohensolution.journal.stories.StoryBand>) =
    bands.map { band -> band.rows to band.tiles.map { "${it.tile.key}@${it.column},${it.row}:${it.size.fileName}" } }

class PackingTest {
    @Test
    fun automaticLayoutIsALargeTileThenRowsOfThree() {
        val bands = packDay(tiles("a", "b", "c", "d", "e", "f"), emptyMap())
        assertEquals(
            listOf(
                4 to listOf("a@0,0:large", "b@4,0:small", "c@4,2:small"),
                2 to listOf("d@0,0:small", "e@2,0:small", "f@4,0:small"),
            ),
            describe(bands),
        )
    }

    @Test
    fun aSmallTileFillsTheHoleALargeOneLeft() {
        val layout = mapOf("b" to TileLayout(TileSize.LARGE))
        val bands = packDay(tiles("a", "b", "c", "d"), layout)
        // "a" is large automatically (first of 3+); "b" doesn't fit beside it and starts below.
        assertEquals(
            listOf(
                4 to listOf("a@0,0:large", "c@4,0:small", "d@4,2:small"),
                4 to listOf("b@0,0:large"),
            ),
            describe(bands),
        )
    }

    @Test
    fun bandsNeverCutThroughATile() {
        val layout = mapOf("a" to TileLayout(TileSize.TALL), "b" to TileLayout(TileSize.WIDE), "c" to TileLayout(TileSize.WIDE))
        val bands = packDay(tiles("a", "b", "c", "d"), layout)
        assertEquals(listOf(4 to listOf("a@0,0:tall", "b@2,0:wide", "c@2,2:wide"), 2 to listOf("d@0,0:small")), describe(bands))
    }

    @Test
    fun theOrderListsArrangedTilesFirstAndNewOnesByTime() {
        val all = tiles("a", "b", "c", "d")
        // "c" was moved first; "b" and "d" were added since and fall in after the last tile no newer than them.
        val ordered = arrangeTiles(all, listOf("c", "a"), key = { it.key }, time = { (it as StoryTile.Media).item.takenAtMillis })
        assertEquals(listOf("c", "a", "b", "d"), ordered.map { it.key })
    }
}

class LayoutTest {
    private val items = listOf(photo("a", 0), photo("b", 10), photo("c", 20), photo("d", 30), photo("e", 0, NEXT_DAY))

    private fun TestScope.setup(fileSystem: FakeFileSystem): StoriesController {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val library = LibraryController(LayoutTestLibrary(items), JournalSettings(LayoutTestStore()), dispatcher)
        library.refresh()
        val stories = StoriesController(
            store = FileStoryStore(fileSystem, ROOT),
            noteStore = FilePhotoNoteStore(fileSystem, ROOT),
            library = library,
            settings = SettingsController(JournalSettings(LayoutTestStore()), dispatcher),
            newId = { "story-1" },
            dispatcher = dispatcher,
            now = { "2026-10-17T12:00:00Z" },
        )
        advanceUntilIdle()
        return stories
    }

    private fun StoriesController.newStory(): String =
        assertNotNull(createStory("Walk", items.map { it.id }))

    @Test
    fun sizesFocusAndOrderSurviveAReload() = runTest {
        val fileSystem = FakeFileSystem()
        val stories = setup(fileSystem)
        val id = stories.newStory()
        stories.setTileSize(id, "b", TileSize.WIDE)
        stories.setTileFocus(id, "b", 0.2f, 1.5f)
        stories.moveTile(id, "d", -2)

        val json = fileSystem.read(ROOT / "stories" / id / "story.json") { readUtf8() }
        assertTrue("\"size\": \"wide\"" in json, json)
        assertTrue("\"y\": 1.0" in json, json)

        val story = setup(fileSystem).currentState.story(id)!!
        assertEquals(listOf("a", "d", "b", "c"), story.tileDays.first().tiles.map { it.key })
        assertEquals(listOf("e"), story.tileDays.last().tiles.map { it.key })
        val b = story.tileDays.first().bands.flatMap { it.tiles }.first { it.tile.key == "b" }
        assertEquals(TileSize.WIDE, b.size)
        assertEquals(FocusPoint(0.2f, 1f), b.focus)
        assertTrue(b.sized)
    }

    @Test
    fun aTileMovesOnlyWithinItsDay() = runTest {
        val stories = setup(FakeFileSystem())
        val id = stories.newStory()
        stories.moveTile(id, "d", 5)
        stories.moveTile(id, "e", -1)
        val story = stories.currentState.story(id)!!
        assertEquals(listOf(listOf("a", "b", "c", "d"), listOf("e")), story.tileDays.map { day -> day.tiles.map { it.key } })
    }

    @Test
    fun newPhotosFallInByTimeAfterArranging() = runTest {
        val stories = setup(FakeFileSystem())
        val id = assertNotNull(stories.createStory("Walk", listOf("a", "b", "d")))
        stories.moveTile(id, "a", 1)
        stories.addItems(id, listOf("c"))
        assertEquals(listOf("b", "a", "c", "d"), stories.currentState.story(id)!!.tiles.map { it.key })
    }

    @Test
    fun resetGoesBackToAutomatic() = runTest {
        val stories = setup(FakeFileSystem())
        val id = stories.newStory()
        stories.setTileSize(id, "a", TileSize.FULL)
        stories.moveTile(id, "a", 3)
        stories.resetLayout(id)
        val story = stories.currentState.story(id)!!
        assertEquals(listOf("a", "b", "c", "d", "e"), story.tiles.map { it.key })
        assertTrue(story.story.layout.isEmpty())
        assertEquals(TileSize.LARGE, story.tileDays.first().bands.first().tiles.first().size)
    }

    @Test
    fun groupingDropsThePhotosOwnLayoutAndAGroupHasItsOwn() = runTest {
        val stories = setup(FakeFileSystem())
        val id = stories.newStory()
        stories.setTileSize(id, "b", TileSize.FULL)
        val groupId = assertNotNull(stories.createGroup(id, listOf("b", "c")))
        assertNull(stories.currentState.story(id)!!.story.layout["b"])

        val key = groupTileKey(groupId)
        stories.setTileSize(id, key, TileSize.HALF)
        stories.setTileFocus(id, key, 0.1f, 0.1f)
        stories.setCover(id, groupId, "c")
        assertEquals(TileLayout(TileSize.HALF), stories.currentState.story(id)!!.story.layout[key])

        stories.ungroup(id, groupId)
        assertNull(stories.currentState.story(id)!!.story.layout[key])
    }
}
