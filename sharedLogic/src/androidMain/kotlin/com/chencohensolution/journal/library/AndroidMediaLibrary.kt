package com.chencohensolution.journal.library

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId

/** Reads photos and videos from MediaStore. Ids are MediaStore row ids. */
internal class AndroidMediaLibrary(context: Context) : MediaLibrary {
    private val context = context.applicationContext

    override fun access(): PhotoAccess = when {
        granted(Manifest.permission.READ_MEDIA_IMAGES) -> PhotoAccess.FULL
        granted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) -> PhotoAccess.LIMITED
        else -> PhotoAccess.DENIED
    }

    override suspend fun loadItems(): List<MediaItem> = withContext(Dispatchers.IO) {
        val zone = ZoneId.systemDefault()
        val items = mutableListOf<MediaItem>()
        context.contentResolver.query(
            MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL),
            PROJECTION,
            "${MediaStore.Files.FileColumns.MEDIA_TYPE} IN (?, ?)",
            arrayOf(
                MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE.toString(),
                MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO.toString(),
            ),
            null,
        )?.use { cursor ->
            val id = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
            val mediaType = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MEDIA_TYPE)
            val dateTaken = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_TAKEN)
            val dateModified = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
            val duration = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DURATION)
            val width = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.WIDTH)
            val height = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.HEIGHT)
            val size = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
            val name = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            while (cursor.moveToNext()) {
                val isVideo = cursor.getInt(mediaType) == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO
                // DATE_TAKEN is missing for files without EXIF; DATE_MODIFIED is in seconds.
                val takenAt = cursor.getLong(dateTaken).takeIf { it > 0 } ?: (cursor.getLong(dateModified) * 1000)
                val date = Instant.ofEpochMilli(takenAt).atZone(zone).toLocalDate()
                items += MediaItem(
                    id = cursor.getLong(id).toString(),
                    type = if (isVideo) MediaType.VIDEO else MediaType.IMAGE,
                    takenAtMillis = takenAt,
                    day = LocalDay(date.year, date.monthValue, date.dayOfMonth),
                    durationMillis = if (isVideo) cursor.getLong(duration).takeIf { it > 0 } else null,
                    width = cursor.getInt(width),
                    height = cursor.getInt(height),
                    sizeBytes = cursor.getLong(size).takeIf { it > 0 },
                    fileName = cursor.getString(name),
                    burstId = cursor.getString(name)?.let(::samsungBurstId),
                )
            }
        }
        items
    }

    private fun granted(permission: String) =
        context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    private companion object {
        /** Samsung names burst shots "20261008_140901_092.jpg": the second they started, then a shot number. */
        val SAMSUNG_BURST = Regex("""^(\d{8}_\d{6})_\d{3}\.\w+$""")

        fun samsungBurstId(fileName: String): String? = SAMSUNG_BURST.find(fileName)?.let { "samsung-${it.groupValues[1]}" }

        val PROJECTION = arrayOf(
            MediaStore.Files.FileColumns._ID,
            MediaStore.Files.FileColumns.MEDIA_TYPE,
            MediaStore.MediaColumns.DATE_TAKEN,
            MediaStore.MediaColumns.DATE_MODIFIED,
            MediaStore.MediaColumns.DURATION,
            MediaStore.MediaColumns.WIDTH,
            MediaStore.MediaColumns.HEIGHT,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DISPLAY_NAME,
        )
    }
}
