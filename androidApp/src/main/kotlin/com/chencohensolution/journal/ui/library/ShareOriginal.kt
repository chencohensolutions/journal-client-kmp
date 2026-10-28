package com.chencohensolution.journal.ui.library

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.chencohensolution.journal.backup.JpegXl
import com.chencohensolution.journal.library.MediaItem
import com.chencohensolution.journal.library.MediaType
import com.chencohensolution.journal.server.ServerPreviews
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// The viewer's Share: the original, to any app, through Android's share sheet.

/**
 * Opens the share sheet with [item]'s original: the gallery's own file, or for a photo only on the server the original
 * the viewer downloaded (downloaded now when it isn't here, checked against its sha256). With [jpegXlAsJpeg] (Settings,
 * on by default) a JPEG XL packed from a JPEG goes as that JPEG, byte for byte, as few apps open JPEG XL; one that
 * wasn't goes as it is. False when the original can't be had (offline, or the server doesn't have it).
 */
internal suspend fun shareOriginal(context: Context, previews: ServerPreviews?, item: MediaItem, jpegXlAsJpeg: Boolean): Boolean {
    val asJpeg = jpegXlAsJpeg && item.isJpegXl
    val shared: Pair<Uri, String?> = if (item.isOnDevice) {
        val jpeg = if (asJpeg) withContext(Dispatchers.IO) { unpackedCopy(context, item) { readDevice(context, item) } } else null
        jpeg ?: (item.contentUri() to item.mimeType)
    } else {
        val sha = item.sha256 ?: return false
        val path = item.keptOriginal ?: previews?.original(sha, item.fileName ?: sha) ?: return false
        withContext(Dispatchers.IO) {
            (if (asJpeg) unpackedCopy(context, item) { File(path).readBytes() } else null)
                ?: (sharedCopy(context, File(path), item.fileName) to item.mimeType)
        }
    }
    val (uri, mimeType) = shared
    val type = mimeType ?: if (item.type == MediaType.VIDEO) "video/*" else "image/*"
    val send = Intent(Intent.ACTION_SEND)
        .setType(type)
        .putExtra(Intent.EXTRA_STREAM, uri)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    // The share sheet shows a preview of it, and passes the read permission on, from the clip.
    send.clipData = ClipData.newRawUri(item.fileName, uri)
    context.startActivity(Intent.createChooser(send, null))
    return true
}

/** A JPEG XL photo, by its type or name. */
private val MediaItem.isJpegXl: Boolean
    get() = type == MediaType.IMAGE &&
        (mimeType.equals("image/jxl", ignoreCase = true) || fileName?.endsWith(".jxl", ignoreCase = true) == true)

private fun readDevice(context: Context, item: MediaItem): ByteArray? =
    context.contentResolver.openInputStream(item.contentUri())?.use { it.readBytes() }

/**
 * The JPEG the JPEG XL [read] gives was packed from, as a copy in `cache/shared/` named as the photo with `.jpg`; null
 * when it can't be read or wasn't packed from a JPEG.
 */
private fun unpackedCopy(context: Context, item: MediaItem, read: () -> ByteArray?): Pair<Uri, String>? {
    val jpeg = runCatching { read() }.getOrNull()?.let(JpegXl::unpack) ?: return null
    val name = (safeName(item.fileName) ?: "photo").substringBeforeLast('.') + ".jpg"
    val copy = File(sharedDir(context), name).apply { writeBytes(jpeg) }
    return FileProvider.getUriForFile(context, "${context.packageName}.share", copy) to "image/jpeg"
}

/**
 * A copy of [original] in `cache/shared/`, named [fileName] (the server's cache calls every original "original"), as a
 * content URI other apps may read. Only the latest share is kept.
 */
private fun sharedCopy(context: Context, original: File, fileName: String?): Uri {
    val copy = original.copyTo(File(sharedDir(context), safeName(fileName) ?: original.name), overwrite = true)
    return FileProvider.getUriForFile(context, "${context.packageName}.share", copy)
}

/** `cache/shared/`, emptied: only the latest share is kept. */
private fun sharedDir(context: Context): File = File(context.cacheDir, "shared").apply {
    deleteRecursively()
    mkdirs()
}

private fun safeName(fileName: String?): String? =
    fileName?.substringAfterLast('/')?.substringAfterLast('\\')?.takeIf { it.isNotBlank() && it != "." && it != ".." }
