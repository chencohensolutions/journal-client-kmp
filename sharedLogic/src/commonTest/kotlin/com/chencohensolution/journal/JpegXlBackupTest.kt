package com.chencohensolution.journal

import com.chencohensolution.journal.backup.BackupEngine
import com.chencohensolution.journal.backup.BackupSettings
import com.chencohensolution.journal.backup.EncodedFile
import com.chencohensolution.journal.backup.UploadLedger
import com.chencohensolution.journal.library.LocalDay
import com.chencohensolution.journal.library.MediaItem
import com.chencohensolution.journal.library.MediaType
import com.chencohensolution.journal.storage.ServerCheck
import com.chencohensolution.journal.storage.ServerVerdict
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import okio.Buffer
import okio.ByteString.Companion.toByteString
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** JPEG photos go to a server that keeps JPEGs as JPEG XL as the JPEG XL the phone packs of them. */
class JpegXlBackupTest {
    private val fs = FakeFileSystem()
    private val server = FakeServer()
    private val database = testLibraryDatabase()
    private val ledger = UploadLedger(fs, "/backup".toPath(), database, Dispatchers.Unconfined)
    private val settings = BackupSettings(enabled = true)

    /** "Packs" a JPEG by keeping its first three quarters, and remembers each JPEG XL made and discarded. */
    private class PhotoFiles(private val canPack: Boolean = true) : FakeFiles() {
        val packed = mutableListOf<String>()
        val discarded = mutableListOf<String>()
        val replaced = mutableListOf<String>()

        override suspend fun encodeJxl(item: MediaItem): EncodedFile? {
            if (!canPack) return null
            packed += item.id
            val jxl = bytes.getValue(item.id).let { it.copyOf(it.size * 3 / 4) }
            val name = item.fileName!!.substringBeforeLast('.') + ".jxl"
            return EncodedFile(name, jxl.size.toLong(), jxl.toByteString().sha256().hex(), { Buffer().write(jxl.copyOfRange(it.toInt(), jxl.size)) }, { discarded += name })
        }

        override suspend fun replaceWithJxl(item: MediaItem, jxl: EncodedFile): Boolean {
            replaced += item.id
            return true
        }
    }

    private fun TestScope.engine(files: PhotoFiles, packJpegs: Boolean = true, check: ServerCheck? = null) =
        BackupEngine(files, ledger, server, check = check, packJpegs = packJpegs, io = StandardTestDispatcher(testScheduler))

    private fun photo(files: PhotoFiles, id: String, name: String, mime: String? = null, type: MediaType = MediaType.IMAGE): MediaItem {
        val bytes = ByteArray(4_000 + id.length) { ((it * 13 + id.hashCode()) % 251).toByte() }
        files.bytes[id] = bytes
        return MediaItem(id, type, 1_000, LocalDay(2026, 10, 28), null, 4000, 3000, bytes.size.toLong(), name, modifiedMillis = 1, mimeType = mime)
    }

    private fun sha(files: PhotoFiles, item: MediaItem) = files.bytes.getValue(item.id).toByteString().sha256().hex()

    @Test
    fun aJpegGoesAsItsJpegXlNamingTheJpeg() = runTest {
        val files = PhotoFiles()
        val jpeg = photo(files, "1", "20261028_101500.jpg", mime = "image/jpeg")

        val result = engine(files).run(listOf(jpeg), settings) {}

        assertEquals(1, result.uploaded)
        val request = server.requests.values.single()
        assertEquals("20261028_101500.jxl", request.fileName)
        assertEquals("image/jxl", request.mimeType)
        assertEquals(MediaType.IMAGE, request.kind)
        val from = request.encodedFrom!!
        assertEquals(sha(files, jpeg), from.sha256, "the server learns the phone's JPEG is this JPEG XL")
        assertEquals("image/jpeg", from.mimeType)
        assertEquals(jpeg.sizeBytes, from.size)
        assertEquals("20261028_101500.jpg", from.fileName)
        assertEquals(listOf("20261028_101500.jxl"), files.discarded, "the JPEG XL made to send it is deleted")
        assertTrue(ledger.isUploaded(jpeg))
        assertEquals(sha(files, jpeg), ledger.entry(jpeg.id)?.sha256, "the ledger keeps the phone's JPEG")

        // The JPEG again (a copy in another folder): the server answers it through its JPEG XL, and nothing is packed.
        val copy = photo(files, "2", "copy.jpeg").also { files.bytes[it.id] = files.bytes.getValue(jpeg.id) }
        val again = engine(files).run(listOf(copy), settings) {}
        assertEquals(1, again.alreadyOnServer)
        assertEquals(listOf("1"), files.packed)
    }

