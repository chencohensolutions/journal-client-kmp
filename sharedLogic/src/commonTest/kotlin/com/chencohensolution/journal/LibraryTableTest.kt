package com.chencohensolution.journal

import com.chencohensolution.journal.backup.BackupState
import com.chencohensolution.journal.backup.LedgerEntry
import com.chencohensolution.journal.backup.UploadLedger
import com.chencohensolution.journal.library.GeoPoint
import com.chencohensolution.journal.library.LibraryController
import com.chencohensolution.journal.library.LibraryTable
import com.chencohensolution.journal.library.LocalDay
import com.chencohensolution.journal.library.MediaItem
import com.chencohensolution.journal.library.MediaLibrary
import com.chencohensolution.journal.library.MediaSource
import com.chencohensolution.journal.library.MediaType
import com.chencohensolution.journal.library.PhotoAccess
import com.chencohensolution.journal.library.StoredMediaLibrary
import com.chencohensolution.journal.library.mergeWithServer
import com.chencohensolution.journal.library.shown
import com.chencohensolution.journal.server.ServerMedia
import com.chencohensolution.journal.server.ServerMediaStore
import com.chencohensolution.journal.settings.JournalSettings
import com.chencohensolution.journal.settings.KeyValueStore
import com.chencohensolution.journal.settings.PrivateMedia
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private class TableTestLibrary(val items: List<MediaItem>) : MediaLibrary {
    override fun access() = PhotoAccess.FULL
    override suspend fun loadItems() = items
}

/** One made-up library: the phone's items, the ledger, the server's list, and what the user hides. */
private class Scenario(
    val device: List<MediaItem>,
    val entries: List<LedgerEntry>,
    val server: List<ServerMedia>,
    val hiddenOnServer: Set<String>,
    val showPrivate: Boolean,
    val kept: Map<String, String>,
    val scores: Map<String, Float>,
)

private fun sha(n: Int) = n.toString(16).padStart(64, '0')

private fun scenario(seed: Int): Scenario {
    val random = Random(seed)
    val base = LocalDay(2026, 10, 1).toEpochDay() * 86_400_000
    val device = (1..30).map { n ->
        MediaItem(
            id = n.toString(), type = if (n % 7 == 0) MediaType.VIDEO else MediaType.IMAGE, takenAtMillis = base + n * 3_600_000L,
            day = LocalDay.fromEpochDay((base + n * 3_600_000L) / 86_400_000), durationMillis = if (n % 7 == 0) 5_000 else null,
            width = 3000, height = 4000, sizeBytes = 1_000L + n, fileName = "IMG_$n.jpg", source = if (n % 3 == 0) MediaSource.WHATSAPP else MediaSource.CAMERA,
            location = if (n % 4 == 0) GeoPoint(32.0, 34.0 + n / 100.0) else null, modifiedMillis = 100L + n, mimeType = "image/jpeg",
        )
    }
    val entries = mutableListOf<LedgerEntry>()
    val server = mutableListOf<ServerMedia>()
    fun serverMedia(s: String, name: String, size: Long, n: Int, private: Boolean = false, audio: Boolean = false) = ServerMedia(
        sha256 = s, isVideo = false, fileName = name, mimeType = if (audio) "audio/mp4" else "image/heic", size = size,
        takenAt = "2026-09-${(10 + n % 18).toString().padStart(2, '0')}T12:00:00+03:00", width = if (n % 5 == 0) null else 1000, height = 800,
        latitude = if (n % 2 == 0) 31.5 else null, longitude = if (n % 2 == 0) 35.1 else null, source = listOf("camera", "whatsapp", null, "folder:Petcube")[n % 4],
        uploadedAt = "2026-10-20T10:00:00Z", isPrivate = private, aesthetic = if (n % 3 == 0) 5.5 + n / 10.0 else null, isAudio = audio,
    )
    device.forEach { item ->
        val n = item.id.toInt()
        when (random.nextInt(6)) {
            // Backed up: the ledger has it, the server too (sometimes private).
            0, 1 -> {
                entries += LedgerEntry(item.id, item.sizeBytes!!, item.modifiedMillis, sha(n), uploaded = true)
                server += serverMedia(sha(n), "renamed_$n.jpg", item.sizeBytes!!, n, private = random.nextInt(4) == 0)
            }
            // Read, but changed since: the entry no longer matches, the name and size still might.
            2 -> {
                entries += LedgerEntry(item.id, item.sizeBytes!! + 1, item.modifiedMillis, sha(n), uploaded = true)
                server += serverMedia(sha(n + 500), item.fileName!!, item.sizeBytes!!, n, private = random.nextInt(4) == 0)
            }
            // Not read by backup: on the server by name and size (another case), or not at all.
            3 -> server += serverMedia(sha(n + 500), item.fileName!!.uppercase(), item.sizeBytes!!, n)
            else -> Unit
        }
    }
    // Only on the server: some deleted from this phone (the ledger still has their old ids), and sound recordings.
    (100..125).forEach { n ->
        server += serverMedia(sha(n), "only_$n.jpg", 5_000L + n, n, private = random.nextInt(5) == 0, audio = n % 9 == 0)
        if (n % 4 == 0) entries += LedgerEntry("old$n", 5_000L + n, null, sha(n), uploaded = true)
    }
    val hidden = server.filter { random.nextInt(8) == 0 }.map { it.sha256 }.toSet() + sha(2) + sha(3)
    return Scenario(
        device = device,
        entries = entries,
        server = server.filterNot { it.sha256 in hidden },
        hiddenOnServer = hidden,
        showPrivate = random.nextBoolean(),
        kept = server.filter { random.nextInt(6) == 0 }.associate { it.sha256 to "/originals/${it.sha256}" },
        scores = server.filter { random.nextInt(4) == 0 }.associate { it.sha256 to 7.25f },
    )
}

