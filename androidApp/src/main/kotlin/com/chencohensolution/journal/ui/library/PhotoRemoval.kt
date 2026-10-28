package com.chencohensolution.journal.ui.library

import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.net.Uri
import android.provider.MediaStore
import android.provider.Settings
import com.chencohensolution.journal.library.MediaItem

/**
 * The one place the app may take photos out of the phone's library (allowed by the owner on Oct 20, 2026, for "Free up
 * space" in Settings; a photo's "Delete from this phone" used it too until Oct 22, 2026). It never deletes anything
 * itself: it asks Android to move photos to the gallery's trash (kept there 30 days), with one prompt for all of
 * [items]. Android shows its own prompt, which the user confirms, unless the user gave the app "Media management" access
 * ([MediaStore.canManageMedia]). The caller must first have the server confirm it holds each photo (by sha256, and hash
 * both copies again). Without a prompt, in the background, it is AndroidPhotoTrash (the shared module). See
 * PhotoSafetyTest.
 */
internal fun moveToTrashRequest(context: Context, items: List<MediaItem>): IntentSender {
    require(items.all { it.isOnDevice }) { "only photos in the library can be moved to its trash" }
    return MediaStore.createTrashRequest(context.contentResolver, items.map { it.contentUri() }, true).intentSender
}

/** The system page where the user gives the app "Media management" access, so the trash needs no prompt each time. */
internal fun manageMediaSettingsIntent(context: Context): Intent =
    Intent(Settings.ACTION_REQUEST_MANAGE_MEDIA, Uri.fromParts("package", context.packageName, null))
