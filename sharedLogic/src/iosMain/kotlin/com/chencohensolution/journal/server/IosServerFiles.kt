@file:OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)

package com.chencohensolution.journal.server

import com.chencohensolution.journal.connection.ObservationHandle
import com.chencohensolution.journal.library.MediaItem
import com.chencohensolution.journal.library.MediaType
import com.chencohensolution.journal.storage.PhoneStorage
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.IO
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import platform.Foundation.NSData
import platform.Foundation.create
import platform.UIKit.UIImage
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.ceil

/** Swift's decoders (ImageIO, AVFoundation) for what [ServerPreviews] keeps. Called off the main thread. */
interface ServerImageDecoding {
    /** A file (a kept original; a video's frame) with its longer side at most [maxSide] pixels; null if it can't. */
    fun decodeFile(path: String, isVideo: Boolean, maxSide: Int): UIImage?

    /** A preview the server made, in any format it makes. */
    fun decodeData(data: NSData): UIImage?
}

/**
 * The server's files as Swift asks for them: images of server-only photos ([ServerPreviews.tile], [ServerPreviews.viewer]),
 * where an original is on this phone, Mark private, what is kept here, and Settings → Storage. Swift
 * can't await Kotlin's suspend functions on its own threads safely, so each takes a callback, called on the main thread
 * (with null when cancelled), and returns a handle that cancels it.
 */
