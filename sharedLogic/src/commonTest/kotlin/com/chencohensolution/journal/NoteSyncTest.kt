package com.chencohensolution.journal

import com.chencohensolution.journal.library.LibraryController
import com.chencohensolution.journal.library.LibraryTable
import com.chencohensolution.journal.library.LocalDay
import com.chencohensolution.journal.library.MediaItem
import com.chencohensolution.journal.library.MediaLibrary
import com.chencohensolution.journal.library.MediaType
import com.chencohensolution.journal.library.PhotoAccess
import com.chencohensolution.journal.library.StoredMediaLibrary
import com.chencohensolution.journal.server.FeedPage
import com.chencohensolution.journal.server.NoteAnswer
import com.chencohensolution.journal.server.ServerMedia
import com.chencohensolution.journal.server.ServerMediaStore
import com.chencohensolution.journal.settings.JournalSettings
import com.chencohensolution.journal.settings.KeyValueStore
import com.chencohensolution.journal.settings.SettingsController
import com.chencohensolution.journal.stories.FeedPager
import com.chencohensolution.journal.stories.FeedTable
import com.chencohensolution.journal.stories.FilePhotoNoteStore
import com.chencohensolution.journal.stories.NoteSyncSource
import com.chencohensolution.journal.stories.NoteVersion
import com.chencohensolution.journal.stories.PhotoNote
import com.chencohensolution.journal.stories.StoriesController
import com.chencohensolution.journal.stories.mergeNotes
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class NotePhotos(val items: List<MediaItem>) : MediaLibrary {
    override fun access() = PhotoAccess.FULL
    override suspend fun loadItems() = items
}

/** The server's notes as setNote keeps them: a version per write, a conflict for an old base. */
private class FakeNoteServer(private val store: ServerMediaStore) {
    val notes = mutableMapOf<String, Pair<String, String>>()
    var reachable = true
    var writes = 0
    private var next = 0

    fun send(sha: String, note: String?, base: String?): NoteAnswer? {
        if (!reachable) return null
        val current = notes[sha]
        if (current?.second != base) return NoteAnswer(saved = false, note = current?.first, version = current?.second)
        writes++
        if (note == null) notes.remove(sha) else notes[sha] = note to "v${++next}"
        val now = notes[sha]
        store.keepNote(sha, now?.first, now?.second)
        return NoteAnswer(saved = true, note = now?.first, version = now?.second)
    }

    /** Another device wrote [note] (null: deleted it). */
    fun writtenElsewhere(sha: String, note: String?): String? {
        if (note == null) notes.remove(sha) else notes[sha] = note to "v${++next}"
        return notes[sha]?.second
    }
}

class NoteSyncTest {
    private val day = LocalDay(2026, 11, 1)
    private val device = (0 until 6).map { n ->
        MediaItem("$n", MediaType.IMAGE, day.toEpochDay() * 86_400_000 + n * 60_000L, day, null, 10, 10, 100L + n, "IMG_$n.jpg", modifiedMillis = 1)
    }

    /** The server's copy of phone photo [n], matched by name and size. */
    private fun serverCopy(n: Int, note: Pair<String?, String?>? = null) = ServerMedia(
        sha256 = "$n".padStart(64, 'a'), isVideo = false, fileName = "IMG_$n.jpg", mimeType = "image/jpeg", size = 100L + n,
        takenAt = "2026-11-01T00:0$n:00Z", uploadedAt = "2026-11-01T10:00:00Z", note = note?.first, noteVersion = note?.second,
    )

    private class Setup(
        val stories: StoriesController,
        val store: ServerMediaStore,
        val server: FakeNoteServer,
        val serverChanged: MutableStateFlow<Int>,
        val enabled: MutableStateFlow<Boolean>,
        val table: LibraryTable,
        val close: () -> Unit,
    )

    private fun TestScope.setup(clock: () -> String): Setup {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val database = testLibraryDatabase()
        fun store() = object : KeyValueStore {
            private val values = mutableMapOf<String, String>()
            override fun getString(key: String): String? = values[key]
            override fun putString(key: String, value: String?) {
                if (value == null) values.remove(key) else values[key] = value
            }
        }
        val library = LibraryController(
            StoredMediaLibrary(NotePhotos(device), database, dispatcher), JournalSettings(store()), dispatcher, table = LibraryTable(database),
        )
        val settings = SettingsController(JournalSettings(store()), dispatcher)
        val serverStore = ServerMediaStore(database)
        val server = FakeNoteServer(serverStore)
        val changed = MutableStateFlow(0)
        val enabled = MutableStateFlow(false)
        val stories = StoriesController(
            noteStore = FilePhotoNoteStore(FakeFileSystem(), "/data/journal".toPath()),
            library = library,
            settings = settings,
            newId = { "set1" },
            dispatcher = dispatcher,
            now = clock,
            feed = FeedPager(FeedTable(database), LibraryTable(database), dispatcher, pageSize = { 10 }),
            shownRow = library::shownRow,
            noteSync = NoteSyncSource(database, enabled, changed, { sha, note, base -> server.send(sha, note, base) }, dispatcher),
        )
        library.refresh()
        return Setup(stories, serverStore, server, changed, enabled, LibraryTable(database)) {
            stories.close()
            settings.close()
            library.close()
        }
    }

