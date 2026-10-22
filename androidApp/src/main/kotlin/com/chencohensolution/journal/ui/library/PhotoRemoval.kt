package com.chencohensolution.journal.ui.library

import android.app.ActivityOptions
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.provider.Settings
import com.chencohensolution.journal.library.MediaItem
import com.chencohensolution.journal.storage.PhotoTrash
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * The one place the app may take photos out of the phone's library (allowed by the owner on Oct 20, 2026, for "Free up
 * space" in Settings; a photo's "Delete from this phone" used it too until Oct 22, 2026). It never deletes anything
 * itself: it asks Android to move photos to the gallery's trash (kept there 30 days), with one prompt for all of
 * [items]. Android shows its own prompt, which the user confirms, unless the user gave the app "Media management" access
 * ([MediaStore.canManageMedia]). The caller must first have the server confirm it holds each photo (by sha256, and hash
 * both copies again). See PhotoSafetyTest.
 */
internal fun moveToTrashRequest(context: Context, items: List<MediaItem>): IntentSender {
    require(items.all { it.isOnDevice }) { "only photos in the library can be moved to its trash" }
    return MediaStore.createTrashRequest(context.contentResolver, items.map { it.contentUri() }, true).intentSender
}

/** The system page where the user gives the app "Media management" access, so the trash needs no prompt each time. */
internal fun manageMediaSettingsIntent(context: Context): Intent =
    Intent(Settings.ACTION_REQUEST_MANAGE_MEDIA, Uri.fromParts("package", context.packageName, null))

/**
 * Moves photos to the trash without a prompt, for "Free up space" in the background; only with "Media management"
 * access. Android may still refuse to start its request from the background: then the photos stay, and wait for one
 * prompt in the app.
 */
internal class AndroidPhotoTrash(context: Context) : PhotoTrash {
    private val context = context.applicationContext

    override fun canTrashWithoutAsking(): Boolean = MediaStore.canManageMedia(context)

    override suspend fun trash(items: List<MediaItem>): Set<String> {
        if (items.isEmpty() || !canTrashWithoutAsking() || !items.all { it.isOnDevice }) return emptySet()
        val request = MediaStore.createTrashRequest(context.contentResolver, items.map { it.contentUri() }, true)
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
        context.contentResolver.query(item.contentUri(), arrayOf(MediaStore.MediaColumns.IS_TRASHED), args, null)?.use { cursor ->
            cursor.moveToFirst() && cursor.getInt(0) == 0
        } ?: false
    }

    private companion object {
        const val TRASH_WAIT_MILLIS = 5_000L
        const val TRASH_WAIT_PER_FILE_MILLIS = 100L
        const val TRASH_CHECK_MILLIS = 100L
    }
}
