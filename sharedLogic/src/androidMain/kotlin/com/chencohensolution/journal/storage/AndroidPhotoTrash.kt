package com.chencohensolution.journal.storage

import android.app.ActivityOptions
import android.app.PendingIntent
import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import com.chencohensolution.journal.library.MediaItem
import com.chencohensolution.journal.library.MediaType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Moves photos to the gallery's trash (kept there 30 days) without a prompt, for "Free up space" in the background and
 * for a JPEG replaced by its JPEG XL (backup's "Keep JPEG XL on this phone"); only with "Media management" access.
 * Android may still refuse to start its request from the background: then the photos stay. The caller must first have
 * the server confirm it holds each photo. See PhotoRemoval.kt and PhotoSafetyTest.
 */
class AndroidPhotoTrash(context: Context) : PhotoTrash {
    private val context = context.applicationContext

    override fun canTrashWithoutAsking(): Boolean = MediaStore.canManageMedia(context)

    override suspend fun trash(items: List<MediaItem>): Set<String> {
        if (items.isEmpty() || !canTrashWithoutAsking() || !items.all { it.isOnDevice }) return emptySet()
        val request = MediaStore.createTrashRequest(context.contentResolver, items.map { it.libraryUri() }, true)
        val mode = if (Build.VERSION.SDK_INT >= 36) {
            ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOW_ALWAYS
        } else {
            @Suppress("DEPRECATION")
            ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
        }
        val options = ActivityOptions.makeBasic().setPendingIntentBackgroundActivityStartMode(mode).toBundle()
        try {
            request.send(context, 0, null, null, null, null, options)
        } catch (_: PendingIntent.CanceledException) {
            return emptySet()
        }
        // Granted without a prompt, but asynchronously: wait until the photos have left the library.
        val deadline = System.currentTimeMillis() + TRASH_WAIT_MILLIS + items.size * TRASH_WAIT_PER_FILE_MILLIS
        var gone = emptySet<String>()
        while (true) {
            gone = items.filterNot { inLibrary(it) }.mapTo(mutableSetOf()) { it.id }
            if (gone.size == items.size || System.currentTimeMillis() > deadline) return gone
            delay(TRASH_CHECK_MILLIS)
        }
    }

    /**
     * Whether [item] is still in the library, not trashed. Asked by its own URI, MediaStore still returns a trashed
     * photo, so its trash flag is read.
     */
    private suspend fun inLibrary(item: MediaItem): Boolean = withContext(Dispatchers.IO) {
        val args = Bundle().apply { putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_INCLUDE) }
        context.contentResolver.query(item.libraryUri(), arrayOf(MediaStore.MediaColumns.IS_TRASHED), args, null)?.use { cursor ->
            cursor.moveToFirst() && cursor.getInt(0) == 0
        } ?: false
    }

    private companion object {
        const val TRASH_WAIT_MILLIS = 5_000L
        const val TRASH_WAIT_PER_FILE_MILLIS = 100L
        const val TRASH_CHECK_MILLIS = 100L
    }
}

/** [this] photo's or video's row in MediaStore. */
internal fun MediaItem.libraryUri(): Uri = ContentUris.withAppendedId(
    if (type == MediaType.VIDEO) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
    id.toLong(),
)