    /** The change feed brought [media]: written, the library made again, and the sync told. */
    private suspend fun Setup.feed(vararg media: ServerMedia) {
        store.apply(FeedPage(media.toList(), emptyList(), emptyList(), emptyList(), "c${serverChanged.value}"), showPrivate = true)
        table.refresh()
        serverChanged.value++
    }

    private fun note(content: String?) = content?.let { Json.parseToJsonElement(it).jsonObject }

    @Test
    fun notesGoBothWaysAndMergeWhenBothChanged() = runTest {
        var time = "2026-11-08T10:00:00Z"
        val s = setup { time }
        advanceUntilIdle()
        // Written before the server kept notes: sent once it does. Photo 5 isn't on the server.
        s.stories.setWriting("1", "Mine", "From the phone")
        s.stories.addTag(listOf("1"), "Sea")
        s.stories.setWriting("5", "Not uploaded", "")
        s.feed(serverCopy(1), serverCopy(2, """{"v":4,"title":"From the tablet","caption":"","tags":["Mom"],"updatedAt":"2026-11-08T09:00:00Z"}""" to "t1"))
        s.server.notes[serverCopy(2).sha256] = """{"v":4,"title":"From the tablet","caption":"","tags":["Mom"],"updatedAt":"2026-11-08T09:00:00Z"}""" to "t1"
        advanceUntilIdle()
        assertEquals(0, s.server.writes, "nothing is sent before the server keeps notes")

        s.enabled.value = true
        advanceUntilIdle()
        val sent = note(s.server.notes.getValue(serverCopy(1).sha256).first)!!
        assertEquals("Mine", sent["title"]!!.jsonPrimitive.content)
        assertEquals("From the phone", sent["caption"]!!.jsonPrimitive.content)
        assertTrue("deviceId" !in sent && "fileName" !in sent && "takenAt" !in sent, "only the note goes: $sent")
        // The tablet's note came in, with its tag; photo 5 waits for its upload.
        val two = s.stories.currentState.notes.getValue("2")
        assertEquals("From the tablet", two.title)
        assertEquals(listOf("Mom"), two.tags)
        assertTrue(s.stories.currentState.storyline.any { it.id == "2" })
        assertEquals(2, s.server.notes.size)

        // Photo 5 is uploaded: its note follows.
        s.feed(serverCopy(5))
        advanceUntilIdle()
        assertEquals("Not uploaded", note(s.server.notes[serverCopy(5).sha256]?.first)?.get("title")?.jsonPrimitive?.content)

        // Both changed photo 2 while apart: the tablet first, the phone later. The phone's writing is the photo's, the
        // tablet's an earlier version, and the merged note goes to the server.
        val tablet = """{"v":4,"title":"Tablet again","caption":"","tags":["Mom","Beach"],"updatedAt":"2026-11-08T11:00:00Z","writtenAt":"2026-11-08T11:00:00Z"}"""
        s.server.writtenElsewhere(serverCopy(2).sha256, tablet)
        time = "2026-11-08T12:00:00Z"
        s.stories.setWriting("2", "Phone again", "")
        advanceUntilIdle()
        val merged = s.stories.currentState.notes.getValue("2")
        assertEquals("Phone again", merged.title)
        assertTrue(merged.history.any { it.title == "Tablet again" }, "the tablet's writing is kept: ${merged.history}")
        assertTrue(merged.history.any { it.title == "From the tablet" })
        assertEquals("Phone again", note(s.server.notes.getValue(serverCopy(2).sha256).first)!!["title"]!!.jsonPrimitive.content)

        // Deleted on another device, and not changed here since: deleted here too.
        val gone = s.server.writtenElsewhere(serverCopy(1).sha256, null)
        s.feed(serverCopy(1, null to gone))
        advanceUntilIdle()
        assertNull(s.stories.currentState.notes["1"])
        assertTrue(s.stories.currentState.storyline.none { it.id == "1" })

        // Changed on another device: comes in, and isn't sent back.
        val writes = s.server.writes
        val elsewhere = """{"v":4,"title":"Set from the tablet","caption":"","set":"tablet-set","updatedAt":"2026-11-08T13:00:00Z"}"""
        val version = s.server.writtenElsewhere(serverCopy(5).sha256, elsewhere)
        s.feed(serverCopy(5, elsewhere to version))
        advanceUntilIdle()
        assertEquals("tablet-set", s.stories.currentState.notes.getValue("5").feedSet)
        assertEquals(writes, s.server.writes, "a note brought in isn't sent back")
        s.close()
    }