    @Test
    fun otherFilesAndOtherServersGetTheFileAsItIs() = runTest {
        val files = PhotoFiles()
        val heic = photo(files, "1", "IMG_1.heic", mime = "image/heic")
        val video = photo(files, "2", "VID_1.mp4", mime = "video/mp4", type = MediaType.VIDEO)
        engine(files).run(listOf(heic, video), settings) {}
        val jpeg = photo(files, "3", "IMG_2.jpg")
        engine(files, packJpegs = false).run(listOf(jpeg), settings) {}

        assertTrue(files.packed.isEmpty(), "only JPEG photos, and only for a server that keeps JPEG XL")
        assertEquals(setOf("IMG_1.heic", "VID_1.mp4", "IMG_2.jpg"), server.requests.values.map { it.fileName }.toSet())
        assertTrue(server.requests.values.all { it.encodedFrom == null })
    }

    @Test
    fun aJpegThePhoneCantPackOrTheServerRefusesGoesAsItIs() = runTest {
        val files = PhotoFiles(canPack = false)
        val unpacked = photo(files, "1", "gray.jpg")
        engine(files).run(listOf(unpacked), settings) {}
        assertEquals("gray.jpg", server.requests.values.single().fileName)

        server.refusesJpegXl = true
        val packing = PhotoFiles()
        val refused = photo(packing, "2", "IMG_3.jpg")
        val result = engine(packing).run(listOf(refused), settings) {}
        assertEquals(1, result.uploaded)
        val sent = server.stored.getValue(sha(packing, refused))
        assertTrue(sent.contentEquals(packing.bytes.getValue(refused.id)), "the JPEG itself, after the JPEG XL was refused")
        assertNull(server.requests.getValue(sha(packing, refused)).encodedFrom)
        assertEquals(listOf("IMG_3.jxl"), packing.discarded)
        assertTrue(ledger.isUploaded(refused))
    }

    @Test
    fun keepingJpegXlReplacesTheJpegOnlyWhenOnAndTheServerConfirmsItsBytes() = runTest {
        val files = PhotoFiles()
        val keep = settings.copy(keepJpegXlOnPhone = true)
        val off = photo(files, "1", "IMG_1.jpg")
        engine(files, check = { ServerVerdict.OK }).run(listOf(off), settings) {}
        assertTrue(files.replaced.isEmpty(), "off by default: the phone keeps its JPEG")

        val confirmed = photo(files, "2", "IMG_2.jpg")
        engine(files, check = { ServerVerdict.OK }).run(listOf(confirmed), keep) {}
        assertEquals(listOf("2"), files.replaced)

        val unconfirmed = photo(files, "3", "IMG_3.jpg")
        engine(files, check = { ServerVerdict.MISMATCH }).run(listOf(unconfirmed), keep) {}
        val unreachable = photo(files, "4", "IMG_4.jpg")
        engine(files, check = { error("offline") }).run(listOf(unreachable), keep) {}
        assertEquals(listOf("2"), files.replaced, "never without the server's word")

        // Refused and sent as the JPEG: the server keeps a JPEG XL of its own making, which isn't the phone's.
        server.refusesJpegXl = true
        val refused = photo(files, "5", "IMG_5.jpg")
        engine(files, check = { ServerVerdict.OK }).run(listOf(refused), keep) {}
        assertEquals(listOf("2"), files.replaced)
        assertEquals(5, files.discarded.size, "each JPEG XL made to send it is deleted")
    }
}
