package com.chencohensolution.journal

import com.chencohensolution.journal.backup.LedgerEntry
import com.chencohensolution.journal.library.BackupStatus
import com.chencohensolution.journal.library.LocalDay
import com.chencohensolution.journal.library.MediaItem
import com.chencohensolution.journal.library.MediaSource
import com.chencohensolution.journal.library.MediaType
import com.chencohensolution.journal.library.mergeWithServer
import com.chencohensolution.journal.server.ServerMedia
import com.chencohensolution.journal.server.parseTimestamp
import com.chencohensolution.journal.settings.JournalSettings
import com.chencohensolution.journal.settings.KeyValueStore
import com.chencohensolution.journal.settings.PrivateMedia
import com.chencohensolution.journal.settings.SettingsController
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
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
    fun privateMediaIsLeftOutUnlessShown() {
        val backedUp = device("22", "IMG_1.heic", 999)
        val notReadYet = device("23", "IMG_2.heic", 555)
        val entries = mapOf("22" to LedgerEntry("22", 999, 1, "b".repeat(64), uploaded = true))
        val server = listOf(
            server('b', "IMG_1.heic", 999, nudity = 0.9), // the device photo backup read (same sha256)
            server('d', "IMG_2.heic", 555, nudity = 0.7), // a device photo matched by name and size
            server('c', "only.heic", 123, nudity = 0.5), // only on the server, at the threshold
            server('e', "beach.heic", 321, nudity = 0.49), // below it
            server('f', "new.heic", 456), // not scanned yet
        )
        val hidden = mergeWithServer(listOf(backedUp, notReadYet), entries, server, privateMedia = PrivateMedia())
        assertEquals(listOf("server:" + "e".repeat(64), "server:" + "f".repeat(64)), hidden.map { it.id })

        val stricter = mergeWithServer(listOf(backedUp, notReadYet), entries, server, privateMedia = PrivateMedia(thresholdPercent = 80))
        assertEquals(listOf("23", "server:" + "c".repeat(64), "server:" + "e".repeat(64), "server:" + "f".repeat(64)), stricter.map { it.id })

        val shown = mergeWithServer(listOf(backedUp, notReadYet), entries, server, privateMedia = PrivateMedia(show = true))
        assertEquals(5, shown.size)
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
        assertEquals(PrivateMedia(show = false, thresholdPercent = 50), settings.privateMedia.value)
        settings.setShowPrivateMedia(true)
        settings.setPrivateThresholdPercent(70)
        settings.setPrivateThresholdPercent(100) // outside the range: ignored
        assertEquals(PrivateMedia(show = true, thresholdPercent = 70), settings.currentState.privateMedia)
        assertEquals(PrivateMedia(show = true, thresholdPercent = 70), SettingsController(JournalSettings(store), dispatcher).privateMedia.value)
    }
}
