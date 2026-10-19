package com.chencohensolution.journal.server

import io.ktor.client.request.get
import io.ktor.client.statement.readRawBytes
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import kotlin.coroutines.cancellation.CancellationException

/**
 * Previews of photos that are only on the server, made by the server at exactly the pixels they are drawn at
 * (journal-server-go docs/media.md), and kept on this device up to [maxBytes] (10 GB by default; the oldest go first).
 */
class ServerPreviews internal constructor(
    private val http: ServerHttp,
    private val fileSystem: FileSystem,
    private val directory: Path,
    private val maxBytes: Long = DEFAULT_MAX_BYTES,
) {
    private val downloads = Semaphore(PARALLEL_DOWNLOADS)
    private val cacheMutex = Mutex()
    /** Bytes in the cache; counted on first use. */
    private var cachedBytes: Long? = null

    /** Whether [NO_MEDIA] was checked this launch. */
    private var marked = false

    /**
     * The preview of [sha256] at [width] × [height] pixels ([contain]: fitted inside, else filling it). Offline, the
     * largest preview of it kept here (the platform scales it); null when there is none. Encoded (WebP).
     */
    suspend fun preview(sha256: String, width: Int, height: Int, contain: Boolean, fetch: Boolean = true): ByteArray? {
        if (width <= 0 || height <= 0) return null
        val w = width.coerceAtMost(MAX_SIDE)
        val h = height.coerceAtMost(MAX_SIDE)
        val fit = if (contain) "contain" else "cover"
        val path = directory / sha256.take(2) / sha256 / "${w}x$h-$fit.webp"
        if (!marked) {
            marked = true
            withContext(Dispatchers.IO) { if (fileSystem.exists(directory)) markNoMedia(fileSystem, directory) }
        }
        withContext(Dispatchers.IO) { if (fileSystem.exists(path)) return@withContext fileSystem.read(path) { readByteArray() } else null }
            ?.let { return it }
        val fetched = if (!fetch) null else try {
            downloads.withPermit {
                val client = http.client() ?: return@withPermit null
                val response = client.get(http.url("/media/$sha256/preview", "w=$w&h=$h&fit=$fit"))
                if (response.status != HttpStatusCode.OK) null else response.readRawBytes().also { store(path, it) }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
        return fetched ?: largestKept(sha256)?.let { kept -> withContext(Dispatchers.IO) { fileSystem.read(kept.file.toPath()) { readByteArray() } } }
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
        fileSystem.list(dir).filter { it.name.endsWith(".webp") }
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
            if (cachedBytes!! > maxBytes) trim()
        }
    }

    private fun countBytes(): Long =
        if (!fileSystem.exists(directory)) 0 else fileSystem.listRecursively(directory).sumOf { fileSystem.metadataOrNull(it)?.size ?: 0 }

    /** Deletes the oldest previews until the cache is at 90% of its limit. */
    private fun trim() {
        val files = fileSystem.listRecursively(directory)
            .filter { it.name != NO_MEDIA }
            .mapNotNull { path -> fileSystem.metadataOrNull(path)?.takeIf { it.isRegularFile }?.let { Triple(path, it.size ?: 0, it.lastModifiedAtMillis ?: 0) } }
            .sortedBy { it.third }
            .toList()
        var total = files.sumOf { it.second }
        for ((path, size) in files) {
            if (total <= maxBytes / 10 * 9) break
            fileSystem.delete(path)
            total -= size
        }
        cachedBytes = total
    }

    private companion object {
        const val DEFAULT_MAX_BYTES = 10L shl 30
        const val MAX_SIDE = 4096
        const val PARALLEL_DOWNLOADS = 6

        /** What the viewer and the tiles ask for (androidApp VIEWER_MAX_SIDE and THUMB_LARGE). */
        const val VIEWER_SIDE = 2560
        const val SMALL_SIDE = 640
    }
}

/** Tells gallery apps to skip [directory]: its previews and originals are the app's copies, not new photos. */
internal const val NO_MEDIA = ".nomedia"

internal fun markNoMedia(fileSystem: FileSystem, directory: Path) {
    val marker = directory / NO_MEDIA
    if (!fileSystem.exists(marker)) fileSystem.write(marker) {}
}
