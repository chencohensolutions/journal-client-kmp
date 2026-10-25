package com.chencohensolution.journal

import com.chencohensolution.journal.backup.LedgerEntry
import com.chencohensolution.journal.library.ChangingLibrary
import com.chencohensolution.journal.library.GeoPoint
import com.chencohensolution.journal.library.LibraryChanges
import com.chencohensolution.journal.library.LocalDay
import com.chencohensolution.journal.library.MediaItem
import com.chencohensolution.journal.library.MediaLibrary
import com.chencohensolution.journal.library.MediaSource
import com.chencohensolution.journal.library.MediaType
import com.chencohensolution.journal.library.PhotoAccess
import com.chencohensolution.journal.library.StoredMediaLibrary
import com.chencohensolution.journal.library.hiddenOnPhone
import com.chencohensolution.journal.server.PrivateFile
import com.chencohensolution.journal.settings.PrivateMedia
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** A platform library that says what changed since its last read: each item's generation, as MediaStore's. */
private class ChangingTestLibrary : ChangingLibrary {
    val items = LinkedHashMap<String, Pair<MediaItem, Long>>()
    var generation = 0L
    val asked = mutableListOf<String?>()

    fun put(item: MediaItem) {
        items[item.id] = item to ++generation
    }

    override fun access() = PhotoAccess.FULL
    override suspend fun loadItems() = items.values.map { it.first }
    override suspend fun changesSince(mark: String?): LibraryChanges {
        asked += mark
        val since = mark?.toLong()
        val changed = items.values.filter { since == null || it.second > since }.map { it.first }
        return LibraryChanges(generation.toString(), full = since == null, changed = changed, present = items.keys.toSet())
    }

    override suspend fun items(ids: Collection<String>) = ids.mapNotNull { items[it]?.first }
}

private class WholeTestLibrary(var items: List<MediaItem>) : MediaLibrary {
    override fun access() = PhotoAccess.FULL
    override suspend fun loadItems() = items
}

private fun photo(id: String, hour: Int, name: String = "$id.jpg", size: Long = 100) =
    MediaItem(id, MediaType.IMAGE, LocalDay(2026, 10, 8).toEpochDay() * 86_400_000 + hour * 3_600_000L, LocalDay(2026, 10, 8), null, 3000, 4000, size, name)

class StoredMediaLibraryTest {
    @Test
    fun readsOnlyWhatChangedAndDropsWhatWasDeleted() = runTest {
        val platform = ChangingTestLibrary().apply { put(photo("1", 9)); put(photo("2", 10)) }
        val stored = StoredMediaLibrary(platform, testLibraryDatabase(), StandardTestDispatcher(testScheduler))
        assertEquals(setOf("1", "2"), stored.loadItems().map { it.id }.toSet())

        platform.put(photo("3", 11))
        platform.put(photo("1", 9).copy(fileName = "edited.jpg"))
        platform.items.remove("2")
        assertEquals(mapOf("1" to "edited.jpg", "3" to "3.jpg"), stored.loadItems().associate { it.id to it.fileName })
        assertEquals(listOf(null, "2"), platform.asked)
    }

    @Test
    fun keepsEveryFieldItReads() = runTest {
        val item = MediaItem(
            id = "7", type = MediaType.VIDEO, takenAtMillis = 1_760_000_000_000, day = LocalDay(2025, 10, 9), durationMillis = 4_200,
            width = 1080, height = 1920, sizeBytes = 9_000_000, fileName = "VID_1.mp4", burstId = "samsung-20251009_101010",
            source = MediaSource.folder("Petcube"), location = GeoPoint(32.08, 34.78), modifiedMillis = 1_760_000_001_000, mimeType = "video/mp4",
        )
        val stored = StoredMediaLibrary(WholeTestLibrary(listOf(item)), testLibraryDatabase(), StandardTestDispatcher(testScheduler))
        assertEquals(listOf(item), stored.loadItems())
    }

    @Test
    fun aLibraryThatCantSayWhatChangedIsReadWhole() = runTest {
        val platform = WholeTestLibrary(listOf(photo("1", 9), photo("2", 10)))
        val stored = StoredMediaLibrary(platform, testLibraryDatabase(), StandardTestDispatcher(testScheduler))
        stored.loadItems()
        platform.items = listOf(photo("2", 10))
        assertEquals(listOf("2"), stored.loadItems().map { it.id })
    }

    @Test
    fun whatTheUserHidesHasNoRowAndComesBackWhenShown() = runTest {
        val platform = ChangingTestLibrary().apply { put(photo("1", 9)); put(photo("secret", 10)) }
        val stored = StoredMediaLibrary(platform, testLibraryDatabase(), StandardTestDispatcher(testScheduler))
        stored.loadItems()

        stored.leaveOut { it.id == "secret" }
        assertEquals(listOf("1"), stored.loadItems().map { it.id })
        // Changed on the phone while hidden: still out.
        platform.put(photo("secret", 10).copy(fileName = "still.jpg"))
        assertEquals(listOf("1"), stored.loadItems().map { it.id })

        stored.leaveOut { false }
        assertEquals(setOf("1", "secret"), stored.loadItems().map { it.id }.toSet())
        assertEquals("still.jpg", stored.loadItems().first { it.id == "secret" }.fileName)
    }

    @Test
    fun thePhoneHidesItsCopiesOfHiddenAndPrivateFiles() {
        val read = photo("1", 9, "IMG_1.jpg", 999)
        val notRead = photo("2", 10, "IMG_2.jpg", 555)
        val other = photo("3", 11)
        val entries = mapOf("1" to LedgerEntry("1", 999, null, "b".repeat(64), uploaded = true))
        val private = listOf(PrivateFile("d".repeat(64), "IMG_2.jpg", 555))

        val byServer = hiddenOnPhone(entries, setOf("b".repeat(64)), emptyList(), PrivateMedia())
        assertTrue(byServer(read))
        assertFalse(byServer(notRead))

        val byPrivacy = hiddenOnPhone(entries, emptySet(), private, PrivateMedia())
        assertTrue(byPrivacy(notRead))
        assertFalse(byPrivacy(other))
        // Shown: nothing is left out.
        assertFalse(hiddenOnPhone(entries, emptySet(), private, PrivateMedia(show = true))(notRead))
    }
}
