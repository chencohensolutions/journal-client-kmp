package com.chencohensolution.journal

import android.content.ContentUris
import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.provider.MediaStore
import androidx.test.platform.app.InstrumentationRegistry
import com.chencohensolution.journal.backup.JpegXl
import com.chencohensolution.journal.backup.PhotoReplacement
import com.chencohensolution.journal.library.AndroidMediaLibrary
import com.chencohensolution.journal.library.LocalDay
import com.chencohensolution.journal.library.MediaItem
import com.chencohensolution.journal.library.MediaType
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Backup's "Keep JPEG XL on this phone" on a real photo library (an emulator): the JPEG XL is written into the JPEG's
 * folder with its date, and the JPEG goes to the trash. Needs "Media management" access, given before the run with
 * `adb shell appops set com.chencohensolution.journal.sharedLogic.test MANAGE_MEDIA allow`; skipped without it.
 */
class PhotoReplacementDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val made = mutableListOf<android.net.Uri>()

    @AfterTest
    fun cleanUp() {
        // Only the photos this test made: the JPEG (trashed) and the JPEG XL.
        made.forEach { runCatching { context.contentResolver.delete(it, null, null) } }
    }

    @Test
    fun theJpegXlTakesTheJpegsPlace() = runBlocking {
        assumeTrue("needs Media management access", MediaStore.canManageMedia(context))
        val jpeg = photoJpeg()
        val takenAt = 1_761_638_400_000L
        val jpegUri = assertNotNull(context.contentResolver.insert(images, ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "jxl-test-${System.nanoTime()}.jpg")
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/JournalTest/")
            put(MediaStore.MediaColumns.DATE_TAKEN, takenAt)
        }))
        made += jpegUri
        context.contentResolver.openOutputStream(jpegUri)!!.use { it.write(jpeg) }
        val item = MediaItem(
            ContentUris.parseId(jpegUri).toString(), MediaType.IMAGE, takenAt, LocalDay(2025, 10, 28), null, 640, 480,
            jpeg.size.toLong(), "photo.jpg", mimeType = "image/jpeg",
        )
        val jxl = assertNotNull(JpegXl.pack(jpeg))
        val file = File(context.cacheDir, "test.jxl").apply { writeBytes(jxl) }
        val name = "jxl-test-${System.nanoTime()}.jxl"

        assertTrue(PhotoReplacement(context).replaceWithJxl(item, file, name))

        val args = Bundle().apply { putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_INCLUDE) }
        context.contentResolver.query(jpegUri, arrayOf(MediaStore.MediaColumns.IS_TRASHED), args, null)!!.use {
            assertTrue(it.moveToFirst() && it.getInt(0) == 1, "the JPEG is in the trash")
        }
        val columns = arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.MIME_TYPE, MediaStore.MediaColumns.RELATIVE_PATH)
        context.contentResolver.query(images, columns, "${MediaStore.MediaColumns.DISPLAY_NAME} = ?", arrayOf(name), null)!!.use {
            assertTrue(it.moveToFirst(), "the JPEG XL is in the library")
            val uri = ContentUris.withAppendedId(images, it.getLong(0))
            made += uri
            assertEquals("image/jxl", it.getString(1))
            assertEquals("Pictures/JournalTest/", it.getString(2))
            val kept = context.contentResolver.openInputStream(uri)!!.use { s -> s.readBytes() }
            assertContentEquals(jxl, kept)
            assertContentEquals(jpeg, JpegXl.unpack(kept), "the very same JPEG comes back")
        }
        // Android keeps no date for a JPEG XL: the app dates it as the JPEG it replaced.
        val shown = AndroidMediaLibrary(context).loadItems().single { it.fileName == name }
        assertEquals(takenAt, shown.takenAtMillis)
        assertEquals("image/jxl", shown.mimeType)
    }

    private val images = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

    private fun photoJpeg(): ByteArray {
        val bitmap = Bitmap.createBitmap(640, 480, Bitmap.Config.ARGB_8888)
        for (y in 0 until 480) for (x in 0 until 640) bitmap.setPixel(x, y, Color.rgb(x * 255 / 640, y * 255 / 480, (x * y) % 256))
        return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }.toByteArray()
    }
}
