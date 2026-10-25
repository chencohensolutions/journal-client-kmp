package com.chencohensolution.journal

import com.chencohensolution.journal.backup.LedgerEntry
import com.chencohensolution.journal.backup.MediaFiles
import com.chencohensolution.journal.backup.SoundFolder
import com.chencohensolution.journal.backup.SoundFolders
import com.chencohensolution.journal.backup.SoundIds
import com.chencohensolution.journal.library.AudioKind
import com.chencohensolution.journal.storage.SoundRemoval
import com.chencohensolution.journal.backup.UploadLedger
import com.chencohensolution.journal.library.LocalDay
import com.chencohensolution.journal.library.MediaItem
import com.chencohensolution.journal.library.MediaLibrary
import com.chencohensolution.journal.library.MediaType
import com.chencohensolution.journal.library.PhotoAccess
import com.chencohensolution.journal.settings.JournalSettings
import com.chencohensolution.journal.settings.KeyValueStore
import com.chencohensolution.journal.storage.FreeUpChecker
import com.chencohensolution.journal.storage.FreeUpController
import com.chencohensolution.journal.storage.FreeUpGroup
import com.chencohensolution.journal.storage.FreeUpRule
import com.chencohensolution.journal.storage.KeptReason
import com.chencohensolution.journal.storage.PhotoTrash
import com.chencohensolution.journal.storage.ServerCheck
import com.chencohensolution.journal.storage.ServerVerdict
import com.chencohensolution.journal.storage.freeUpPlan
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import okio.Buffer
import okio.ByteString.Companion.toByteString
import okio.Path.Companion.toPath
import okio.Source
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val DAY = 86_400_000L
private const val NOW = 1_000 * DAY
private const val GB = 1L shl 30

private fun item(id: String, ageDays: Long, size: Long, name: String = "IMG_$id.jpg", type: MediaType = MediaType.IMAGE, plusMillis: Long = 0) =
    MediaItem(id, type, NOW - ageDays * DAY + plusMillis, LocalDay(2026, 10, 20), null, 4000, 3000, size, name, modifiedMillis = 7)

private fun uploaded(item: MediaItem, sha: String = "sha-${item.id}") =
    LedgerEntry(item.id, item.sizeBytes!!, item.modifiedMillis, sha, uploaded = true)

private class Files : MediaFiles {
    val bytes = mutableMapOf<String, ByteArray>()
    override fun open(item: MediaItem, offset: Long): Source = Buffer().write(bytes.getValue(item.id))
    override fun takenAt(item: MediaItem) = "2026-10-20T10:00:00+03:00"
}

private class Server : ServerCheck {
    val verdicts = mutableMapOf<String, ServerVerdict>()
    val asked = mutableListOf<String>()
    var offline = false
    override suspend fun verify(sha256: String): ServerVerdict {
        if (offline) throw okio.IOException("connection refused")
        asked += sha256
        return verdicts[sha256] ?: ServerVerdict.OK
    }
}

private class Library(var items: List<MediaItem>) : MediaLibrary {
    override fun access() = PhotoAccess.FULL
    override suspend fun loadItems() = items
}

private class Store : KeyValueStore {
    val values = mutableMapOf<String, String>()
    override fun getString(key: String) = values[key]
    override fun putString(key: String, value: String?) {
        if (value == null) values.remove(key) else values[key] = value
    }
}

private class Trash(var allowed: Boolean) : PhotoTrash {
    val trashed = mutableListOf<String>()
    override fun canTrashWithoutAsking() = allowed
    val requests = mutableListOf<Int>()
    override suspend fun trash(items: List<MediaItem>): Set<String> {
        requests += items.size
        trashed += items.map { it.id }
        return items.mapTo(mutableSetOf()) { it.id }
    }
}

