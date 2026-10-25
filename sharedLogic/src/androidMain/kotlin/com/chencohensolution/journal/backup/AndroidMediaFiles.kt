package com.chencohensolution.journal.backup

import android.content.ContentUris
import android.content.Context
import android.provider.MediaStore
import com.chencohensolution.journal.library.MediaItem
import com.chencohensolution.journal.library.MediaType
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okio.HashingSink
import okio.Source
import okio.blackholeSink
import okio.buffer
import okio.source
import java.io.File
import java.io.FileNotFoundException
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Reads photos and videos for upload, read-only. MediaStore removes GPS from what an app reads unless it may read
 * media locations and asks for the original ([MediaStore.setRequireOriginal]); the server must get the file
 * byte for byte, so its sha256 is the original's.
 */
internal class AndroidMediaFiles(context: Context) : MediaFiles {
    private val app = context.applicationContext
    private val resolver = app.contentResolver

    override fun open(item: MediaItem, offset: Long): Source {
        val uri = SoundIds.documentOf(item.id)?.let(Uri::parse) ?: run {
            val collection = if (item.type == MediaType.VIDEO) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            MediaStore.setRequireOriginal(ContentUris.withAppendedId(collection, item.id.toLong()))
        }
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

    override fun takenAt(item: MediaItem): String = rfc3339(item.takenAtMillis, ZoneId.systemDefault())

    /** Made in the app's cache (`cache/flac/`), from the WAV in its sound folder. */
    override suspend fun encodeFlac(item: MediaItem): EncodedFlac? = withContext(Dispatchers.IO) {
        val uri = SoundIds.documentOf(item.id)?.let(Uri::parse) ?: return@withContext null
        val name = (item.fileName ?: "recording.wav").substringBeforeLast('.') + ".flac"
        val dir = File(app.cacheDir, "flac").apply { mkdirs() }
        val file = File(dir, "${System.nanoTime()}.flac")
        val encoded = resolver.openInputStream(uri)?.use { FlacEncoder.encode(it, file) } ?: false
        if (!encoded) return@withContext null
        val sha = HashingSink.sha256(blackholeSink()).use { hashing ->
            file.source().buffer().use { it.readAll(hashing) }
            hashing.hash.hex()
        }
        EncodedFlac(
            fileName = name,
            size = file.length(),
            sha256 = sha,
            open = { offset -> file.inputStream().also { it.skip(offset) }.source() },
            discard = { file.delete() },
            localPath = file.path,
        )
    }

    override suspend fun replaceWithFlac(item: MediaItem, flac: EncodedFlac): Boolean {
        val file = flac.localPath?.let(::File)?.takeIf { it.exists() } ?: return false
        return AndroidSoundRemoval(app).replace(item, file, flac.fileName)
    }

    /** FLACs a stopped run left in the cache. */
    override fun runEnded() {
        File(app.cacheDir, "flac").listFiles()?.forEach { it.delete() }
    }
}

/**
 * [millis] as RFC 3339 with the zone's offset at that moment: "2026-10-16T22:48:00.000+03:00". Always with seconds:
 * `OffsetDateTime.toString()` leaves them out on a whole minute, which the server refuses.
 */
internal fun rfc3339(millis: Long, zone: ZoneId): String =
    Instant.ofEpochMilli(millis).atZone(zone).format(RFC_3339)

private val RFC_3339: DateTimeFormatter = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSSXXX")
