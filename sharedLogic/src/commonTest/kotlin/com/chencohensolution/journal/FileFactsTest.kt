package com.chencohensolution.journal

import com.chencohensolution.journal.library.FileFacts
import com.chencohensolution.journal.library.FileFactsCache
import com.chencohensolution.journal.library.MediaSource
import com.chencohensolution.journal.library.editedFileName
import com.chencohensolution.journal.library.sourceOfAsset
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private val DIR = "/caches/library".toPath()

private fun facts(read: Long, size: Long? = 2_000_000, name: String? = "IMG_0001.HEIC", type: String? = "image/heic") =
    FileFacts(assetModifiedMillis = read, fileName = name, sizeBytes = size, mimeType = type, originalMimeType = type, modifiedMillis = read)

class FileFactsTest {
    @Test
    fun photoKitTellsTheSource() {
        fun source(screenshot: Boolean = false, whatsApp: Boolean = false, userLibrary: Boolean = true, type: String? = "image/heic") =
            sourceOfAsset(screenshot, whatsApp, userLibrary, type)
        assertEquals(MediaSource.CAMERA, source())
        assertEquals(MediaSource.CAMERA, source(type = "image/jpeg"))
        assertEquals(MediaSource.CAMERA, source(type = "video/quicktime"))
        assertEquals(MediaSource.CAMERA, source(type = "image/x-adobe-dng"))
        // Not read yet: as before it was read.
        assertEquals(MediaSource.CAMERA, source(type = null))
        // A screenshot is a PNG, and WhatsApp's photos are JPEGs: what PhotoKit marks wins over the type.
        assertEquals(MediaSource.SCREENSHOTS, source(screenshot = true, type = "image/png"))
        assertEquals(MediaSource.SCREENSHOTS, source(screenshot = true, whatsApp = true))
        assertEquals(MediaSource.WHATSAPP, source(whatsApp = true, type = "image/jpeg"))
        assertEquals(MediaSource.WHATSAPP, source(whatsApp = true, type = "video/mp4"))
        // Saved from apps: formats the camera doesn't write.
        assertEquals(MediaSource.OTHER, source(type = "image/png"))
        assertEquals(MediaSource.OTHER, source(type = "image/gif"))
        assertEquals(MediaSource.OTHER, source(type = "video/mp4"))
        // Synced from a computer or in a shared album.
        assertEquals(MediaSource.OTHER, source(userLibrary = false))
    }

    @Test
    fun anEditKeepsTheOriginalsName() {
        assertEquals("IMG_0001.jpg", editedFileName("IMG_0001.HEIC", "FullSizeRender.jpg"))
        assertEquals("IMG_0002.heic", editedFileName("IMG_0002.HEIC", "FullSizeRender.heic"))
        assertEquals("IMG_0003.mov", editedFileName("IMG_0003.MOV", "FullSizeRender.mov"))
        assertEquals("IMG_0004.HEIC", editedFileName("IMG_0004.HEIC", "FullSizeRender"))
    }

    @Test
    fun theFileKeepsItsDateUntilItChanges() {
        val first = facts(read = 1_000)
        // A favorite or the Photos analysis moved the asset's date; the file is as it was.
        val touched = facts(read = 5_000).after(first)
        assertEquals(5_000, touched.assetModifiedMillis)
        assertEquals(1_000, touched.modifiedMillis)
        // An edit: a new size.
        val edited = facts(read = 9_000, size = 1_500_000, name = "IMG_0001.jpg", type = "image/jpeg").after(touched)
        assertEquals(9_000, edited.modifiedMillis)
        assertEquals(facts(read = 3_000), facts(read = 3_000).after(null))
    }

    @Test
    fun cacheSurvivesAReloadAndADamagedFile() {
        val fs = FakeFileSystem()
        val cache = FileFactsCache(fs, DIR)
        assertTrue(cache.load().isEmpty())
        val edited = facts(read = 7, name = "IMG_0002.jpg", type = "image/jpeg").copy(originalMimeType = "image/heic", modifiedMillis = 3)
        val unread = FileFacts(4, null, null, null, null, null)
        val saved = mapOf("A/L0/001" to facts(read = 1), "B/L0/001" to edited, "C/L0/001" to unread)
        cache.save(saved)
        assertEquals(saved, FileFactsCache(fs, DIR).load())

        fs.write(DIR / "files.json") { writeUtf8("{not json") }
        assertTrue(FileFactsCache(fs, DIR).load().isEmpty())
        fs.write(DIR / "files.json") { writeUtf8("""{"v":99,"assets":{}}""") }
        assertTrue(FileFactsCache(fs, DIR).load().isEmpty())
    }
}