class FreeUpTest {
    @Test
    fun olderThanTakesOnlyBackedUpPhotosTakenBeforeThenAndKeepsPairsTogether() {
        val old = item("old", ageDays = 400, size = 10)
        val recent = item("recent", ageDays = 10, size = 10)
        val notBackedUp = item("mine", ageDays = 500, size = 10)
        val changed = item("changed", ageDays = 500, size = 10)
        // A RAW and its JPEG: only the JPEG is backed up, so neither leaves.
        val raw = item("raw", ageDays = 600, size = 30, name = "PXL_1.dng")
        val jpeg = item("jpeg", ageDays = 600, size = 5, name = "PXL_1.jpg", plusMillis = 800)
        // Both backed up: they leave together.
        val raw2 = item("raw2", ageDays = 700, size = 30, name = "PXL_2.DNG")
        val jpeg2 = item("jpeg2", ageDays = 700, size = 5, name = "PXL_2.jpg", plusMillis = 500)
        val serverOnly = item("server", ageDays = 800, size = 10).copy(isOnDevice = false)
        val entries = listOf(old, recent, jpeg, raw2, jpeg2, serverOnly).associate { it.id to uploaded(it) } +
            (changed.id to uploaded(changed).copy(modifiedMillis = 1))

        val plan = freeUpPlan(listOf(old, recent, notBackedUp, changed, raw, jpeg, raw2, jpeg2, serverOnly), entries, FreeUpRule.OlderThan(365), NOW)

        assertEquals(listOf(listOf("old"), listOf("raw2", "jpeg2")), plan.groups.map { g -> g.items.map { it.id } })
        assertEquals(45, plan.bytes)
        assertEquals(3, plan.photos)
    }

    @Test
    fun everythingTakesAllThatIsBackedUpFromTheChosenSources() {
        val camera = item("camera", ageDays = 1, size = 10)
        val oldCamera = item("old", ageDays = 900, size = 20)
        val whatsapp = item("wa", ageDays = 5, size = 30).copy(source = com.chencohensolution.journal.library.MediaSource.WHATSAPP)
        val notBackedUp = item("mine", ageDays = 2, size = 40)
        val entries = listOf(camera, oldCamera, whatsapp).associate { it.id to uploaded(it) }
        val items = listOf(camera, oldCamera, whatsapp, notBackedUp)

        val all = freeUpPlan(items, entries, FreeUpRule.Everything, NOW)
        assertEquals(setOf("camera", "old", "wa"), all.groups.flatMap { g -> g.items.map { it.id } }.toSet())
        assertEquals(60, all.bytes)
        assertEquals(NOW - 900 * DAY to NOW - DAY, all.takenRange)

        val onlyWhatsApp = freeUpPlan(items, entries, FreeUpRule.Everything, NOW, kept = setOf(com.chencohensolution.journal.library.MediaSource.CAMERA))
        assertEquals(listOf("wa"), onlyWhatsApp.groups.flatMap { g -> g.items.map { it.id } })
        assertNull(freeUpPlan(items, emptyMap(), FreeUpRule.Everything, NOW).takenRange)
    }

    @Test
    fun keepNewestKeepsTheNewestUpToTheSizeCountingEverythingOnThePhone() {
        val newest = item("1", ageDays = 1, size = 3 * GB)
        val notBackedUp = item("2", ageDays = 2, size = 1 * GB)
        val tooMuch = item("3", ageDays = 3, size = 2 * GB)
        val oldest = item("4", ageDays = 4, size = GB)
        val entries = listOf(newest, tooMuch, oldest).associate { it.id to uploaded(it) }

        val plan = freeUpPlan(listOf(oldest, tooMuch, notBackedUp, newest), entries, FreeUpRule.KeepNewest(5), NOW)

        // 3 + 1 GB fit in 5; the next 2 GB don't, so it and everything older goes.
        assertEquals(listOf("3", "4"), plan.groups.flatMap { g -> g.items.map { it.id } })
    }

