package com.chencohensolution.journal.server

import io.ktor.client.request.get
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.readRawBytes
import io.ktor.http.HttpStatusCode
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okio.FileSystem
import okio.HashingSink
import okio.Path
import okio.Path.Companion.toPath
import okio.buffer
import okio.use
import kotlin.coroutines.cancellation.CancellationException

/**
 * Photos that are only on the server, as this device shows them: previews the server makes at exactly the pixels they
 * are drawn at (journal-server-go docs/media.md), in the format and quality of [options], each size kept as its own
 * file; or, with previews off, their originals. Both are kept on this device up to [maxBytes] (Settings → Storage on
 * this phone, 10 GB by default; the oldest go first).
 */
class ServerPreviews internal constructor(
    private val http: ServerHttp,
    private val fileSystem: FileSystem,
    private val directory: Path,
    private val maxBytes: () -> Long = { DEFAULT_MAX_BYTES },
    private val options: () -> PreviewOptions = { PreviewOptions() },
) {
    private val downloads = Semaphore(PARALLEL_DOWNLOADS)
    private val originalDownloads = Semaphore(PARALLEL_ORIGINALS)
    private val originalLocks = mutableMapOf<String, Mutex>()
    private val locksMutex = Mutex()
    private val cacheMutex = Mutex()
    /** Bytes in the cache; counted on first use. */
    private var cachedBytes: Long? = null

    /**
     * Bytes kept elsewhere under the same limit (the video player's cache), so previews, originals and videos together
     * stay within [maxBytes].
     */
    var otherBytes: () -> Long = { 0 }

    /** Bytes of previews and originals kept here as last counted (0 before the first count); cheap to read. */
    val knownBytes: Long get() = cachedBytes ?: 0

    /** What previews and originals may take: the limit, less what the other cache under it takes. */
    private fun limit(): Long = (maxBytes() - otherBytes()).coerceAtLeast(MIN_SHARE)

    /** Whether [NO_MEDIA] was checked this launch. */
    private var marked = false

    /** Whether photos are shown from previews (else from their originals, downloaded). */
    val usesPreviews: Boolean get() = options().enabled

    /** How a platform turns what is kept into a picture: an original file, or encoded preview bytes. Null: it can't. */
    class Decoders<T : Any>(
        val original: suspend (path: String) -> T?,
        val preview: suspend (bytes: ByteArray) -> T?,
    )

    /**
     * A server-only photo or video drawn at [width] × [height] pixels (a tile). A video: its poster at that size (videos
     * aren't downloaded to be shown). A photo: its original when one is in this cache; else, with previews on, the
     * preview at exactly that size (kept, or made by the server now); with previews off, the exact preview when one is
     * kept, else the original downloaded now, and a preview after all when this device can't decode it (Samsung's DNG
     * 1.7). Offline, the largest preview kept stands in. Null when nothing of it can be had.
     */
    suspend fun <T : Any> tile(sha256: String, isVideo: Boolean, fileName: String?, width: Int, height: Int, decode: Decoders<T>): T? {
        if (isVideo) return preview(sha256, width, height, contain = true)?.let { decode.preview(it) }
        cachedOriginal(sha256)?.let { decode.original(it) }?.let { return it }
        if (!usesPreviews) {
            preview(sha256, width, height, contain = true, fetch = false, orLargest = false)?.let { decode.preview(it) }?.let { return it }
            original(sha256, fileName ?: sha256)?.let { decode.original(it) }?.let { return it }
        }
        return preview(sha256, width, height, contain = true)?.let { decode.preview(it) }
    }

    /**
     * A server-only photo opened on its own, fitted in [maxSide]: its original, downloaded into this cache now if it
     * isn't here (and kept). A preview at that size only when this device can't decode the original. Null offline; the
     * caller keeps showing what it had ([largestPreview]).
     */
    suspend fun <T : Any> viewer(sha256: String, fileName: String?, maxSide: Int, decode: Decoders<T>): T? {
        original(sha256, fileName ?: sha256)?.let { decode.original(it) }?.let { return it }
        return preview(sha256, maxSide, maxSide, contain = true, orLargest = false)?.let { decode.preview(it) }
    }

    /** The largest preview of [sha256] kept here, never fetched: what shows while the original downloads. */
    suspend fun largestPreview(sha256: String): ByteArray? =
        largestKept(sha256)?.let { kept -> withContext(Dispatchers.IO) { fileSystem.read(kept.file.toPath()) { readByteArray() } } }

    /** Where the original of [sha256] is streamed from (`GET /media/{sha256}`, with Range). */
    fun mediaUrl(sha256: String): String = http.url("/media/$sha256")

    /**
     * What is kept here of [sha256]: each preview (its pixels, format and quality, read from its name, largest first)
     * and the original when it was downloaded. [photoWidth] × [photoHeight] is the photo's own size, which a preview
     * fitted inside its box takes the shape of.
     */
    suspend fun keptHere(sha256: String, photoWidth: Int, photoHeight: Int): KeptHere {
        val previews = kept(sha256).mapNotNull { keptPreview(it, photoWidth, photoHeight) }
        val original = cachedOriginal(sha256)?.let { withContext(Dispatchers.IO) { fileSystem.metadataOrNull(it.toPath())?.size } }
        return KeptHere(previews, original)
    }

    /**
     * The preview of [sha256] at [width] × [height] pixels ([contain]: fitted inside, else filling it), in the format
     * and quality of [options]: the one kept here, else made by the server now (unless not [fetch]). Offline (with
     * [orLargest]), the largest preview of it kept here (the platform scales it); null when there is none. Encoded.
     */
    suspend fun preview(sha256: String, width: Int, height: Int, contain: Boolean, fetch: Boolean = true, orLargest: Boolean = true): ByteArray? {
        if (width <= 0 || height <= 0) return null
        val w = width.coerceAtMost(MAX_SIDE)
        val h = height.coerceAtMost(MAX_SIDE)
        val fit = if (contain) "contain" else "cover"
        val (_, format, quality) = options()
        val path = directory / sha256.take(2) / sha256 / "${w}x$h-$fit-${quality.query}.${format.extension}"
        markOnce()
        withContext(Dispatchers.IO) { if (fileSystem.exists(path)) return@withContext fileSystem.read(path) { readByteArray() } else null }
            ?.let { return it }
        val fetched = if (!fetch) null else try {
            downloads.withPermit {
                val client = http.client() ?: return@withPermit null
                val query = "w=$w&h=$h&fit=$fit&fmt=${format.query}&q=${quality.query}"
                val response = client.get(http.url("/media/$sha256/preview", query))
                if (response.status != HttpStatusCode.OK) null else response.readRawBytes().also { store(path, it) }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
        if (fetched != null || !orLargest) return fetched
        return largestKept(sha256)?.let { kept -> withContext(Dispatchers.IO) { fileSystem.read(kept.file.toPath()) { readByteArray() } } }
    }

    /** The original of [sha256] when it is kept in this cache (downloaded while previews were off); never fetched. */
    suspend fun cachedOriginal(sha256: String): String? = withContext(Dispatchers.IO) {
        val dir = directory / sha256.take(2) / sha256
        if (!fileSystem.exists(dir)) return@withContext null
        fileSystem.list(dir).firstOrNull { it.name.startsWith(ORIGINAL) && !it.name.endsWith(".tmp") }?.toString()
    }

    /**
     * The original of [sha256], downloaded into this cache now when it is not here yet (checked against its sha256),
     * to show it with previews off. Null offline, or when the server does not have it.
     */
    suspend fun original(sha256: String, fileName: String): String? {
        cachedOriginal(sha256)?.let { return it }
        val lock = locksMutex.withLock { originalLocks.getOrPut(sha256) { Mutex() } }
        val found = lock.withLock {
            cachedOriginal(sha256) ?: try {
                originalDownloads.withPermit { download(sha256, fileName) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
        }
        locksMutex.withLock { if (!lock.isLocked) originalLocks.remove(sha256) }
        return found
    }

    private suspend fun download(sha256: String, fileName: String): String? = withContext(Dispatchers.IO) {
        val client = http.client() ?: return@withContext null
        markOnce()
        val dir = directory / sha256.take(2) / sha256
        fileSystem.createDirectories(dir)
        val extension = fileName.substringAfterLast('.', "").lowercase().filter { it.isLetterOrDigit() }.take(8)
        val target = dir / (if (extension.isEmpty()) ORIGINAL else "$ORIGINAL.$extension")
        val temp = dir / "$ORIGINAL.tmp"
        val ok = client.prepareGet(http.url("/media/$sha256")).execute { response ->
            if (response.status != HttpStatusCode.OK) return@execute false
            val hashing = HashingSink.sha256(fileSystem.sink(temp))
            hashing.buffer().use { sink ->
                val channel = response.bodyAsChannel()
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val n = channel.readAvailable(buffer)
                    if (n == -1) break
                    sink.write(buffer, 0, n)
                }
            }
            hashing.hash.hex() == sha256
        }
        if (!ok) {
            fileSystem.delete(temp, mustExist = false)
            return@withContext null
        }
        fileSystem.atomicMove(temp, target)
        cacheMutex.withLock {
            val total = cachedBytes ?: countBytes()
            cachedBytes = total + (fileSystem.metadataOrNull(target)?.size ?: 0)
            if (cachedBytes!! > limit()) trim()
        }
        target.takeIf { fileSystem.exists(it) }?.toString()
    }

    private suspend fun markOnce() {
        if (marked) return
        marked = true
        withContext(Dispatchers.IO) { if (fileSystem.exists(directory)) markNoMedia(fileSystem, directory) }
    }

    /**
     * Keeps [webp], a picture made on this device, as [sha256]'s preview: a video's frame, while the server makes no
     * posters for videos.
     */
    suspend fun keepPoster(sha256: String, webp: ByteArray, width: Int, height: Int) {
        store(directory / sha256.take(2) / sha256 / "${width}x$height-poster.webp", webp)
    }

    /** A preview kept on this device: its file and size. */
    data class Kept(val file: String, val bytes: Long)

    /** The previews of [sha256] kept here, largest first. */
    suspend fun kept(sha256: String): List<Kept> = withContext(Dispatchers.IO) {
        val dir = directory / sha256.take(2) / sha256
        if (!fileSystem.exists(dir)) return@withContext emptyList()
        fileSystem.list(dir).filter { !it.name.startsWith(ORIGINAL) && !it.name.endsWith(".tmp") }
            .map { Kept(it.toString(), fileSystem.metadataOrNull(it)?.size ?: 0) }
            .sortedByDescending { it.bytes }
    }

    private suspend fun largestKept(sha256: String): Kept? = kept(sha256).firstOrNull()

    /**
     * Makes sure [sha256] can still be shown when its original leaves the device: the viewer's sizes, fetched now.
     * True when they are kept here.
     */
    suspend fun keepForViewing(sha256: String): Boolean {
        val viewer = preview(sha256, VIEWER_SIDE, VIEWER_SIDE, contain = true)
        val small = preview(sha256, SMALL_SIDE, SMALL_SIDE, contain = true)
        return viewer != null && small != null && kept(sha256).size >= 2
    }

    private suspend fun store(path: Path, bytes: ByteArray) = withContext(Dispatchers.IO) {
        cacheMutex.withLock {
            val total = cachedBytes ?: countBytes()
            fileSystem.createDirectories(path.parent!!)
            markNoMedia(fileSystem, directory)
            val temp = path.parent!! / (path.name + ".tmp")
            fileSystem.write(temp) { write(bytes) }
            fileSystem.atomicMove(temp, path)
            cachedBytes = total + bytes.size
            if (cachedBytes!! > limit()) trim()
        }
    }

    /** Bytes the previews take up on this device. */
    suspend fun usedBytes(): Long = withContext(Dispatchers.IO) {
        cacheMutex.withLock { (cachedBytes ?: countBytes()).also { cachedBytes = it } }
    }

    /** Applies a lowered limit now, instead of at the next preview kept. */
    suspend fun trimToLimit() = withContext(Dispatchers.IO) {
        cacheMutex.withLock {
            val total = cachedBytes ?: countBytes()
            cachedBytes = total
            if (total > limit()) trim()
        }
    }

    /** Deletes every preview; they are fetched again as photos are shown. */
    suspend fun clear() = withContext(Dispatchers.IO) {
        cacheMutex.withLock {
            if (fileSystem.exists(directory)) fileSystem.deleteRecursively(directory)
            cachedBytes = 0
            marked = false
        }
    }

    private fun countBytes(): Long =
        if (!fileSystem.exists(directory)) 0 else fileSystem.listRecursively(directory).sumOf { fileSystem.metadataOrNull(it)?.size ?: 0 }

    /** Deletes the oldest previews until the cache is at 90% of its limit. */
    private fun trim() {
        val limit = limit()
        val files = fileSystem.listRecursively(directory)
            .filter { it.name != NO_MEDIA }
            .mapNotNull { path -> fileSystem.metadataOrNull(path)?.takeIf { it.isRegularFile }?.let { Triple(path, it.size ?: 0, it.lastModifiedAtMillis ?: 0) } }
            .sortedBy { it.third }
            .toList()
        var total = files.sumOf { it.second }
        for ((path, size) in files) {
            if (total <= limit / 10 * 9) break
            fileSystem.delete(path)
            total -= size
        }
        cachedBytes = total
    }

    companion object {
        const val DEFAULT_MAX_BYTES = 10L shl 30
        private const val MAX_SIDE = 4096
        private const val PARALLEL_DOWNLOADS = 6
        private const val PARALLEL_ORIGINALS = 2

        /** What previews may always keep, however much the videos take. */
        private const val MIN_SHARE = 512L shl 20

        /** The name (before its extension) of an original kept in this cache. */
        private const val ORIGINAL = "original"

        /** What the viewer and the tiles ask for (androidApp VIEWER_MAX_SIDE and THUMB_LARGE). */
        private const val VIEWER_SIDE = 2560
        private const val SMALL_SIDE = 640
    }
}

/** Tells gallery apps to skip [directory]: its previews and originals are the app's copies, not new photos. */
internal const val NO_MEDIA = ".nomedia"

internal fun markNoMedia(fileSystem: FileSystem, directory: Path) {
    val marker = directory / NO_MEDIA
    if (!fileSystem.exists(marker)) fileSystem.write(marker) {}
}
