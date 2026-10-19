package com.chencohensolution.journal.ui.library

import android.content.Context
import android.content.IntentSender
import android.provider.MediaStore
import com.chencohensolution.journal.library.MediaItem

/**
 * The one place the app may take a photo out of the phone's library (allowed by the owner on Oct 20, 2026: "Delete from
 * this phone" in a photo's details). It never deletes anything itself: it asks Android to move the photo to the
 * gallery's trash, and Android shows its own prompt, which the user confirms. The caller must first have the server
 * confirm it holds the photo (by sha256) and keep its previews, so the photo keeps showing. See PhotoSafetyTest.
 */
internal fun moveToTrashRequest(context: Context, item: MediaItem): IntentSender {
    require(item.isOnDevice) { "only a photo in the library can be moved to its trash" }
    return MediaStore.createTrashRequest(context.contentResolver, listOf(item.contentUri()), true).intentSender
}