    @Test
    fun checkerNeedsBothCopiesToHaveTheSha256() = runTest {
        val fs = FakeFileSystem()
        val ledger = UploadLedger(fs, "/backup".toPath())
        val files = Files()
        val server = Server()
        val checker = FreeUpChecker(files, ledger, server)
        fun add(id: String, content: String): MediaItem {
            val bytes = content.encodeToByteArray()
            files.bytes[id] = bytes
            return item(id, 400, bytes.size.toLong())
        }
        val good = add("good", "photo one")
        val edited = add("edited", "photo two")
        val missing = add("missing", "photo three")
        val damaged = add("damaged", "photo four")
        for (it in listOf(good, missing, damaged)) ledger.record(uploaded(it, files.bytes.getValue(it.id).toByteString().sha256().hex()))
        ledger.record(uploaded(edited, "a different sha256"))
        server.verdicts[ledger.entry("missing")!!.sha256] = ServerVerdict.MISSING
        server.verdicts[ledger.entry("damaged")!!.sha256] = ServerVerdict.MISMATCH

        assertNull(checker.check(FreeUpGroup(listOf(good))))
        assertEquals(KeptReason.CHANGED_ON_PHONE, checker.check(FreeUpGroup(listOf(edited))))
        assertNull(ledger.entry("edited"), "read again by backup")
        assertEquals(KeptReason.MISSING_ON_SERVER, checker.check(FreeUpGroup(listOf(missing))))
        assertEquals(false, ledger.entry("missing")!!.uploaded)
        assertEquals(KeptReason.DAMAGED_ON_SERVER, checker.check(FreeUpGroup(listOf(damaged))))
        assertTrue(ledger.entry("damaged")!!.uploaded)
        assertEquals(KeptReason.DAMAGED_ON_SERVER, checker.check(FreeUpGroup(listOf(good, damaged))), "a pair stays when one fails")

        server.offline = true
        assertFailsWith<okio.IOException> { checker.check(FreeUpGroup(listOf(good))) }
    }

    private class Run(allowed: Boolean, testDispatcher: kotlinx.coroutines.CoroutineDispatcher) {
        val fs = FakeFileSystem()
        val ledger = UploadLedger(fs, "/backup".toPath())
        val files = Files()
        val server = Server()
        val trash = Trash(allowed)
        val library = Library(emptyList())
        var ledgerChanges = 0
        val controller = FreeUpController(
            settings = JournalSettings(Store()),
            ledger = ledger,
            library = library,
            files = files,
            server = server,
            fileSystem = fs,
            directory = "/freeup".toPath(),
            now = { NOW },
            onLedgerChanged = { ledgerChanges++ },
            dispatcher = testDispatcher,
        )

        suspend fun photo(id: String, content: String, ageDays: Long = 400): MediaItem {
            val bytes = content.encodeToByteArray()
            files.bytes[id] = bytes
            val it = item(id, ageDays, bytes.size.toLong())
            ledger.record(uploaded(it, bytes.toByteString().sha256().hex()))
            library.items += it
            return it
        }
    }

    @Test
    fun withMediaManagementAccessEachCheckedPhotoGoesToTheTrashRightAway() = runTest {
        val run = Run(allowed = true, StandardTestDispatcher(testScheduler))
        run.photo("a", "first")
        run.photo("b", "second")
        val edited = run.photo("c", "third")
        // Edited in place without its size or date changing: only the hash shows it.
        run.files.bytes[edited.id] = "THIRD".encodeToByteArray()
        run.photo("new", "taken last week", ageDays = 7)
        run.controller.setRule(FreeUpRule.OlderThan(365))

        val result = run.controller.runOnce(run.trash)!!

        assertEquals(listOf("a", "b"), run.trash.trashed)
        assertEquals(listOf(2), run.trash.requests, "one request for the batch")
        assertEquals(2, result.removed)
        assertEquals(mapOf(KeptReason.CHANGED_ON_PHONE to 1), result.kept)
        assertEquals(1, run.ledgerChanges)
        assertEquals(0, result.waiting)
    }

    @Test
    fun withoutAccessCheckedPhotosWaitForOnePromptAndChangedOnesAreLeftOut() = runTest {
        val run = Run(allowed = false, StandardTestDispatcher(testScheduler))
        val a = run.photo("a", "first")
        val b = run.photo("b", "second")

        val result = run.controller.runOnce(run.trash)!!

        assertEquals(emptyList(), run.trash.trashed)
        assertEquals(2, result.waiting)
        assertEquals(2, run.controller.currentState.waitingCount)
        assertEquals(listOf(a, b), run.controller.waitingItems())

        // A second run doesn't check them again.
        run.server.asked.clear()
        run.controller.runOnce(run.trash)
        assertEquals(emptyList(), run.server.asked)

        // Changed after it was checked: left out of the prompt.
        run.library.items = listOf(a, b.copy(modifiedMillis = 99))
        assertEquals(listOf(a), run.controller.waitingItems())

        run.controller.confirmationDone()
        run.controller.state.first { it.waitingCount == 0 }
        assertEquals(emptyList(), run.controller.waitingItems())
    }

