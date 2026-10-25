package com.chencohensolution.journal

import com.chencohensolution.journal.backup.LedgerEntry
import com.chencohensolution.journal.library.BackupStatus
import com.chencohensolution.journal.library.LocalDay
import com.chencohensolution.journal.library.MediaItem
import com.chencohensolution.journal.library.MediaSource
import com.chencohensolution.journal.library.MediaType
import com.chencohensolution.journal.library.mergeWithServer
import com.chencohensolution.journal.network.MtlsHttpClientFactory
import com.chencohensolution.journal.server.PrivateFile
import com.chencohensolution.journal.server.ServerHttp
import com.chencohensolution.journal.server.ServerLibrary
import com.chencohensolution.journal.server.ServerMedia
import com.chencohensolution.journal.server.parseTimestamp
import com.chencohensolution.journal.settings.JournalSettings
import com.chencohensolution.journal.settings.KeyValueStore
import com.chencohensolution.journal.settings.PrivateMedia
import com.chencohensolution.journal.settings.SettingsController
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class ServerLibraryTest {
    private fun server(
        sha: Char, name: String, size: Long, takenAt: String? = "2026-10-08T19:01:57.937+03:00", nudity: Double? = null,
        isPrivate: Boolean = false,
    ) = ServerMedia(
        sha256 = sha.toString().repeat(64), isVideo = false, fileName = name, mimeType = "image/heic", size = size,
        takenAt = takenAt, width = 3000, height = 4000, source = "whatsapp", latitude = 32.8, longitude = 35.0,
        uploadedAt = "2026-10-19T22:05:21.535868Z", nudity = nudity, isPrivate = isPrivate,
    )

    private fun device(id: String, name: String, size: Long) =
        MediaItem(id, MediaType.IMAGE, 0, LocalDay(2026, 10, 8), null, 3000, 4000, size, name, modifiedMillis = 1)

    @Test
    fun aPhotoBackupDidNotReadIsFoundOnTheServerByNameAndSize() = runTest {
        // Imported on the server, so this phone's backup never read it: no sha256 of its own.
        val imported = server('a', "20261008_210329.heic", 714_000)
        val files = FakeFileSystem().apply {
            createDirectories("/server".toPath())
            write("/server/media.json".toPath()) { writeUtf8("{\"v\":1,\"media\":[${Json.encodeToString(ServerMedia.serializer(), imported)}]}") }
        }
        val store = object : KeyValueStore {
            override fun getString(key: String): String? = null
            override fun putString(key: String, value: String?) {}
        }
        val factory = MtlsHttpClientFactory { _, _ -> error("no network in this test") }
        val library = ServerLibrary(JournalSettings(store), ServerHttp(JournalSettings(store), factory, emptyList()), files, "/server".toPath(), testLibraryDatabase(), StandardTestDispatcher(testScheduler), io = StandardTestDispatcher(testScheduler))
        library.media.first { it.isNotEmpty() }

        assertEquals(imported.sha256, library.sha256Of(device("1", "20261008_210329.HEIC", 714_000)))
        assertEquals("b".repeat(64), library.sha256Of(device("2", "other.jpg", 1).copy(sha256 = "b".repeat(64))))
        assertNull(library.sha256Of(device("3", "20261008_210329.heic", 1)))
    }

    @Test
    fun parsesTheServersTimesWithTheirOwnDay() {
        val t = parseTimestamp("2026-10-08T01:30:00.250+03:00")!!
        assertEquals(LocalDay(2026, 10, 8), t.day) // Still Oct 8 where it was taken, though Oct 7 in UTC.
        assertEquals(LocalDay(2026, 10, 7).toEpochDay() * 86_400_000L + 22 * 3_600_000L + 30 * 60_000L + 250, t.millis)
        assertEquals(0L, parseTimestamp("1970-01-01T00:00:00Z")!!.millis)
        assertNull(parseTimestamp("yesterday"))
    }

    @Test
    fun photosOnlyOnTheServerAreAddedOnce() {
        val local = device("21", "20261008_190157.heic", 541_212)
        val renamedLocally = device("22", "other.heic", 999)
        val entries = mapOf("22" to LedgerEntry("22", 999, 1, "b".repeat(64), uploaded = true))
        val merged = mergeWithServer(
            listOf(local, renamedLocally),
            entries,
            listOf(
                server('a', "20261008_190157.heic", 541_212), // same name and size as a device photo not read yet
                server('b', "IMG_1.heic", 999), // the device photo backup read (same sha256)
                server('c', "deleted.heic", 123), // only on the server
            ),
        )
        assertEquals(listOf("21", "22", "server:" + "c".repeat(64)), merged.map { it.id })
        assertEquals("b".repeat(64), merged[1].sha256)

        val only = merged.last()
        assertFalse(only.isOnDevice)
        assertEquals(BackupStatus.BackedUp, only.backup)
        assertEquals(MediaSource.WHATSAPP, only.source)
        assertEquals(LocalDay(2026, 10, 8), only.day)
        assertEquals(32.8, only.location?.latitude)
    }

    @Test
    fun aPhotoDeletedFromThePhoneKeepsItsIdAndShowsAKeptOriginal() {
        val entries = mapOf("49" to LedgerEntry("49", 541_212, 1, "a".repeat(64), uploaded = true))
        val merged = mergeWithServer(
            device = emptyList(),
            entries = entries,
            server = listOf(server('a', "20261008_190157.heic", 541_212), server('c', "other.heic", 5)),
            kept = mapOf("c".repeat(64) to "/files/originals/cccc.heic"),
        )
        // Its stories and note refer to the device id it had.
        assertEquals("49", merged[0].id)
        assertFalse(merged[0].isOnDevice)
        assertFalse(merged[0].hasOriginalHere)
        assertEquals("/files/originals/cccc.heic", merged[1].keptOriginal)
        assertEquals(true, merged[1].hasOriginalHere)
    }

    @Test
    fun theDevicesOwnCopyOfWhatTheServerHidesIsLeftOut() {
        val backedUp = device("22", "IMG_1.heic", 999)
        val notReadYet = device("23", "IMG_2.heic", 555)
        val entries = mapOf("22" to LedgerEntry("22", 999, 1, "b".repeat(64), uploaded = true))
        val server = listOf(server('e', "beach.heic", 321, nudity = 0.9)) // the server sends what it shows, whatever the score
        val hiddenOnServer = setOf("b".repeat(64))
        val merged = mergeWithServer(listOf(backedUp, notReadYet), entries, server, hiddenOnServer = hiddenOnServer)
        // The copy backup read is left out; the one it hasn't read yet can't be matched, and shows.
        assertEquals(listOf("23", "server:" + "e".repeat(64)), merged.map { it.id })
        // "Show private media" doesn't bring it back: the server hides it.
        assertEquals(merged, mergeWithServer(listOf(backedUp, notReadYet), entries, server, privateMedia = PrivateMedia(show = true), hiddenOnServer = hiddenOnServer))
    }

    @Test
    fun filesMarkedPrivateAreHiddenWhateverTheirScoreAndFlaggedWhenShown() {
        val backedUp = device("22", "IMG_1.heic", 999)
        val notReadYet = device("23", "IMG_2.heic", 555)
        val entries = mapOf("22" to LedgerEntry("22", 999, 1, "b".repeat(64), uploaded = true))
        val server = listOf(
            server('b', "IMG_1.heic", 999, isPrivate = true), // the device photo backup read (same sha256)
            server('d', "IMG_2.heic", 555, nudity = 0.1, isPrivate = true), // matched by name and size, scored low
            server('c', "only.heic", 123, isPrivate = true), // only on the server
            server('e', "beach.heic", 321),
        )
        val hidden = mergeWithServer(listOf(backedUp, notReadYet), entries, server, privateMedia = PrivateMedia())
        assertEquals(listOf("server:" + "e".repeat(64)), hidden.map { it.id })

        val shown = mergeWithServer(listOf(backedUp, notReadYet), entries, server, privateMedia = PrivateMedia(show = true))
        assertEquals(listOf("22" to true, "23" to true, "server:" + "c".repeat(64) to true, "server:" + "e".repeat(64) to false),
            shown.map { it.id to it.isPrivate })
    }

    @Test
    fun thePhonesCopiesOfPrivateFilesLeftOutOfTheListAreHiddenToo() {
        val backedUp = device("22", "IMG_1.heic", 999)
        val notReadYet = device("23", "IMG_2.heic", 555)
        val entries = mapOf("22" to LedgerEntry("22", 999, 1, "b".repeat(64), uploaded = true))
        val leftOut = listOf(PrivateFile("b".repeat(64), "IMG_1.heic", 999), PrivateFile("d".repeat(64), "IMG_2.heic", 555))
        val server = listOf(server('e', "beach.heic", 321))
        // Matched by sha256, or by name and size before backup read it; as when the list held them as private.
        assertEquals(listOf("server:" + "e".repeat(64)), mergeWithServer(listOf(backedUp, notReadYet), entries, server, privateFiles = leftOut).map { it.id })
        assertEquals(
            mergeWithServer(listOf(backedUp, notReadYet), entries, listOf(server('b', "IMG_1.heic", 999, isPrivate = true), server('d', "IMG_2.heic", 555, isPrivate = true)) + server)
                .map { it.id },
            mergeWithServer(listOf(backedUp, notReadYet), entries, server, privateFiles = leftOut).map { it.id },
        )
    }

    @Test
    fun showPrivateMediaIsOffByDefaultAndSaved() = runTest {
        val values = mutableMapOf<String, String>()
        val store = object : KeyValueStore {
            override fun getString(key: String) = values[key]
            override fun putString(key: String, value: String?) {
                if (value == null) values.remove(key) else values[key] = value
            }
        }
        val dispatcher = StandardTestDispatcher(testScheduler)
        val settings = SettingsController(JournalSettings(store), dispatcher)
        values["private_threshold_percent"] = "70" // kept here before the server hid nudity
        assertEquals(PrivateMedia(show = false), settings.privateMedia.value)
        settings.setShowPrivateMedia(true)
        assertEquals(PrivateMedia(show = true), settings.currentState.privateMedia)
        assertEquals(PrivateMedia(show = true), SettingsController(JournalSettings(store), dispatcher).privateMedia.value)
        assertEquals(null, values["private_threshold_percent"])
    }
}
