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
import com.chencohensolution.journal.stories.CropHandle
import com.chencohensolution.journal.stories.CropKind
import com.chencohensolution.journal.stories.CropRect
import com.chencohensolution.journal.stories.FilePhotoNoteStore
import com.chencohensolution.journal.stories.MAX_CROP_RATIO
import com.chencohensolution.journal.stories.MIN_CROP_SIZE
import com.chencohensolution.journal.stories.PhotoCrops
import com.chencohensolution.journal.stories.PhotoNote
import com.chencohensolution.journal.stories.StoriesController
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val PHOTO = 4f / 3f
private const val SCREEN = 9f / 19.5f
private val CROPS_ROOT = "/data/journal".toPath()

private fun near(expected: Float, actual: Float, message: String = "") =
    assertTrue(abs(expected - actual) < 0.001f, "$message expected $expected, was $actual")

private fun CropRect.inside() = x >= -0.0001f && y >= -0.0001f && x + w <= 1.0001f && y + h <= 1.0001f

class CropsTest {
    @Test
    fun theLargestCropOfAShapeStartsOnTheCropAlreadyMade() {
        val portrait = CropRect.largest(SCREEN, PHOTO)
        near(1f, portrait.h)
        near(SCREEN, portrait.ratioOn(PHOTO), "shape")
        near(0.5f, portrait.x + portrait.w / 2, "centered")
        assertEquals(SCREEN, portrait.aspect)

        // Around a Moments crop on the right: as far right as the photo allows.
        val right = CropRect.largest(SCREEN, PHOTO, around = CropRect(0.7f, 0.2f, 0.3f, 0.3f))
        near(1f, right.x + right.w)
        assertTrue(right.inside())
    }

    @Test
    fun movingAndPinchingStayOnThePhotoAndKeepTheShape() {
        val crop = CropRect(0.2f, 0.2f, 0.4f, 0.4f)
        assertEquals(CropRect(0.6f, 0f, 0.4f, 0.4f), crop.moved(0.9f, -0.5f))

        val larger = crop.scaled(10f)
        near(1f, maxOf(larger.w, larger.h))
        near(1f, larger.w / larger.h, "shape")
        assertTrue(larger.inside())

        val smaller = crop.scaled(0.01f)
        near(MIN_CROP_SIZE, minOf(smaller.w, smaller.h))
    }

    @Test
    fun aFreeCropKeepsItsSideAndALimitedShape() {
        val crop = CropRect(0.2f, 0.2f, 0.4f, 0.4f)
        // The right edge out: the left stays.
        val wider = crop.resized(CropHandle.RIGHT, 0.2f, 0f, PHOTO)
        near(0.2f, wider.x)
        near(0.6f, wider.w)
        near(0.4f, wider.h)

        // Squashed to a strip: held at 3:1.
        val strip = crop.resized(CropHandle.BOTTOM, 0f, -0.3f, PHOTO)
        near(MAX_CROP_RATIO, strip.ratioOn(PHOTO), "shape")
        assertTrue(strip.inside())
    }

    @Test
    fun aLockedCornerKeepsTheScreenShape() {
        val crop = CropRect.largest(SCREEN, PHOTO).scaled(0.5f)
        val dragged = crop.resized(CropHandle.TOP_LEFT, -0.1f, 0.3f, PHOTO, lockedRatio = SCREEN)
        near(SCREEN, dragged.ratioOn(PHOTO), "shape")
        // The opposite corner stays.
        near(crop.x + crop.w, dragged.x + dragged.w)
        near(crop.y + crop.h, dragged.y + dragged.h)
        assertTrue(dragged.inside())
    }