    @Test
    fun anUnreachableServerStopsTheRunAndRemovesNothing() = runTest {
        val run = Run(allowed = true, StandardTestDispatcher(testScheduler))
        run.photo("a", "first")
        run.server.offline = true

        assertNull(run.controller.runOnce(run.trash))
        assertEquals(emptyList(), run.trash.trashed)
        assertTrue(run.controller.currentState.activity is com.chencohensolution.journal.storage.FreeUpActivity.Stopped)
    }

    @Test
    fun soundFilesOfTheChosenFoldersAreDeletedForGoodOnceChecked() = runTest {
        val calls = SoundFolder("content://tree/calls", "Call", "Recordings › Call", AudioKind.CALL)
        val voice = SoundFolder("content://tree/voice", "Voice Recorder", "Recordings › Voice Recorder", AudioKind.RECORDING)
        val fs = FakeFileSystem()
        val ledger = UploadLedger(fs, "/backup".toPath())
        val files = Files()
        val server = Server()
        val inFolders = mutableMapOf<SoundFolder, MutableList<MediaItem>>()
        suspend fun sound(folder: SoundFolder, name: String, content: String, sha: String? = null): MediaItem {
            val bytes = content.encodeToByteArray()
            val it = item(SoundIds.of(folder, name), ageDays = 400, size = bytes.size.toLong(), name = name, type = MediaType.AUDIO)
            files.bytes[it.id] = bytes
            ledger.record(uploaded(it, sha ?: bytes.toByteString().sha256().hex()))
            inFolders.getOrPut(folder) { mutableListOf() } += it
            return it
        }
        val call = sound(calls, "Call recording 0501234567_241025_101500.m4a", "a call")
        // A WAV kept on the phone while the server has its FLAC: checked by the WAV's sha256, which the server answers.
        val wav = sound(calls, "Call recording 0501234567_241025_111500.wav", "a WAV call")
        val changed = sound(calls, "Call recording 0501234567_241025_121500.m4a", "a call", sha = "not these bytes")
        val recording = sound(voice, "20241025_101500.m4a", "a recording")
        val store = Store()
        JournalSettings(store).backup = com.chencohensolution.journal.backup.BackupSettings(soundFolders = listOf(calls, voice))
        val photo = item("p", ageDays = 400, size = 5)
        files.bytes["p"] = "photo".encodeToByteArray()
        ledger.record(uploaded(photo, "photo".encodeToByteArray().toByteString().sha256().hex()))
        val removed = mutableListOf<String>()
        val controller = FreeUpController(
            settings = JournalSettings(store),
            ledger = ledger,
            library = Library(listOf(photo)),
            files = files,
            server = server,
            soundFolders = object : SoundFolders {
                override suspend fun list(folder: SoundFolder) = inFolders[folder].orEmpty()
            },
            fileSystem = fs,
            directory = "/freeup".toPath(),
            now = { NOW },
            dispatcher = StandardTestDispatcher(testScheduler),
        )
        controller.toggleFolder(voice.treeUri)
        val plan = controller.state.first { it.plan != null }.plan!!
        assertEquals(3, plan.sounds, "the calls, not the kept Voice Recorder folder")
        assertEquals(1, plan.photos)
        assertEquals(3, plan.ofFolder(calls.treeUri).files)
        assertEquals(0, plan.ofFolder(voice.treeUri).files)

        val trash = Trash(allowed = true)
        val result = controller.runOnce(trash, object : SoundRemoval {
            override suspend fun delete(items: List<MediaItem>): Set<String> {
                removed += items.map { it.id }
                return items.mapTo(mutableSetOf()) { it.id }
            }
        })!!

        assertEquals(listOf(call.id, wav.id).sorted(), removed.sorted())
        assertEquals(listOf("p"), trash.trashed, "photos still go to the trash")
        assertEquals(3, result.removed)
        assertEquals(mapOf(KeptReason.CHANGED_ON_PHONE to 1), result.kept)
        assertTrue(changed.id !in removed && recording.id !in removed)
        assertEquals(0, result.waiting)
    }
}