class LibraryTableTest {
    @Test
    fun theTableIsTheMergeOfManyLibraries() = runTest {
        val seen = mutableMapOf<String, Int>()
        fun saw(case: String, items: Collection<MediaItem>, test: (MediaItem) -> Boolean) {
            seen[case] = (seen[case] ?: 0) + items.count(test)
        }
        repeat(40) { seed ->
            val s = scenario(seed)
            val database = testLibraryDatabase()
            StoredMediaLibrary(TableTestLibrary(s.device), database, Dispatchers.Unconfined).loadItems()
            val ledger = UploadLedger(FakeFileSystem(), "/backup".toPath(), database, Dispatchers.Unconfined)
            s.entries.forEach { ledger.record(it) }
            val store = ServerMediaStore(database)
            store.replaceAll(s.server, s.hiddenOnServer, cursor = null, showPrivate = s.showPrivate)
            val table = LibraryTable(database)
            table.refresh()

            val expected = mergeWithServer(
                s.device, s.entries.associateBy { it.id }, store.media().filterNot { it.isAudio }, s.kept, PrivateMedia(s.showPrivate),
                s.hiddenOnServer, s.scores, store.privateFiles(),
            ).associateBy { it.id }
            val actual = table.all().map { shown(it, s.kept, s.scores) }.associateBy { it.id }
            assertEquals(expected.keys, actual.keys, "seed $seed: ids")
            expected.forEach { (id, item) -> assertEquals(item, actual.getValue(id), "seed $seed: $id") }
            val items = expected.values
            saw("private on the phone", items) { it.isOnDevice && it.isPrivate }
            saw("private on the server", items) { !it.isOnDevice && it.isPrivate }
            saw("deleted from the phone", items) { !it.isOnDevice && it.id.startsWith("old") }
            saw("server only", items) { !it.isOnDevice && it.id.startsWith("server:") }
            saw("kept original", items) { it.keptOriginal != null }
            saw("scored", items) { it.aesthetic != null }
            saw("read by backup", items) { it.isOnDevice && it.sha256 != null }
            // Fewer items than the phone and the server have between them: some matched, hidden or left out.
            if (s.device.size + s.server.count { !it.isAudio } > items.size) seen["matched or left out"] = (seen["matched or left out"] ?: 0) + 1
        }
        seen.forEach { (case, count) -> assertTrue(count > 0, "no case of: $case") }
    }

    @Test
    fun theControllerPatchesOnlyTheDayThatChanged() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val device = (0..8).map { n ->
            val day = LocalDay(2026, 10, 1 + n / 3)
            MediaItem("$n", MediaType.IMAGE, day.toEpochDay() * 86_400_000 + n * 60_000L, day, null, 10, 10, 100L + n, "IMG_$n.jpg", modifiedMillis = 1)
        }
        val database = testLibraryDatabase()
        val backup = MutableStateFlow(BackupState())
        val store = object : KeyValueStore {
            override fun getString(key: String): String? = null
            override fun putString(key: String, value: String?) {}
        }
        val controller = LibraryController(
            StoredMediaLibrary(TableTestLibrary(device), database, dispatcher), JournalSettings(store),
            dispatcher, backup = backup, table = LibraryTable(database),
        )
        controller.refresh()
        advanceUntilIdle()
        val before = controller.currentState.days
        assertEquals(listOf(LocalDay(2026, 10, 3), LocalDay(2026, 10, 2), LocalDay(2026, 10, 1)), before.map { it.day })
        assertEquals(listOf("2", "1", "0"), before.last().items.map { it.id })

        // Backup reads photo 4 (Oct 2): only that day is made again.
        val entry = LedgerEntry("4", 104, 1, sha(4))
        UploadLedger(FakeFileSystem(), "/backup".toPath(), database, Dispatchers.Unconfined).record(entry)
        backup.value = backup.value.copy(entries = mapOf("4" to entry))
        advanceUntilIdle()
        val after = controller.currentState.days
        assertEquals(sha(4), after[1].items.first { it.id == "4" }.sha256)
        assertTrue(after[0] === before[0] && after[2] === before[2], "the other days are the same objects")
        controller.close()
    }

    @Test
    fun aRefreshReturnsOnlyWhatChanged() = runTest {
        val s = scenario(7)
        val database = testLibraryDatabase()
        StoredMediaLibrary(TableTestLibrary(s.device), database, Dispatchers.Unconfined).loadItems()
        val ledger = UploadLedger(FakeFileSystem(), "/backup".toPath(), database, Dispatchers.Unconfined)
        s.entries.forEach { ledger.record(it) }
        ServerMediaStore(database).replaceAll(s.server, s.hiddenOnServer, cursor = null, showPrivate = true)
        val table = LibraryTable(database)
        val first = table.refresh()
        assertTrue(first.changed.size > 40, "${first.changed.size}")
        assertTrue(table.refresh().isEmpty)

        // A photo backup reads: one row changes (it learns its sha256), nothing else.
        val item = s.device.first { id -> s.entries.none { it.id == id.id } }
        ledger.record(LedgerEntry(item.id, item.sizeBytes!!, item.modifiedMillis, sha(9_999)))
        assertTrue(table.affects(listOf(item.id), listOf(sha(9_999))))
        val delta = table.refresh()
        assertEquals(listOf(item.id), delta.changed.map { it.item.id })
        assertEquals(sha(9_999), delta.changed.single().item.sha256)

        // A call backup reads: nothing of the library.
        ledger.record(LedgerEntry("sound:call1", 10, null, sha(8_888), uploaded = true))
        assertTrue(!table.affects(listOf("sound:call1"), listOf(sha(8_888))))
        assertTrue(table.refresh().isEmpty)
    }
}
