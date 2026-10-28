package com.chencohensolution.journal.library

import android.content.Context
import android.media.ExifInterface
import com.chencohensolution.journal.backup.JpegXl
import java.io.ByteArrayInputStream
import java.io.File
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlinx.serialization.json.Json

/**
 * When the JPEG XL photos on this phone were taken. Android reads no date from a JPEG XL (it keeps its date taken at 0,
 * and won't take one set after), and the app then falls back to when the file was written: so backup's "Keep JPEG XL on
 * this phone" records the JPEG's date here as it writes the JPEG XL (in `files/jxl-dates.json`, by MediaStore id).
 * A JPEG XL not recorded (the app's data cleared, or one from elsewhere) is dated from the EXIF of the JPEG it gives
 * back, once, and recorded.
 */
internal class JxlDates private constructor(private val file: File) {
    private val dates: MutableMap<String, Long> =
        runCatching { Json.decodeFromString<Map<String, Long>>(file.readText()) }.getOrDefault(emptyMap()).toMutableMap()

    @Synchronized
    fun record(id: String, takenAtMillis: Long) {
        dates[id] = takenAtMillis
        save()
    }

    /** When the JPEG XL [id] was taken; [read] gives its bytes when it isn't recorded. Null when not known. */
    @Synchronized
    fun takenAt(id: String, read: () -> ByteArray?): Long? {
        dates[id]?.let { return it }
        val jpeg = runCatching { read() }.getOrNull()?.let(JpegXl::unpack) ?: return null
        val taken = exifTakenAt(jpeg) ?: return null
        dates[id] = taken
        save()
        return taken
    }

    private fun save() {
        val temp = File(file.path + ".tmp")
        temp.writeText(Json.encodeToString(dates.toMap()))
        temp.renameTo(file)
    }

    companion object {
        @Volatile
        private var instance: JxlDates? = null

        fun of(context: Context): JxlDates = instance ?: synchronized(this) {
            instance ?: JxlDates(File(context.applicationContext.filesDir, "jxl-dates.json")).also { instance = it }
        }

        private val EXIF_TIME = DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss")

        /** The JPEG's EXIF date taken, with its offset when the camera wrote one, else in this phone's zone. */
        internal fun exifTakenAt(jpeg: ByteArray): Long? = runCatching {
            val exif = ExifInterface(ByteArrayInputStream(jpeg))
            val time = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL) ?: exif.getAttribute(ExifInterface.TAG_DATETIME) ?: return null
            val local = LocalDateTime.parse(time.trim(), EXIF_TIME)
            val offset = exif.getAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL)?.let { runCatching { ZoneOffset.of(it.trim()) }.getOrNull() }
            if (offset != null) OffsetDateTime.of(local, offset).toInstant().toEpochMilli() else local.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        }.getOrNull()
    }
}