    @Test
    fun aCropForAnotherScreenKeepsItsCenterAndHeight() {
        val made = CropRect.largest(SCREEN, PHOTO).scaled(0.6f)
        val other = 9f / 16f
        val fitted = made.fittedTo(other, PHOTO)
        near(made.h, fitted.h)
        near(other, fitted.ratioOn(PHOTO), "shape")
        near(made.x + made.w / 2, fitted.x + fitted.w / 2, "center")
        assertEquals(other, fitted.aspect)
        // The screen it was made for: unchanged.
        assertEquals(made, made.fittedTo(SCREEN, PHOTO))
    }

    @Test
    fun cropsAreSavedInTheNoteFileWithoutLosingNewerFields() {
        val fileSystem = FakeFileSystem()
        val store = FilePhotoNoteStore(fileSystem, CROPS_ROOT)
        val path = CROPS_ROOT / "media" / "p1.json"
        fileSystem.createDirectories(path.parent!!)
        // Written by a newer version: a field this one doesn't know, also inside "crops".
        fileSystem.write(path) {
            writeUtf8("""{"v":3,"deviceId":"p1","takenAt":1,"caption":"old","rating":5,"crops":{"story":{"x":0,"y":0,"w":1,"h":1}},"updatedAt":"a"}""")
        }
        val crops = PhotoCrops(moments = CropRect(0f, 0.1f, 1f, 0.5f), portrait = CropRect(0.3f, 0f, 0.35f, 1f, SCREEN))
        store.save(PhotoNote("p1", "p1.jpg", 1, caption = "new", updatedAt = "b", crops = crops))

        val text = fileSystem.read(path) { readUtf8() }
        assertTrue("\"rating\"" in text && "\"story\"" in text, text)
        val loaded = store.loadAll().single()
        assertEquals("new", loaded.caption)
        assertEquals(crops, loaded.crops)

        // Clearing the crops keeps the unknown one only.
        store.save(loaded.copy(crops = PhotoCrops()))
        assertTrue(store.loadAll().single().crops.isEmpty)
        assertTrue("\"story\"" in fileSystem.read(path) { readUtf8() })
    }

    @Test
    fun aPhotoWithOnlyACropKeepsItsNoteAndStaysUnsorted() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val day = LocalDay(2026, 10, 1)
        val photo = MediaItem("p1", MediaType.IMAGE, day.toEpochDay() * 86_400_000, day, null, 4000, 3000, 100, "p1.jpg")
        val library = LibraryController(CropsTestLibrary(listOf(photo)), JournalSettings(CropsTestStore()), dispatcher)
        library.refresh()
        val fileSystem = FakeFileSystem()
        val stories = StoriesController(
            noteStore = FilePhotoNoteStore(fileSystem, CROPS_ROOT),
            library = library,
            settings = SettingsController(JournalSettings(CropsTestStore()), dispatcher),
            newId = { "story-1" },
            dispatcher = dispatcher,
            now = { "2026-10-19T12:00:00Z" },
        )
        advanceUntilIdle()

        val crops = PhotoCrops().with(CropKind.MOMENTS, CropRect(0f, 0.2f, 1f, 0.6f))
        stories.setCrops("p1", crops)
        advanceUntilIdle()
        assertEquals(crops, stories.currentState.note("p1")?.crops)
        // A crop alone doesn't put a photo on the storyline.
        assertTrue(stories.currentState.storyline.isEmpty())
        assertTrue(fileSystem.exists(CROPS_ROOT / "media" / "p1.json"))

        stories.setCrops("p1", PhotoCrops())
        advanceUntilIdle()
        assertNull(stories.currentState.note("p1"))
        assertFalse(fileSystem.exists(CROPS_ROOT / "media" / "p1.json"))
    }
}

private class CropsTestLibrary(val items: List<MediaItem>) : MediaLibrary {
    override fun access() = PhotoAccess.FULL
    override suspend fun loadItems() = items
}

private class CropsTestStore : KeyValueStore {
    private val values = mutableMapOf<String, String>()
    override fun getString(key: String) = values[key]
    override fun putString(key: String, value: String?) {
        if (value == null) values.remove(key) else values[key] = value
    }
}
