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
import okio.ByteString.Companion.toByteString
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
    override suspend fun encodeFlac(item: MediaItem): EncodedFile? = withContext(Dispatchers.IO) {
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
        EncodedFile(
            fileName = name,
            size = file.length(),
            sha256 = sha,
            open = { offset -> file.inputStream().also { it.skip(offset) }.source() },
            discard = { file.delete() },
            localPath = file.path,
        )
    }

    override suspend fun replaceWithFlac(item: MediaItem, flac: EncodedFile): Boolean {
        val file = flac.localPath?.let(::File)?.takeIf { it.exists() } ?: return false
        return AndroidSoundRemoval(app).replace(item, file, flac.fileName)
    }

    /**
     * Made in the app's cache (`cache/jxl/`) with libjxl (the app's JPEG XL library): the JPEG read as it is, packed,
     * and unpacked again to check it is the very same JPEG, byte for byte, as the server checks it too.
     */
    override suspend fun encodeJxl(item: MediaItem): EncodedFile? = withContext(Dispatchers.IO) {
        val jpeg = open(item, 0).buffer().use { it.readByteArray() }
        val jxl = JpegXl.pack(jpeg) ?: return@withContext null
        val name = (item.fileName ?: "photo.jpg").substringBeforeLast('.') + ".jxl"
        val dir = File(app.cacheDir, "jxl").apply { mkdirs() }
        val file = File(dir, "${System.nanoTime()}.jxl")
        file.writeBytes(jxl)
        EncodedFile(
            fileName = name,
            size = file.length(),
            sha256 = jxl.toByteString().sha256().hex(),
            open = { offset -> file.inputStream().also { it.skip(offset) }.source() },
            discard = { file.delete() },
            localPath = file.path,
        )
    }

    override suspend fun replaceWithJxl(item: MediaItem, jxl: EncodedFile): Boolean {
        val file = jxl.localPath?.let(::File)?.takeIf { it.exists() } ?: return false
        return PhotoReplacement(app).replaceWithJxl(item, file, jxl.fileName)
    }

    /** FLACs and JPEG XLs a stopped run left in the cache. */
    override fun runEnded() {
        File(app.cacheDir, "flac").listFiles()?.forEach { it.delete() }
        File(app.cacheDir, "jxl").listFiles()?.forEach { it.delete() }
    }
}

/**
 * [millis] as RFC 3339 with the zone's offset at that moment: "2026-10-16T22:48:00.000+03:00". Always with seconds:
 * `OffsetDateTime.toString()` leaves them out on a whole minute, which the server refuses.
 */
internal fun rfc3339(millis: Long, zone: ZoneId): String =
    Instant.ofEpochMilli(millis).atZone(zone).format(RFC_3339)

private val RFC_3339: DateTimeFormatter = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSSXXX")
