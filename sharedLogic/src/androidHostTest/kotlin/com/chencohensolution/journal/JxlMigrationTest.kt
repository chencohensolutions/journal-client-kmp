package com.chencohensolution.journal

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.chencohensolution.journal.backup.LedgerEntry
import com.chencohensolution.journal.backup.UploadLedger
import com.chencohensolution.journal.db.LibraryDatabase
import com.chencohensolution.journal.library.LibraryTable
import com.chencohensolution.journal.library.LocalDay
import com.chencohensolution.journal.library.MediaItem
import com.chencohensolution.journal.library.MediaLibrary
import com.chencohensolution.journal.library.MediaSource
import com.chencohensolution.journal.library.MediaType
import com.chencohensolution.journal.library.PhotoAccess
import com.chencohensolution.journal.library.StoredMediaLibrary
import com.chencohensolution.journal.server.FeedPage
import com.chencohensolution.journal.server.ServerMedia
import com.chencohensolution.journal.server.ServerMediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * A phone's database from before JPEGs were kept as JPEG XL (version 9) takes migration 9: the server's files gain the
 * JPEG they were kept from, the change feed is read again, and the phone's copy of a JPEG is its JPEG XL, also as the
 * library is made again only where it changed (the triggers 9.sqm makes again).
 */
class JxlMigrationTest {
    private class OnePhoto(val items: List<MediaItem>) : MediaLibrary {
        override fun access() = PhotoAccess.FULL
        override suspend fun loadItems() = items
    }

    @Test
    fun aVersion9DatabaseMatchesJpegsKeptAsJxl() = runTest {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY, Properties())
        LibraryDatabase.Schema.create(driver)
        // Back to version 9's shape: no encoded_sha256, and its triggers.
        listOf(
            "DROP INDEX server_media_encoded",
            "DROP TRIGGER server_media_changed",
            "DROP TRIGGER server_media_gone",
            "ALTER TABLE server_media DROP COLUMN encoded_sha256",
            "ALTER TABLE server_private DROP COLUMN encoded_sha256",
            "CREATE TRIGGER server_media_changed AFTER UPDATE ON server_media BEGIN INSERT OR IGNORE INTO library_dirty VALUES ('s', new.sha256); END",
            "CREATE TRIGGER server_media_gone AFTER DELETE ON server_media BEGIN INSERT OR IGNORE INTO library_dirty VALUES ('s', old.sha256); END",
            "INSERT INTO sync_state VALUES ('server_cursor', 'from version 9')",
        ).forEach { driver.execute(null, it, 0) }
        LibraryDatabase.Schema.migrate(driver, 9, LibraryDatabase.Schema.version)

        val database = LibraryDatabase(driver)
        val store = ServerMediaStore(database)
        assertNull(store.cursor(), "the feed is read again, to learn which files are JPEG XL")
        val day = LocalDay(2026, 10, 1)
        val photo = MediaItem(
            id = "1", type = MediaType.IMAGE, takenAtMillis = day.toEpochDay() * 86_400_000, day = day, durationMillis = null,
            width = 4000, height = 3000, sizeBytes = 3_000_000, fileName = "IMG_1.jpg", source = MediaSource.CAMERA,
            modifiedMillis = 5, mimeType = "image/jpeg",
        )
        StoredMediaLibrary(OnePhoto(listOf(photo)), database, Dispatchers.Unconfined).loadItems()
        val jpeg = "1".padStart(64, '0')
        UploadLedger(FakeFileSystem(), "/backup".toPath(), database, Dispatchers.Unconfined)
            .record(LedgerEntry("1", 3_000_000, 5, jpeg, uploaded = true))
        val table = LibraryTable(database)
        table.refresh()
        // The feed as the server sends it once the photo is kept as JPEG XL: the JPEG gone, then its JPEG XL.
        val jxl = ServerMedia(
            sha256 = "2".padStart(64, '0'), isVideo = false, fileName = "IMG_1.jxl", mimeType = "image/jxl", size = 2_400_000,
            uploadedAt = "2026-10-20T10:00:00Z", isPrivate = true, encodedSha256 = jpeg,
        )
        store.apply(FeedPage(listOf(jxl), listOf(jpeg), emptyList(), listOf(jxl.sha256, jpeg), "c1"), showPrivate = true)
        table.refresh()
        val rows = table.all()
        assertEquals(listOf("1"), rows.map { it.item.id }, "the phone's JPEG shows once")
        assertEquals(jxl.sha256, rows.single().serverSha)
        assertEquals(true, rows.single().item.isPrivate)

        // The JPEG XL leaves the server's list (deleted there): the phone's photo is only the phone's again.
        store.apply(FeedPage(emptyList(), listOf(jxl.sha256), emptyList(), listOf(jxl.sha256), "c2"), showPrivate = true)
        table.refresh()
        assertNull(table.all().single().serverSha)
    }
}
