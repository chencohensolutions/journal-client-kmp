package com.chencohensolution.journal.ui.library

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.chencohensolution.journal.library.MediaItem
import com.chencohensolution.journal.library.MediaType
import com.chencohensolution.journal.server.ServerPreviews
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// The viewer's Share: the original, to any app, through Android's share sheet.

/**
 * Opens the share sheet with [item]'s original: the gallery's own file, or for a photo only on the server the original
 * the viewer downloaded (downloaded now when it isn't here, checked against its sha256). False when the original can't
 * be had (offline, or the server doesn't have it).
 */
internal suspend fun shareOriginal(context: Context, previews: ServerPreviews?, item: MediaItem): Boolean {
    val uri = if (item.isOnDevice) {
        item.contentUri()
    } else {
        val sha = item.sha256 ?: return false
        val path = item.keptOriginal ?: previews?.original(sha, item.fileName ?: sha) ?: return false
        withContext(Dispatchers.IO) { sharedCopy(context, File(path), item.fileName) }
    }
    val type = item.mimeType ?: if (item.type == MediaType.VIDEO) "video/*" else "image/*"
    val send = Intent(Intent.ACTION_SEND)
        .setType(type)
        .putExtra(Intent.EXTRA_STREAM, uri)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    // The share sheet shows a preview of it, and passes the read permission on, from the clip.
    send.clipData = ClipData.newRawUri(item.fileName, uri)
    context.startActivity(Intent.createChooser(send, null))
    return true
}

/**
 * A copy of [original] in `cache/shared/`, named [fileName] (the server's cache calls every original "original"), as a
 * content URI other apps may read. Only the latest share is kept.
 */
private fun sharedCopy(context: Context, original: File, fileName: String?): Uri {
    val dir = File(context.cacheDir, "shared")
    dir.deleteRecursively()
    dir.mkdirs()
    val name = fileName?.substringAfterLast('/')?.substringAfterLast('\\')?.takeIf { it.isNotBlank() && it != "." && it != ".." }
        ?: original.name
    val copy = original.copyTo(File(dir, name), overwrite = true)
    return FileProvider.getUriForFile(context, "${context.packageName}.share", copy)
}
