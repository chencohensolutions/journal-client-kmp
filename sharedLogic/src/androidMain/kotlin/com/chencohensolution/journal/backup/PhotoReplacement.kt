package com.chencohensolution.journal.backup

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import com.chencohensolution.journal.library.JxlDates
import com.chencohensolution.journal.library.MediaItem
import com.chencohensolution.journal.storage.AndroidPhotoTrash
import com.chencohensolution.journal.storage.libraryUri
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Backup's "Keep JPEG XL on this phone" (asked for by the owner on Oct 28, 2026, off by default): the one place the app
 * writes a file into the phone's photo library. Once the server confirmed the JPEG XL backup made of a JPEG photo, it
 * writes that JPEG XL into the JPEG's folder, as the app's own new photo (the JPEG's date kept in [JxlDates]), and then
 * moves the JPEG to the gallery's trash (kept there 30 days) through [AndroidPhotoTrash], which needs "Media management"
 * access. When
 * either step fails, the JPEG XL it wrote is deleted again (it is the app's own) and the JPEG stays. The phone's gallery
 * can't show JPEG XL (checked on a Galaxy S25, Android 17): only the app can. See PhotoSafetyTest.
 */
internal class PhotoReplacement(context: Context) {
    private val app = context.applicationContext
    private val trash = AndroidPhotoTrash(app)

    /** Writes [jxl] as [name] beside the JPEG [item] and moves the JPEG to the trash; false when it couldn't. */
    suspend fun replaceWithJxl(item: MediaItem, jxl: File, name: String): Boolean {
        if (!item.isOnDevice || !trash.canTrashWithoutAsking()) return false
        val written = withContext(Dispatchers.IO) { write(item, jxl, name) } ?: return false
        if (trash.trash(listOf(item)).contains(item.id)) return true
        withContext(Dispatchers.IO) { runCatching { app.contentResolver.delete(written, null, null) } }
        return false
    }

    /** [jxl] as a new photo in [item]'s folder, with its date; null when MediaStore refused it or it isn't whole. */
    private fun write(item: MediaItem, jxl: File, name: String): Uri? {
        val folder = app.contentResolver.query(item.libraryUri(), arrayOf(MediaStore.MediaColumns.RELATIVE_PATH), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        } ?: return null
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jxl")
            put(MediaStore.MediaColumns.RELATIVE_PATH, folder)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        // Older Androids don't know image/jxl, and refuse it in the photo library.
        val uri = runCatching { app.contentResolver.insert(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values) }.getOrNull()
            ?: return null
        val whole = runCatching {
            app.contentResolver.openOutputStream(uri, "w")!!.use { out -> jxl.inputStream().use { it.copyTo(out) } } == jxl.length()
        }.getOrDefault(false)
        // Published, Android scans the file, reads no date from a JPEG XL and keeps 0, and takes none set after: the
        // app keeps the JPEG's date itself (JxlDates), before the library can see the file.
        if (whole) JxlDates.of(app).record(ContentUris.parseId(uri).toString(), item.takenAtMillis)
        val published = whole && runCatching {
            app.contentResolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null) == 1
        }.getOrDefault(false)
        if (published) return uri
        runCatching { app.contentResolver.delete(uri, null, null) }
        return null
    }
}
