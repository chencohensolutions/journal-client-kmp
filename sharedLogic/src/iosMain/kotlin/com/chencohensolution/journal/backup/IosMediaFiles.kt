@file:OptIn(ExperimentalForeignApi::class)

package com.chencohensolution.journal.backup

import com.chencohensolution.journal.library.MediaItem
import com.chencohensolution.journal.library.currentResource
import kotlinx.cinterop.ExperimentalForeignApi
import okio.EOFException
import okio.FileSystem
import okio.IOException
import okio.Path
import okio.Source
import okio.use
import platform.Foundation.NSDate
import platform.Foundation.NSError
import platform.Foundation.NSISO8601DateFormatWithFractionalSeconds
import platform.Foundation.NSISO8601DateFormatWithInternetDateTime
import platform.Foundation.NSISO8601DateFormatter
import platform.Foundation.NSTimeZone
import platform.Foundation.NSURL
import platform.Foundation.dateWithTimeIntervalSince1970
import platform.Foundation.localTimeZone
import platform.Photos.PHAsset
import platform.Photos.PHAssetResourceManager
import platform.Photos.PHAssetResourceRequestOptions
import platform.darwin.DISPATCH_TIME_FOREVER
import platform.darwin.dispatch_semaphore_create
import platform.darwin.dispatch_semaphore_signal
import platform.darwin.dispatch_semaphore_wait

/**
 * Reads photos and videos for upload, read-only. PhotoKit can't open a file at an offset, so the file the library
 * reported (an edit's full-size render, a Live Photo's photo, the JPEG of RAW+JPEG; see `currentResource`) is copied
 * into [directory] (in Caches) and read from there. One copy is kept, for the photo being uploaded: the next photo
 * replaces it, and the end of the run removes it. Photos only in iCloud are downloaded first.
 */
internal class IosMediaFiles(private val directory: Path, private val fileSystem: FileSystem = FileSystem.SYSTEM) : MediaFiles {
    init {
        // Left by a run the app didn't see end.
        runEnded()
    }

    override fun open(item: MediaItem, offset: Long): Source {
        val path = copy(item)
        val handle = fileSystem.openReadOnly(path)
        // The source keeps the handle open until it is closed.
        return handle.use { it.source(offset) }
    }

    override fun takenAt(item: MediaItem): String = rfc3339(item.takenAtMillis)

    override fun runEnded() {
        runCatching { fileSystem.deleteRecursively(directory) }
    }

    /** The copy of [item]'s file, made now unless it is already there. */
    private fun copy(item: MediaItem): Path {
        val name = "${item.id.filter { it.isLetterOrDigit() }}-${item.sizeBytes}-${item.modifiedMillis}"
        val path = directory / name
        if (fileSystem.metadataOrNull(path)?.size == item.sizeBytes) return path
        fileSystem.deleteRecursively(directory)
        fileSystem.createDirectories(directory)

        val asset = PHAsset.fetchAssetsWithLocalIdentifiers(listOf(item.id), null).firstObject as PHAsset?
            ?: throw IOException("${item.fileName ?: item.id} is no longer in the library")
        val resource = currentResource(asset) ?: throw IOException("${item.fileName ?: item.id} has no file yet")
        val partial = directory / "$name.part"
        val options = PHAssetResourceRequestOptions().apply { networkAccessAllowed = true }
        val done = dispatch_semaphore_create(0)
        var failure: NSError? = null
        PHAssetResourceManager.defaultManager().writeDataForAssetResource(resource, NSURL.fileURLWithPath(partial.toString()), options) { error ->
            failure = error
            dispatch_semaphore_signal(done)
        }
        dispatch_semaphore_wait(done, DISPATCH_TIME_FOREVER)
        failure?.let { throw IOException("Can't read ${item.fileName ?: item.id}: ${it.localizedDescription}") }

        // Not the size the library reported: the photo changed since it was listed. The engine reads it again later.
        val size = fileSystem.metadataOrNull(partial)?.size
        if (size != item.sizeBytes) {
            fileSystem.delete(partial)
            throw EOFException("${item.fileName ?: item.id} is $size bytes, not ${item.sizeBytes}")
        }
        fileSystem.atomicMove(partial, path)
        return path
    }
}

/** [millis] as RFC 3339 with the device's offset at that moment, always with seconds: "2026-10-16T22:48:00.000+03:00". */
internal fun rfc3339(millis: Long): String {
    val formatter = NSISO8601DateFormatter()
    formatter.formatOptions = NSISO8601DateFormatWithInternetDateTime or NSISO8601DateFormatWithFractionalSeconds
    formatter.timeZone = NSTimeZone.localTimeZone
    return formatter.stringFromDate(NSDate.dateWithTimeIntervalSince1970(millis / 1000.0))
}
