package com.chencohensolution.journal

import android.graphics.Bitmap
import android.graphics.Color
import android.media.ExifInterface
import androidx.test.platform.app.InstrumentationRegistry
import com.chencohensolution.journal.backup.JpegXl
import com.chencohensolution.journal.library.JxlDates
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.OffsetDateTime
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * libjxl on Android (the app's JPEG XL library): a JPEG packed as JPEG XL is smaller and gives back the very same JPEG,
 * EXIF and all, as the server checks it does (journal-server-go media/jxl.go).
 */
class JpegXlDeviceTest {
    @Test
    fun aJpegWithExifComesBackByteForByte() {
        val jpeg = withExif(photoJpeg())
        val jxl = assertNotNull(JpegXl.pack(jpeg), "a color photo with EXIF packs")
        assertTrue(jxl.size < jpeg.size, "${jxl.size} bytes, the JPEG ${jpeg.size}")
        assertTrue(JpegXl.isJpegXl(jxl))
        assertContentEquals(jpeg, JpegXl.unpack(jxl))
    }

    @Test
    fun whatIsntAJpegXlOfAJpegIsntUnpacked() {
        val jpeg = photoJpeg()
        assertNull(JpegXl.unpack(jpeg), "a JPEG isn't a JPEG XL")
        assertNull(JpegXl.pack(byteArrayOf(1, 2, 3)), "not a JPEG")
    }

    @Test
    fun aJpegXlIsDatedByItsJpegsExif() {
        val file = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "dated.jpg")
        file.writeBytes(photoJpeg())
        ExifInterface(file.path).apply {
            setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, "2025:10:28 10:15:00")
            setAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL, "+02:00")
            saveAttributes()
        }
        val jxl = assertNotNull(JpegXl.pack(file.readBytes()))
        val jpeg = assertNotNull(JpegXl.unpack(jxl))
        assertEquals(OffsetDateTime.parse("2025-10-28T10:15:00+02:00").toInstant().toEpochMilli(), JxlDates.exifTakenAt(jpeg))
    }

    /** A photo-like JPEG: smooth colors with some noise, as libjxl packs a camera's. */
    private fun photoJpeg(): ByteArray {
        val bitmap = Bitmap.createBitmap(640, 480, Bitmap.Config.ARGB_8888)
        var seed = 7
        for (y in 0 until 480) for (x in 0 until 640) {
            seed = seed * 1103515245 + 12345
            val noise = (seed ushr 16) and 15
            bitmap.setPixel(x, y, Color.rgb((x * 255 / 640 + noise) % 256, (y * 255 / 480 + noise) % 256, (x + y + noise) % 256))
        }
        return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }.toByteArray()
    }

    /** [jpeg] with an EXIF segment (APP1) right after its start: a TIFF header with one empty directory. */
    private fun withExif(jpeg: ByteArray): ByteArray {
        val tiff = byteArrayOf(0x49, 0x49, 0x2A, 0, 8, 0, 0, 0, 0, 0, 0, 0, 0, 0)
        val payload = "Exif".encodeToByteArray() + byteArrayOf(0, 0) + tiff
        val length = payload.size + 2
        val app1 = byteArrayOf(0xFF.toByte(), 0xE1.toByte(), (length shr 8).toByte(), length.toByte()) + payload
        return jpeg.copyOfRange(0, 2) + app1 + jpeg.copyOfRange(2, jpeg.size)
    }
}