    @Test
    fun offlineChangesWaitForTheServer() = runTest {
        val s = setup { "2026-11-08T10:00:00Z" }
        s.enabled.value = true
        advanceUntilIdle()
        s.feed(serverCopy(3))
        advanceUntilIdle()
        s.server.reachable = false
        s.stories.setCrops("3", com.chencohensolution.journal.stories.PhotoCrops(moments = com.chencohensolution.journal.stories.CropRect(0f, 0f, 0.5f, 0.5f)))
        advanceUntilIdle()
        assertTrue(s.server.notes.isEmpty())
        s.server.reachable = true
        s.serverChanged.value++
        advanceUntilIdle()
        val sent = note(s.server.notes.getValue(serverCopy(3).sha256).first)!!
        assertEquals(0.5, (sent["crops"] as JsonObject)["moments"]!!.jsonObject["w"]!!.jsonPrimitive.content.toDouble())
        s.close()
    }

    @Test
    fun theWritingSavedLastWinsAndNothingIsLost() {
        val local = PhotoNote(
            "1", "IMG_1.jpg", 5, caption = "phone text", tags = listOf("A"), updatedAt = "2026-11-08T12:00:00Z", title = "Phone",
            writtenAt = "2026-11-08T12:00:00Z", history = listOf(NoteVersion("First", "", "2026-11-01T00:00:00Z")),
        )
        val server = PhotoNote(
            "server", null, 0, caption = "tablet text", tags = listOf("B"), updatedAt = "2026-11-08T13:00:00Z", title = "Tablet",
            writtenAt = "2026-11-08T11:00:00Z", feedSet = "s", history = listOf(NoteVersion("First", "", "2026-11-01T00:00:00Z")),
        )
        val merged = mergeNotes(local, server)
        assertEquals("Phone", merged.title, "the writing saved last")
        assertEquals("phone text", merged.caption)
        assertEquals(listOf(NoteVersion("First", "", "2026-11-01T00:00:00Z"), NoteVersion("Tablet", "tablet text", "2026-11-08T11:00:00Z")), merged.history)
        // The rest is the note changed last's: the tablet's.
        assertEquals(listOf("B"), merged.tags)
        assertEquals("s", merged.feedSet)
        assertEquals("1", merged.deviceId)
        assertEquals("IMG_1.jpg", merged.fileName)
        // The same writing on both: no new version.
        assertEquals(1, mergeNotes(local, local.copy(updatedAt = "2026-11-08T14:00:00Z")).history.size)
        // Cleared on the tablet after the phone wrote: cleared, and the phone's writing kept as a version.
        val cleared = mergeNotes(local, server.copy(title = "", caption = "", writtenAt = "2026-11-08T13:00:00Z"))
        assertFalse(cleared.hasWriting)
        assertTrue(cleared.history.any { it.title == "Phone" && it.text == "phone text" })
    }

    @Test
    fun theServersNoteKeepsWhatThisVersionDoesNotKnow() {
        val fs = FakeFileSystem()
        val store = FilePhotoNoteStore(fs, "/j".toPath())
        val content = """{"v":9,"title":"T","caption":"c","later":{"x":1},"updatedAt":"2026-11-08T10:00:00Z"}"""
        val saved = store.saveFromServer("42", "IMG.jpg", 7, content)!!
        assertEquals("T", saved.title)
        assertEquals("IMG.jpg", saved.fileName)
        assertEquals(7, saved.takenAtMillis)
        // Sent back as it came: the field it doesn't know too, without the photo's id, name or time.
        val back = Json.parseToJsonElement(store.syncContent("42")!!).jsonObject
        assertEquals(Json.parseToJsonElement(content).jsonObject, back)
        // A local change keeps it.
        store.save(saved.copy(caption = "changed"))
        val changed = Json.parseToJsonElement(store.syncContent("42")!!).jsonObject
        assertEquals("changed", changed["caption"]!!.jsonPrimitive.content)
        assertEquals(Json.parseToJsonElement("""{"x":1}"""), changed["later"])
        assertNull(store.saveFromServer("43", null, 0, "[1]"))
        assertNull(store.syncContent("43"))
    }
}
