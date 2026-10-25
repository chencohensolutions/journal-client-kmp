package com.chencohensolution.journal

import com.chencohensolution.journal.backup.BackupActivity
import com.chencohensolution.journal.backup.BackupProgress
import com.chencohensolution.journal.backup.BackupSettings
import com.chencohensolution.journal.backup.BackupState
import com.chencohensolution.journal.backup.backupQueue
import com.chencohensolution.journal.backup.LedgerEntry
import com.chencohensolution.journal.library.LibraryController
import com.chencohensolution.journal.library.LibraryTable
import com.chencohensolution.journal.library.StoredMediaLibrary
import com.chencohensolution.journal.library.MadeUpLibrary
import com.chencohensolution.journal.library.MediaItem
import com.chencohensolution.journal.library.MediaLibrary
import com.chencohensolution.journal.library.PhotoAccess
import com.chencohensolution.journal.library.mergeWithServer
import com.chencohensolution.journal.settings.JournalSettings
import com.chencohensolution.journal.settings.KeyValueStore
import com.chencohensolution.journal.settings.SettingsController
import com.chencohensolution.journal.stories.FilePhotoNoteStore
import com.chencohensolution.journal.stories.StoriesController
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private class LoadTestLibrary(val items: List<MediaItem>) : MediaLibrary {
    override fun access() = PhotoAccess.FULL
    override suspend fun loadItems() = items
}

private class LoadTestStore : KeyValueStore {
    private val values = mutableMapOf<String, String>()
    override fun getString(key: String) = values[key]
    override fun putString(key: String, value: String?) {
        if (value == null) values.remove(key) else values[key] = value
    }
}

/** The made-up library, and how often a large library is merged and Moments rebuilt (docs/plans/library-on-disk.md). */
class LibraryLoadTest {
    @Test
    fun madeUpLibraryIsTheSameEveryTime() {
        val first = MadeUpLibrary(10_000)
        val again = MadeUpLibrary(10_000)
        assertEquals(10_000, first.serverMedia.size)
        assertEquals(5_000, first.deviceItems.size)
        assertEquals(first.deviceItems, again.deviceItems)
        assertEquals(first.serverMedia, again.serverMedia)
        assertEquals(10_000, first.serverMedia.map { it.sha256 }.toSet().size)
        assertTrue(first.serverMedia.all { it.sha256.length == 64 && it.sha256.startsWith(MadeUpLibrary.SHA_PREFIX) })
        assertTrue(first.deviceItems.all { it.id.toLong() >= MadeUpLibrary.FIRST_ID && it.location != null })
        // Fifteen years, newest first.
        val days = first.deviceItems.map { it.day }
        assertTrue(days.first().year == 2026 && days.last().year in 2011..2012)
        assertEquals(days.sortedDescending(), days)
    }

    @Test
    fun backupNeverTakesMadeUpPhotos() {
        val madeUp = MadeUpLibrary(1_000)
        val real = madeUp.deviceItems.first().copy(id = "1234", fileName = "IMG_real.jpg")
        val everySource = BackupSettings(sources = madeUp.deviceItems.map { it.source }.toSet() + real.source)
        assertEquals(listOf(real), backupQueue(madeUp.deviceItems + real, everySource))
        // Nor a made-up server file shown as if on the phone.
        val shownFromServer = real.copy(sha256 = madeUp.serverMedia.first().sha256)
        assertEquals(emptyList(), backupQueue(listOf(shownFromServer), everySource))
    }

    @Test
    fun madeUpDevicePhotosAreTheServersOwn() {
        val madeUp = MadeUpLibrary(10_000)
        // Matched by file name and size: one item each, 5,000 on the phone and 5,000 only on the server.
        val merged = mergeWithServer(madeUp.deviceItems, emptyMap(), madeUp.serverMedia)
        assertEquals(10_000, merged.size)
        assertEquals(5_000, merged.count { it.isOnDevice })
    }

    /**
     * Backing up 2,000 small files (calls, one a second, none of them a photo of the library) on a library of 20,000:
     * how many merges of the whole library and rebuilds of Moments it starts. The goal of the library on disk is a
     * handful of each. Measured Oct 25, 2026 before the library was in the database: 2,001 merges (one per file; the app
     * ran out of memory doing this). With the library in the database (phase 4), a ledger change that touches no photo
     * makes nothing again.
     */
    @Test
    fun backupOfManySmallFilesMergesTheLibraryFewTimes() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val madeUp = MadeUpLibrary(20_000)
        val backup = MutableStateFlow(BackupState())
        val database = testLibraryDatabase()
        val library = LibraryController(
            StoredMediaLibrary(LoadTestLibrary(madeUp.deviceItems), database, dispatcher), JournalSettings(LoadTestStore()), dispatcher,
            backup = backup, table = LibraryTable(database),
        )
        val settings = SettingsController(JournalSettings(LoadTestStore()), dispatcher)
        val stories = StoriesController(
            noteStore = FilePhotoNoteStore(FakeFileSystem(), "/data/journal".toPath()),
            library = library,
            settings = settings,
            newId = { "id" },
            dispatcher = dispatcher,
        )
        library.refresh()
        advanceUntilIdle()
        val mergesBefore = library.merges
        val rebuildsBefore = stories.rebuilds
        val recountsBefore = library.recounts

        repeat(FILES) { n ->
            val id = "call/$n"
            val progress = BackupProgress(done = n, total = FILES, bytesDone = n * 50_000L, bytesTotal = FILES * 50_000L, currentId = id)
            backup.update { it.copy(activity = BackupActivity.Running(progress)) }
            advanceTimeBy(500)
            backup.update { it.copy(entries = it.entries + (id to LedgerEntry(id, 50_000, 0, n.toString(16).padStart(64, '0'), uploaded = true))) }
            advanceTimeBy(500)
        }
        backup.update { it.copy(activity = BackupActivity.Idle) }
        advanceUntilIdle()

        val merges = library.merges - mergesBefore
        val rebuilds = stories.rebuilds - rebuildsBefore
        println("Backup of $FILES small files: $merges merges, $rebuilds Moments rebuilds")
        assertEquals(10_000, library.currentState.itemCount)
        assertTrue(merges <= FEW, "$merges merges of the library")
        assertTrue(rebuilds <= FEW, "$rebuilds rebuilds of Moments")
        // Backup's counts come from the database (step 6d): a call's ledger entry counts nothing again.
        assertTrue(library.recounts - recountsBefore <= FEW, "${library.recounts - recountsBefore} counts of the library")
        assertEquals(10_000, library.currentState.notBackedUpCount)
        stories.close()
        settings.close()
        library.close()
    }

    private companion object {
        const val FILES = 2_000
        const val FEW = 5

    }
}
