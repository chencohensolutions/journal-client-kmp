package com.chencohensolution.journal

import com.chencohensolution.journal.stories.StoriesController
import com.chencohensolution.journal.stories.FilePhotoNoteStore
import com.chencohensolution.journal.stories.FileStoryStore
import com.chencohensolution.journal.stories.TimelineEntry
import com.chencohensolution.journal.library.LibraryController
import com.chencohensolution.journal.library.LocalDay
import com.chencohensolution.journal.library.MediaItem
import com.chencohensolution.journal.library.MediaLibrary
import com.chencohensolution.journal.library.MediaType
import com.chencohensolution.journal.library.PhotoAccess
import com.chencohensolution.journal.settings.JournalSettings
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

private class StoriesTestLibrary(var items: List<MediaItem>) : MediaLibrary {
    override fun access() = PhotoAccess.FULL
    override suspend fun loadItems() = items
}

private class StoriesTestStore : KeyValueStore {
    private val values = mutableMapOf<String, String>()
    override fun getString(key: String) = values[key]
    override fun putString(key: String, value: String?) {
        if (value == null) values.remove(key) else values[key] = value
    }
}

private fun photo(id: String, day: LocalDay, hour: Int) =
    MediaItem(id, MediaType.IMAGE, day.toEpochDay() * 86_400_000 + hour * 3_600_000L, day, null, 10, 10, 100, "$id.jpg")

private val OCT_7 = LocalDay(2026, 10, 7)
private val OCT_8 = LocalDay(2026, 10, 8)
private val OCT_9 = LocalDay(2026, 10, 9)
private val OCT_16 = LocalDay(2026, 10, 16)
private val ROOT = "/data/journal".toPath()

class LocalDayTest {
    @Test
    fun epochDaysRoundTrip() {
        assertEquals(0, LocalDay(1970, 1, 1).toEpochDay())
        assertEquals(LocalDay(2024, 3, 1), LocalDay(2024, 2, 28).plusDays(2))
        assertEquals(LocalDay(2027, 1, 1), LocalDay(2026, 12, 31).plusDays(1))
        val day = LocalDay(2026, 10, 8)
        assertEquals(day, LocalDay.fromEpochDay(day.toEpochDay()))
        assertEquals(day, LocalDay.parse("2026-10-08"))
        assertEquals("2026-10-08", day.toString())
        assertNull(LocalDay.parse("Oct 8"))
    }
}

class StoriesControllerTest {
    private val items = listOf(
        photo("a", OCT_7, 9),
        photo("b", OCT_8, 10),
        photo("c", OCT_8, 12),
        photo("d", OCT_9, 8),
        photo("e", OCT_16, 20),
    )

    private class Setup(val stories: StoriesController, val fileSystem: FakeFileSystem)

    private fun TestScope.setup(fileSystem: FakeFileSystem = FakeFileSystem()): Setup {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val library = LibraryController(StoriesTestLibrary(items), JournalSettings(StoriesTestStore()), dispatcher)
        library.refresh()
        var id = 0
        val stories = StoriesController(
            store = FileStoryStore(fileSystem, ROOT),
            noteStore = FilePhotoNoteStore(fileSystem, ROOT),
            library = library,
            dispatcher = dispatcher,
            now = { "2026-10-17T12:00:00Z" },
            newId = { "story-${++id}" },
        )
        advanceUntilIdle()
        return Setup(stories, fileSystem)
    }

    @Test
    fun everythingStartsUnsorted() = runTest {
        val state = setup().stories.currentState
        assertEquals(5, state.unsortedCount)
        assertEquals(listOf(OCT_16, OCT_9, OCT_8, OCT_7), state.timeline.map { (it as TimelineEntry.DayEntry).day.day })
    }

