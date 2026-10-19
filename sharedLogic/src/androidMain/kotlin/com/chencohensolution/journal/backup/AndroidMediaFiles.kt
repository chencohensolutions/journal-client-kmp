package com.chencohensolution.journal.backup

import android.content.ContentUris
import android.content.Context
import android.provider.MediaStore
import com.chencohensolution.journal.library.MediaItem
import com.chencohensolution.journal.library.MediaType
import okio.Source
import okio.source
import java.io.FileNotFoundException
import java.time.Instant
import java.time.ZoneId

/**
 * Reads photos and videos for upload, read-only. MediaStore removes GPS from what an app reads unless it may read
 * media locations and asks for the original ([MediaStore.setRequireOriginal]); the server must get the file
 * byte for byte, so its sha256 is the original's.
 */
internal class AndroidMediaFiles(context: Context) : MediaFiles {
    private val resolver = context.applicationContext.contentResolver

    override fun open(item: MediaItem, offset: Long): Source {
        val id = item.id.toLong()
        val collection = if (item.type == MediaType.VIDEO) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val uri = MediaStore.setRequireOriginal(ContentUris.withAppendedId(collection, id))
        val stream = resolver.openInputStream(uri) ?: throw FileNotFoundException("Can't read ${item.fileName ?: item.id}")
        var skipped = 0L
        while (skipped < offset) {
            val n = stream.skip(offset - skipped)
            if (n <= 0) {
                stream.close()
                throw okio.EOFException("${item.fileName ?: item.id} is shorter than $offset bytes")
            }
            skipped += n
        }
        return stream.source()
    }

    override fun takenAt(item: MediaItem): String =
        Instant.ofEpochMilli(item.takenAtMillis).atZone(ZoneId.systemDefault()).toOffsetDateTime().toString()
}
