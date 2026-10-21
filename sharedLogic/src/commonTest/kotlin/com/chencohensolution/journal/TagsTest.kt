package com.chencohensolution.journal

import com.chencohensolution.journal.library.LibraryController
import com.chencohensolution.journal.library.LocalDay
import com.chencohensolution.journal.library.MediaItem
import com.chencohensolution.journal.library.MediaLibrary
import com.chencohensolution.journal.library.MediaType
import com.chencohensolution.journal.library.PhotoAccess
import com.chencohensolution.journal.settings.JournalSettings
import com.chencohensolution.journal.settings.SettingsController
import com.chencohensolution.journal.settings.KeyValueStore
import com.chencohensolution.journal.stories.FilePhotoNoteStore
import com.chencohensolution.journal.stories.StoriesController
import com.chencohensolution.journal.stories.matches
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class TagsTestLibrary(val items: List<MediaItem>) : MediaLibrary {
    override fun access() = PhotoAccess.FULL
    override suspend fun loadItems() = items
}

private class TagsTestStore : KeyValueStore {
    private val values = mutableMapOf<String, String>()
    override fun getString(key: String) = values[key]
    override fun putString(key: String, value: String?) {
        if (value == null) values.remove(key) else values[key] = value
    }
}

private fun dayPhoto(id: String, day: LocalDay) =
    MediaItem(id, MediaType.IMAGE, day.toEpochDay() * 86_400_000 + 12 * 3_600_000L, day, null, 10, 10, 100, "$id.jpg")

private val JAN_1 = LocalDay(2026, 1, 1)
private val MAY_5 = LocalDay(2026, 5, 5)
private val OCT_1 = LocalDay(2026, 10, 1)
private val OCT_3 = LocalDay(2026, 10, 3)
private val OCT_5 = LocalDay(2026, 10, 5)
private val OCT_20 = LocalDay(2026, 10, 20)
private val TAGS_ROOT = "/data/journal".toPath()

class TagsTest {
    private val items = listOf(
        dayPhoto("jan", JAN_1),
        dayPhoto("may", MAY_5),
        dayPhoto("o1", OCT_1),
        dayPhoto("o3", OCT_3),
        dayPhoto("o5", OCT_5),
        dayPhoto("o20", OCT_20),
    )

    private class Setup(val stories: StoriesController, val fileSystem: FakeFileSystem)

    private fun TestScope.setup(fileSystem: FakeFileSystem = FakeFileSystem()): Setup {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val library = LibraryController(TagsTestLibrary(items), JournalSettings(TagsTestStore()), dispatcher)
        library.refresh()
        var id = 0
        var minute = 0
        val stories = StoriesController(
            noteStore = FilePhotoNoteStore(fileSystem, TAGS_ROOT),
            library = library,
            settings = SettingsController(JournalSettings(TagsTestStore()), dispatcher),
            newId = { "set-${++id}" },
            dispatcher = dispatcher,
            now = { "2026-10-17T12:${(minute++).toString().padStart(2, '0')}:00Z" },
        )
        advanceUntilIdle()
        return Setup(stories, fileSystem)
    }

    @Test
    fun taggedPhotosAreFoundByTag() = runTest {
        val stories = setup().stories
        assertEquals("Receipts", stories.addTag(listOf("o1", "o3"), "  Receipts "))
        stories.setWriting("o5", "", "Only a caption")
        val state = stories.currentState
        assertEquals(listOf("o3", "o1"), state.tagged("receipts").flatMap { day -> day.items.map { it.id } })
        assertEquals("Only a caption", state.note("o5")?.caption)
    }

    @Test
    fun tagsReuseTheFirstSpellingAndCount() = runTest {
        val stories = setup().stories
        stories.addTag(listOf("o1"), "Dog")
        assertEquals("Dog", stories.addTag(listOf("o3", "o1"), "dog"))
        val tag = stories.currentState.tags.single()
        assertEquals("Dog", tag.name)
        assertEquals(2, tag.count)
        assertEquals("o3", tag.latest?.id)
        assertNull(stories.addTag(listOf("o1"), "   "))
    }

    @Test
    fun removingTheLastTagAndCaptionDeletesTheNote() = runTest {
        val setup = setup()
        val stories = setup.stories
        stories.addTag(listOf("o1"), "Dog")
        stories.setWriting("o1", "", "Good boy")
        assertTrue(setup.fileSystem.exists(TAGS_ROOT / "media" / "o1.json"))

        stories.removeTag(listOf("o1"), "DOG")
        stories.setWriting("o1", "", "")
        assertNull(stories.currentState.note("o1"))
        assertFalse(setup.fileSystem.exists(TAGS_ROOT / "media" / "o1.json"))
    }

    @Test
    fun notesSurviveAReload() = runTest {
        val fileSystem = FakeFileSystem()
        val first = setup(fileSystem).stories
        first.addTag(listOf("o1"), "Dog")
        first.setWriting("o1", "", "Good boy")
        val note = assertNotNull(setup(fileSystem).stories.currentState.note("o1"))
        assertEquals(listOf("Dog"), note.tags)
        assertEquals("Good boy", note.caption)
        assertEquals("o1.jpg", note.fileName)
    }

    @Test
    fun searchMatchesPartsAndSmallTypos() = runTest {
        val stories = setup().stories
        stories.addTag(listOf("o1"), "Kyoto with Dana")
        stories.addTag(listOf("o3"), "Birthday")
        assertEquals(listOf("Kyoto with Dana"), stories.currentState.searchTags("kyotp").map { it.name })
        assertTrue(matches("Japan trip", "JAP"))
        assertFalse(matches("Japan trip", "dog"))
    }
}
