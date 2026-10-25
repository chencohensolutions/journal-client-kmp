@file:OptIn(ExperimentalForeignApi::class)

package com.chencohensolution.journal.backup

import com.chencohensolution.journal.library.MediaItem
import com.chencohensolution.journal.library.currentResource
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import okio.HashingSink
import okio.blackholeSink
import okio.buffer
import platform.AVFAudio.AVAudioFile
import platform.AVFAudio.AVAudioPCMBuffer
import platform.AVFAudio.AVFormatIDKey
import platform.AVFAudio.AVNumberOfChannelsKey
import platform.AVFAudio.AVSampleRateKey
import platform.CoreAudioTypes.kAudioFormatFLAC
import platform.Foundation.NSNumber
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import okio.EOFException
import okio.FileSystem
import okio.IOException
import okio.Path
import okio.Path.Companion.toPath
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
 * into [directory] (in Caches) and read from there; so are the files of the sound folders the user picked ([sounds]),
 * whose WAVs are sent as FLAC made here. A copy is kept while the run reads it: one for the photo being
 * hashed, and one per file being uploaded (the engine sends several at once, bounded by the sender's limits). Each
 * goes when the engine is done with it ([release]), and the end of the run removes any left. Photos only in iCloud are
 * downloaded first. Safe to use for several photos at once.
 */
internal class IosMediaFiles(
    private val directory: Path,
    private val fileSystem: FileSystem = FileSystem.SYSTEM,
    private val sounds: IosSoundFolders? = null,
) : MediaFiles {
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

    override fun release(item: MediaItem) {
        runCatching { fileSystem.delete(directory / name(item)) }
    }

    override fun runEnded() {
        runCatching { fileSystem.deleteRecursively(directory) }
    }

    /** Made in [directory] from the WAV copied there, with the FLAC encoder iOS has (AVAudioFile). */
    override suspend fun encodeFlac(item: MediaItem): EncodedFlac? = withContext(Dispatchers.IO) {
        if (sounds == null || !SoundIds.isSound(item.id)) return@withContext null
        val wav = runCatching { copy(item) }.getOrNull() ?: return@withContext null
        val flac = directory / "${name(item)}.flac"
        if (!encode(wav.toString(), flac.toString())) {
            fileSystem.delete(flac)
            return@withContext null
        }
        val sha = HashingSink.sha256(blackholeSink()).use { hashing ->
            fileSystem.source(flac).buffer().use { it.readAll(hashing) }
            hashing.hash.hex()
        }
        EncodedFlac(
            fileName = (item.fileName ?: "recording.wav").substringBeforeLast('.') + ".flac",
            size = fileSystem.metadata(flac).size ?: 0,
            sha256 = sha,
            open = { offset -> fileSystem.openReadOnly(flac).use { it.source(offset) } },
            discard = { fileSystem.delete(flac) },
            localPath = flac.toString(),
        )
    }

    override suspend fun replaceWithFlac(item: MediaItem, flac: EncodedFlac): Boolean {
        val path = flac.localPath?.takeIf { fileSystem.exists(it.toPath()) } ?: return false
        return sounds?.replace(item, path, flac.fileName) ?: false
    }

    /** [from] (a WAV) as FLAC in [to], same rate and channels, losslessly; false when iOS can't read or write it. */
    private fun encode(from: String, to: String): Boolean = memScoped {
        val error = alloc<ObjCObjectVar<NSError?>>()
        val input = AVAudioFile(forReading = NSURL.fileURLWithPath(from), error = error.ptr)
        if (error.value != null) return false
        val format = input.processingFormat
        val settings = mapOf<Any?, Any>(
            AVFormatIDKey to NSNumber(unsignedInt = kAudioFormatFLAC),
            AVSampleRateKey to NSNumber(double = format.sampleRate),
            AVNumberOfChannelsKey to NSNumber(unsignedInt = format.channelCount),
        )
        val output = AVAudioFile(forWriting = NSURL.fileURLWithPath(to), settings = settings, commonFormat = format.commonFormat, interleaved = format.isInterleaved(), error = error.ptr)
        if (error.value != null) return false
        val buffer = AVAudioPCMBuffer(pCMFormat = format, frameCapacity = 1u shl 16) ?: return false
        try {
            // Read up to its length: at the end a read answers false without an error.
            while (input.framePosition < input.length) {
                if (!input.readIntoBuffer(buffer, error = error.ptr) || buffer.frameLength == 0u) return false
                if (!output.writeFromBuffer(buffer, error = error.ptr)) return false
            }
            return true
        } finally {
            output.close()
            input.close()
        }
    }

    private fun name(item: MediaItem) = "${item.id.filter { it.isLetterOrDigit() }.takeLast(80)}-${item.sizeBytes}-${item.modifiedMillis}"

    /** The copy of [item]'s file, made now unless it is already there. */
    private fun copy(item: MediaItem): Path {
        val name = name(item)
        val path = directory / name
        if (fileSystem.metadataOrNull(path)?.size == item.sizeBytes) return path
        fileSystem.createDirectories(directory)
        if (SoundIds.isSound(item.id)) {
            val partial = directory / "$name.part"
            if (sounds?.copy(item, partial.toString()) != true) throw IOException("Can't read ${item.fileName ?: item.id} in its folder")
            if (fileSystem.metadataOrNull(partial)?.size != item.sizeBytes) {
                fileSystem.delete(partial)
                throw EOFException("${item.fileName ?: item.id} changed since it was listed")
            }
            fileSystem.atomicMove(partial, path)
            return path
        }

        val asset = PHAsset.fetchAssetsWithLocalIdentifiers(listOf(item.id), null).firstObject as PHAsset?
            ?: throw IOException("${item.fileName ?: item.id} is no longer in the library")
        val resource = currentResource(asset) ?: throw IOException("${item.fileName ?: item.id} has no file yet")
        val partial = directory / "$name.part"
        fileSystem.delete(partial) // PhotoKit won't write over a file.
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