    @Test
    fun storyFromADayTakesItsPhotosOutOfUnsorted() = runTest {
        val stories = setup().stories
        val id = stories.createStoryFromDay("Arrival", OCT_8)
        advanceUntilIdle()

        val state = stories.currentState
        val story = assertNotNull(state.story(assertNotNull(id)))
        assertEquals(listOf("b", "c"), story.items.map { it.id })
        assertEquals(OCT_8, story.firstDay)
        assertEquals(1, story.dayCount)
        assertEquals(3, state.unsortedCount)
        // Newest first: Oct 16, Oct 9, the story (Oct 8), Oct 7.
        val entries = state.timeline
        assertIs<TimelineEntry.StoryEntry>(entries[2])
        assertEquals(OCT_7, (entries[3] as TimelineEntry.DayEntry).day.day)
    }

    @Test
    fun addingNeighborDaysStretchesTheDates() = runTest {
        val stories = setup().stories
        val id = assertNotNull(stories.createStoryFromDay("Trip", OCT_8))
        advanceUntilIdle()
        assertEquals(listOf(OCT_7, OCT_9), stories.currentState.neighborDays(id).map { it.day })

        stories.addDay(id, OCT_9)
        stories.addDay(id, OCT_7)
        advanceUntilIdle()

        val trip = assertNotNull(stories.currentState.story(id))
        assertEquals(OCT_7, trip.firstDay)
        assertEquals(OCT_9, trip.lastDay)
        assertEquals(3, trip.dayCount)
        assertEquals(listOf("a", "b", "c", "d"), trip.items.map { it.id })
        assertEquals(listOf(OCT_7, OCT_8, OCT_9), trip.tileDays.map { it.day })
    }

    @Test
    fun aPhotoCanBeInSeveralStories() = runTest {
        val stories = setup().stories
        val trip = assertNotNull(stories.createStory("Trip", listOf("b", "c")))
        val dinner = assertNotNull(stories.createStory("Dinner", listOf("c")))
        advanceUntilIdle()

        assertEquals(setOf(trip, dinner), stories.currentState.storiesContaining("c").map { it.id }.toSet())
        stories.removeItems(trip, listOf("c"))
        advanceUntilIdle()
        assertEquals(listOf(dinner), stories.currentState.storiesContaining("c").map { it.id })
    }

    @Test
    fun blankTitlesAndEmptyStoriesAreRefused() = runTest {
        val stories = setup().stories
        assertNull(stories.createStory("  ", listOf("a")))
        assertNull(stories.createStory("Nothing", listOf("not-on-device")))
        assertTrue(stories.currentState.stories.isEmpty())
    }

    @Test
    fun storiesAreSavedAsReadableJsonAndReloaded() = runTest {
        val fileSystem = FakeFileSystem()
        val first = setup(fileSystem).stories
        val id = assertNotNull(first.createStory("Japan trip", listOf("b")))
        first.rename(id, "Japan")

        val json = fileSystem.read(ROOT / "stories" / id / "story.json") { readUtf8() }
        assertTrue("\"title\": \"Japan\"" in json, json)
        assertTrue("\"dates\"" !in json, json)
        assertTrue("\"deviceId\": \"b\"" in json, json)
        assertTrue("\"fileName\": \"b.jpg\"" in json, json)

        val reloaded = setup(fileSystem).stories.currentState.story(id)
        assertEquals("Japan", reloaded?.title)
        assertEquals(listOf("b"), reloaded?.items?.map { it.id })
    }

    @Test
    fun deleteKeepsThePhotos() = runTest {
        val setup = setup()
        val id = assertNotNull(setup.stories.createStoryFromDay("Arrival", OCT_8))
        setup.stories.delete(id)
        advanceUntilIdle()

        assertTrue(setup.stories.currentState.stories.isEmpty())
        assertEquals(5, setup.stories.currentState.unsortedCount)
        assertTrue(!setup.fileSystem.exists(ROOT / "stories" / id))
    }

    @Test
    fun aDamagedFileDoesNotHideOtherStories() = runTest {
        val fileSystem = FakeFileSystem()
        val first = setup(fileSystem).stories
        val id = assertNotNull(first.createStory("Good", listOf("a")))
        fileSystem.createDirectories(ROOT / "stories" / "broken")
        fileSystem.write(ROOT / "stories" / "broken" / "story.json") { writeUtf8("{not json") }

        val stories = setup(fileSystem).stories.currentState.stories
        assertEquals(listOf(id), stories.map { it.id })
    }
}