class IosServerFiles internal constructor(
    val library: ServerLibrary,
    private val previews: ServerPreviews,
    private val originals: KeptOriginals,
    private val storage: PhoneStorage,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** Set once by the app, before any image is asked for. */
    var decoding: ServerImageDecoding? = null

    /** Whether photos are shown from previews (else from their originals, downloaded). */
    val usesPreviews: Boolean get() = previews.usesPreviews

    /**
     * [item] (a server-only photo or video, or one whose original was downloaded) to cover [width] × [height] pixels: the
     * kept original, else [ServerPreviews.tile] at the photo's shape scaled to cover the box, the size Android asks for.
     */
    fun tile(item: MediaItem, width: Int, height: Int, onResult: (UIImage?) -> Unit): ObservationHandle = run(onResult) {
        val decoding = decoding ?: return@run null
        val (w, h) = coverSize(item, width, height)
        val kept = item.keptOriginal
        if (kept != null) return@run withContext(fullDecodes) { decoding.decodeFile(kept, item.type == MediaType.VIDEO, maxOf(w, h)) }
        val sha = item.sha256 ?: return@run null
        previews.tile(sha, item.type == MediaType.VIDEO, item.fileName, w, h, decoders(decoding, item, maxOf(w, h)))
    }

    /** A server-only photo opened on its own, fitted in [maxSide]: [ServerPreviews.viewer] (its original, downloaded). */
    fun viewer(item: MediaItem, maxSide: Int, onResult: (UIImage?) -> Unit): ObservationHandle = run(onResult) {
        val decoding = decoding ?: return@run null
        val kept = item.keptOriginal
        if (kept != null) return@run withContext(fullDecodes) { decoding.decodeFile(kept, item.type == MediaType.VIDEO, maxSide) }
        val sha = item.sha256 ?: return@run null
        previews.viewer(sha, item.fileName, maxSide, decoders(decoding, item, maxSide))
    }

    /** The largest preview of [item] kept on this phone, never fetched: what shows while its original downloads. */
    fun largestPreview(item: MediaItem, onResult: (UIImage?) -> Unit): ObservationHandle = run(onResult) {
        val decoding = decoding ?: return@run null
        val sha = item.sha256 ?: return@run null
        previews.largestPreview(sha)?.let { bytes -> withContext(Dispatchers.IO) { decoding.decodeData(bytes.toNSData()) } }
    }

    /** Marks [item] private on the server ([isPrivate]), or no longer private; [onDone] gets whether it worked. */
    fun setPrivate(item: MediaItem, isPrivate: Boolean, onDone: (Boolean) -> Unit): ObservationHandle = run({ onDone(it ?: false) }) {
        val sha = item.sha256 ?: return@run false
        library.setPrivate(listOf(sha), isPrivate)
    }

    /** What is kept here of [item] (a server-only photo or video): [PhoneStorage.keptHere]. */
    fun keptHere(item: MediaItem, onResult: (KeptHere?) -> Unit): ObservationHandle = run(onResult) {
        val sha = item.sha256 ?: return@run null
        storage.keptHere(sha, item.type == MediaType.VIDEO, item.width, item.height)
    }

    /** Bytes kept in all (Settings → Storage on this phone), after applying a lowered limit. */
    fun usedBytes(onResult: (Long) -> Unit): ObservationHandle = run({ onResult(it ?: 0) }) {
        storage.trimToLimit()
        storage.usedBytes()
    }

    /** "Clear": deletes previews, downloaded-to-show originals and streamed videos; [onDone] gets what is left. */
    fun clear(onDone: (Long) -> Unit): ObservationHandle = run({ onDone(it ?: 0) }) {
        storage.clear()
        storage.usedBytes()
    }

    /** The limit in all, in bytes. */
    val limitBytes: Long get() = storage.limit

    // Observing StateFlows that have no observe of their own, for Swift.

    fun observeUsage(onChange: (StorageUsage?) -> Unit): ObservationHandle = observe(library.usage, onChange)

    fun observeMedia(onChange: (List<ServerMedia>) -> Unit): ObservationHandle = observe(library.media, onChange)

    /** The sha256 of each original kept here. */
    fun observeKept(onChange: (Set<String>) -> Unit): ObservationHandle = observe(originals.kept) { onChange(it.keys) }

    /**
     * How far [item]'s original is downloading into the cache now (0..1; 0 while its size isn't known), or null when
     * it isn't downloading: the viewer's cloud, whichever screen started the download.
     */
    fun observeOriginalProgress(item: MediaItem, onChange: (Float?) -> Unit): ObservationHandle =
        observe(previews.originalProgress) { downloads -> onChange(item.sha256?.let { downloads[it] }) }

    /** Whether [item]'s original is on this phone already (kept, or in the cache): showing it downloads nothing. */
    fun hasOriginal(item: MediaItem, onResult: (Boolean) -> Unit): ObservationHandle = run({ onResult(it ?: false) }) {
        item.keptOriginal != null || item.sha256?.let { previews.cachedOriginal(it) } != null
    }

    /**
     * The file of [item]'s original on this phone, to read its details (EXIF, codecs) from: the kept original, else the
     * one downloaded into the preview cache; null while it isn't here (nothing is downloaded for this).
     */
    fun originalPath(item: MediaItem, onResult: (String?) -> Unit): ObservationHandle = run(onResult) {
        item.keptOriginal ?: item.sha256?.let { previews.cachedOriginal(it) }
    }

    private fun <T> observe(flow: StateFlow<T>, onChange: (T) -> Unit): ObservationHandle {
        val job = scope.launch { flow.collect(onChange) }
        return ObservationHandle { job.cancel() }
    }

    /** Runs [block] and calls [onResult] exactly once: with its result, or null when it failed or was cancelled. */
    private fun <T : Any> run(onResult: (T?) -> Unit, block: suspend () -> T?): ObservationHandle {
        var result: T? = null
        val job = scope.launch {
            result = try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
        }
        // A cancelled job completes on the canceller's thread: the answer still goes out on the main thread.
        job.invokeOnCompletion { cause -> if (cause == null) onResult(result) else scope.launch { onResult(null) } }
        return ObservationHandle { job.cancel() }
    }

    private fun decoders(decoding: ServerImageDecoding, item: MediaItem, maxSide: Int) = ServerPreviews.Decoders(
        original = { path -> withContext(fullDecodes) { decoding.decodeFile(path, item.type == MediaType.VIDEO, maxSide) } },
        preview = { bytes -> withContext(Dispatchers.IO) { decoding.decodeData(bytes.toNSData()) } },
    )

    private companion object {
        /** Decodes of whole originals take far longer than previews: a few at a time. */
        @OptIn(ExperimentalCoroutinesApi::class)
        val fullDecodes = Dispatchers.IO.limitedParallelism(3)
    }
}

/** The photo's own shape scaled to cover [width] × [height]; the box itself when the shape is unknown. */
private fun coverSize(item: MediaItem, width: Int, height: Int): Pair<Int, Int> {
    if (item.width <= 0 || item.height <= 0) return width to height
    val scale = maxOf(width.toFloat() / item.width, height.toFloat() / item.height)
    return ceil(item.width * scale).toInt() to ceil(item.height * scale).toInt()
}

private fun ByteArray.toNSData(): NSData =
    if (isEmpty()) NSData() else usePinned { NSData.create(bytes = it.addressOf(0), length = size.toULong()) }
