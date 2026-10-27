package com.chencohensolution.journal.library

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId

/**
 * Reads photos and videos from MediaStore. Ids are MediaStore row ids. [changesSince] reads only what changed since a
 * mark, by MediaStore's generations (docs/plans/library-on-disk.md, phase 2).
 */
internal class AndroidMediaLibrary(context: Context) : ChangingLibrary {
    private val context = context.applicationContext

    override fun access(): PhotoAccess = when {
        granted(Manifest.permission.READ_MEDIA_IMAGES) -> PhotoAccess.FULL
        granted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) -> PhotoAccess.LIMITED
        else -> PhotoAccess.DENIED
    }

    override suspend fun loadItems(): List<MediaItem> = withContext(Dispatchers.IO) { read(MediaStore.VOLUME_EXTERNAL) }

    /**
     * What changed since [mark]: per volume, the items whose generation is past the one kept; a volume whose version
     * changed (MediaStore was rebuilt) is read whole, and so is everything when the time zone (days depend on it), the
     * access or [READER_VERSION] changed, or with access to selected photos only (picking more changes no
     * generation). An ids-only pass finds what was deleted.
     */
    override suspend fun changesSince(mark: String?): LibraryChanges = withContext(Dispatchers.IO) {
        val access = access()
        val head = "$READER_VERSION|${ZoneId.systemDefault().id}|$access"
        // Generations are read before the items: what changes meanwhile is read again next time, never missed.
        val volumes = MediaStore.getExternalVolumeNames(context).associateWith { volume ->
            MediaStore.getVersion(context, volume) to MediaStore.getGeneration(context, volume)
        }
        val newMark = head + volumes.entries.joinToString("") { (volume, at) -> ";$volume=${at.first}:${at.second}" }
        val old = mark?.split(";")?.takeIf { it.first() == head && access == PhotoAccess.FULL }
            ?.drop(1)?.associate { it.substringBefore("=") to it.substringAfter("=") }
        if (old == null) {
            val all = read(MediaStore.VOLUME_EXTERNAL)
            return@withContext LibraryChanges(newMark, full = true, changed = all, present = all.mapTo(HashSet()) { it.id })
        }
        val changed = volumes.flatMap { (volume, at) ->
            val before = old[volume]
            val version = before?.substringBeforeLast(":")
            val generation = before?.substringAfterLast(":")?.toLongOrNull()
            if (version != at.first || generation == null) {
                read(volume)
            } else {
                read(volume, "${MediaStore.MediaColumns.GENERATION_MODIFIED} > ?", arrayOf(generation.toString()))
            }
        }
        LibraryChanges(newMark, full = false, changed = changed, present = ids())
    }

    override suspend fun items(ids: Collection<String>): List<MediaItem> = withContext(Dispatchers.IO) {
        ids.mapNotNull { it.toLongOrNull() }.chunked(500).flatMap { chunk ->
            read(MediaStore.VOLUME_EXTERNAL, "${MediaStore.Files.FileColumns._ID} IN (${chunk.joinToString(",")})")
        }
    }

    /** The ids of every photo and video. */
    private fun ids(): Set<String> {
        val ids = HashSet<String>()
        context.contentResolver.query(
            MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL), arrayOf(MediaStore.Files.FileColumns._ID), MEDIA_TYPES, MEDIA_TYPE_ARGS, null,
        )?.use { cursor -> while (cursor.moveToNext()) ids += cursor.getLong(0).toString() }
        return ids
    }

    /** The photos and videos of [volume] that [selection] (with [args]) picks; all of them without one. */
    private fun read(volume: String, selection: String? = null, args: Array<String> = emptyArray()): List<MediaItem> {
        val zone = ZoneId.systemDefault()
        val items = mutableListOf<MediaItem>()
        context.contentResolver.query(
            MediaStore.Files.getContentUri(volume),
            PROJECTION,
            if (selection == null) MEDIA_TYPES else "$MEDIA_TYPES AND $selection",
            MEDIA_TYPE_ARGS + args,
            null,
        )?.use { cursor ->
            val id = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
            val mediaType = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MEDIA_TYPE)
            val dateTaken = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_TAKEN)
            val dateModified = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
            val duration = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DURATION)
            val width = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.WIDTH)
            val height = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.HEIGHT)
            val orientation = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.ORIENTATION)
            val size = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
            val name = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            val folder = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.RELATIVE_PATH)
            val mime = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
            val owner = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.OWNER_PACKAGE_NAME)
            val appNames = HashMap<String, String?>()
            while (cursor.moveToNext()) {
                val isVideo = cursor.getInt(mediaType) == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO
                // DATE_TAKEN is missing for files without EXIF; DATE_MODIFIED is in seconds.
                val takenAt = cursor.getLong(dateTaken).takeIf { it > 0 } ?: (cursor.getLong(dateModified) * 1000)
                val date = Instant.ofEpochMilli(takenAt).atZone(zone).toLocalDate()
                // WIDTH and HEIGHT are the stored pixels; a photo turned a quarter is shown the other way round.
                val turned = cursor.getInt(orientation) % 180 == 90
                items += MediaItem(
                    id = cursor.getLong(id).toString(),
                    type = if (isVideo) MediaType.VIDEO else MediaType.IMAGE,
                    takenAtMillis = takenAt,
                    day = LocalDay(date.year, date.monthValue, date.dayOfMonth),
                    durationMillis = if (isVideo) cursor.getLong(duration).takeIf { it > 0 } else null,
                    width = cursor.getInt(if (turned) height else width),
                    height = cursor.getInt(if (turned) width else height),
                    sizeBytes = cursor.getLong(size).takeIf { it > 0 },
                    fileName = cursor.getString(name),
                    burstId = cursor.getString(name)?.let(::samsungBurstId),
                    source = sourceOfFolder(
                        cursor.getString(folder),
                        cursor.getString(owner)?.takeIf(::isSourceApp)?.let { appNames.getOrPut(it) { appName(it) } },
                    ),
                    modifiedMillis = cursor.getLong(dateModified).takeIf { it > 0 }?.let { it * 1000 },
                    mimeType = cursor.getString(mime),
                    folder = cursor.getString(folder),
                )
            }
        }
        return items
    }

    override fun watch(onChange: () -> Unit): () -> Unit {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) = onChange()
        }
        val resolver = context.contentResolver
        resolver.registerContentObserver(MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL), true, observer)
        return { resolver.unregisterContentObserver(observer) }
    }

    /**
     * The name an app shows under its icon ("Petcube", "ChatGPT"), for files it saved loose in Pictures or Download;
     * null when it isn't installed any more or can't be seen (the manifest's <queries> shows apps with a launcher icon).
     */
    private fun appName(packageName: String): String? = runCatching {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString().takeIf { it.isNotBlank() && it != packageName }
    }.getOrNull()

    private fun granted(permission: String) =
        context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    private companion object {
        /**
         * Bumped when what is read from a row changes (a new column, a new rule for sources), so every phone reads its
         * library whole once.
         */
        const val READER_VERSION = 1

        val MEDIA_TYPES = "${MediaStore.Files.FileColumns.MEDIA_TYPE} IN (?, ?)"
        val MEDIA_TYPE_ARGS = arrayOf(
            MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE.toString(),
            MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO.toString(),
        )

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
            MediaStore.MediaColumns.ORIENTATION,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.RELATIVE_PATH,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.OWNER_PACKAGE_NAME,
        )
    }
}
