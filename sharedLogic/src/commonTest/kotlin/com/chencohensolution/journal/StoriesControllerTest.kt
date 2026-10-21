package com.chencohensolution.journal

import com.chencohensolution.journal.stories.StoriesController
import com.chencohensolution.journal.stories.FilePhotoNoteStore
import com.chencohensolution.journal.stories.deleteOldStories
import com.chencohensolution.journal.stories.groupMoments
import com.chencohensolution.journal.settings.GroupingMode
import com.chencohensolution.journal.settings.TimelineGrouping
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
import kotlin.test.assertFalse
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

    private class Setup(val stories: StoriesController, val fileSystem: FakeFileSystem, val settings: SettingsController)

    private fun TestScope.setup(
        fileSystem: FakeFileSystem = FakeFileSystem(),
        settingsStore: KeyValueStore = StoriesTestStore(),
    ): Setup {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val library = LibraryController(StoriesTestLibrary(items), JournalSettings(StoriesTestStore()), dispatcher)
        library.refresh()
        var id = 0
        var minute = 0
        val settings = SettingsController(JournalSettings(settingsStore), dispatcher)
        val stories = StoriesController(
            noteStore = FilePhotoNoteStore(fileSystem, ROOT),
            library = library,
            settings = settings,
            dispatcher = dispatcher,
            now = { "2026-10-17T12:${(minute++).toString().padStart(2, '0')}:00Z" },
            newId = { "set-${++id}" },
        )
        advanceUntilIdle()
        return Setup(stories, fileSystem, settings)
    }

    @Test
    fun theStorylineHoldsWrittenPhotosNewestFirst() = runTest {
        val stories = setup().stories
        assertTrue(stories.currentState.storyline.isEmpty())

        stories.setWriting("b", "Coffee", "")
        stories.setWriting("e", "", "The long walk home")
        stories.addTag(listOf("c"), "Dog")
        assertEquals(listOf("e", "b"), stories.currentState.storyline.map { it.id })
    }

    @Test
    fun removingFromTheStorylineKeepsTheWriting() = runTest {
        val setup = setup()
        val stories = setup.stories
        stories.setWriting("b", "Coffee", "With Dana")
        stories.setInStoryline("b", false)
        assertTrue(stories.currentState.storyline.isEmpty())
        assertEquals("Coffee", stories.currentState.note("b")?.title)
        assertTrue("\"storyline\": false" in setup.fileSystem.read(ROOT / "media" / "b.json") { readUtf8() })

        // Kept after a reload, and put back by "Add to storyline".
        val reloaded = setup(setup.fileSystem).stories
        assertTrue(reloaded.currentState.storyline.isEmpty())
        reloaded.setInStoryline("b", true)
        assertEquals(listOf("b"), reloaded.currentState.storyline.map { it.id })
    }

    @Test
    fun clearingTheWritingTakesThePhotoOffTheStoryline() = runTest {
        val stories = setup().stories
        stories.setWriting("d", "Rain", "")
        stories.setWriting("d", "", "")
        assertTrue(stories.currentState.storyline.isEmpty())
    }

    @Test
    fun oldStoriesAreDeletedAndNotesKept() = runTest {
        val fileSystem = FakeFileSystem()
        fileSystem.createDirectories(ROOT / "stories" / "2026-10-17T12-52-22.257Z")
        fileSystem.write(ROOT / "stories" / "2026-10-17T12-52-22.257Z" / "story.json") { writeUtf8("{}") }
        fileSystem.createDirectories(ROOT / "events")
        setup(fileSystem).stories.setWriting("a", "Kept", "")

        deleteOldStories(fileSystem, ROOT)
        assertFalse(fileSystem.exists(ROOT / "stories"))
        assertFalse(fileSystem.exists(ROOT / "events"))
        assertTrue(fileSystem.exists(ROOT / "media" / "a.json"))
        deleteOldStories(fileSystem, ROOT)
    }

    @Test
    fun byGapAMomentRunsUntilALongPause() {
        // Gaps: a 9:00 → b 10:00 next day is 25 h; b → c 2 h; c 12:00 → d 8:00 next day is 20 h; d → e a week.
        val newestFirst = items.sortedByDescending { it.takenAtMillis }
        val moments = groupMoments(newestFirst, TimelineGrouping(GroupingMode.BY_GAP, 12))
        assertEquals(listOf(listOf("e"), listOf("d"), listOf("c", "b"), listOf("a")), moments.map { m -> m.items.map { it.id } })

        // c → d (20 h) joins with 24 h: one moment from Oct 8 to Oct 9.
        val joined = groupMoments(newestFirst, TimelineGrouping(GroupingMode.BY_GAP, 24))[1]
        assertEquals(listOf("d", "c", "b"), joined.items.map { it.id })
        assertEquals(OCT_8, joined.oldestDay)
        assertEquals(OCT_9, joined.newestDay)
    }

    @Test
    fun aMomentCanCrossMidnight() = runTest {
        val late = MediaItem("late", MediaType.IMAGE, OCT_8.toEpochDay() * 86_400_000 + 23 * 3_600_000L, OCT_8, null, 10, 10, 100, "late.jpg")
        val early = MediaItem("early", MediaType.IMAGE, OCT_9.toEpochDay() * 86_400_000 + 3_600_000L, OCT_9, null, 10, 10, 100, "early.jpg")
        val moments = groupMoments(listOf(early, late), TimelineGrouping(GroupingMode.BY_GAP, 12))
        assertEquals(listOf(listOf("early", "late")), moments.map { m -> m.items.map { it.id } })
        assertEquals(2, groupMoments(listOf(early, late), TimelineGrouping()).size)
    }

    @Test
    fun theGroupingSettingIsKept() = runTest {
        val store = StoriesTestStore()
        val first = setup(settingsStore = store).settings
        assertEquals(TimelineGrouping(GroupingMode.BY_DAY, 12), first.currentState.grouping)
        first.setGroupingMode(GroupingMode.BY_GAP)
        first.setGapHours(6)
        first.setGapHours(0)

        assertEquals(TimelineGrouping(GroupingMode.BY_GAP, 6), setup(settingsStore = store).settings.currentState.grouping)
    }
}
