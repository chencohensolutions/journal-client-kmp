package com.chencohensolution.journal.storage

import com.chencohensolution.journal.server.KeptHere
import com.chencohensolution.journal.server.ServerPreviews
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext

/** A platform's cache of videos streamed from the server (Media3 on Android), keyed by sha256. */
interface VideoCache {
    /** Bytes the cached videos take up. */
    fun usedBytes(): Long

    /** Bytes of [sha256] cached so far (the parts played or buffered). */
    fun cachedBytes(sha256: String): Long

    /** Deletes every cached video; they stream again when played. */
    fun clear()
}

/**
 * Settings → Storage on this phone: the previews and downloaded originals of server photos ([ServerPreviews]) and the
 * videos streamed from the server ([VideoCache], attached by the platform) under one limit ([limitBytes], 10 GB by
 * default). Previews and originals give way to videos and the other way round, least recently used first, but each
 * keeps at least [MIN_SHARE_BYTES].
 */
class PhoneStorage internal constructor(
    private val previews: ServerPreviews,
    private val limitBytes: () -> Long,
) {
    private var videos: VideoCache? = null

    /** The platform's video cache, counted toward the limit from now on. */
    fun attachVideos(cache: VideoCache) {
        videos = cache
        previews.otherBytes = { cache.usedBytes() }
    }

    /** What may be kept in all. */
    val limit: Long get() = limitBytes()

    /** What cached videos may take now: the limit less what previews and originals take. */
    fun videoBudget(): Long = (limitBytes() - previews.knownBytes).coerceAtLeast(MIN_SHARE_BYTES)

    /** Bytes kept in all: previews, originals and videos. */
    suspend fun usedBytes(): Long = previews.usedBytes() + withContext(Dispatchers.IO) { videos?.usedBytes() ?: 0 }

    /** Applies a lowered limit now. */
    suspend fun trimToLimit() = previews.trimToLimit()

    /** Deletes previews, originals and videos ("Clear"); the server keeps everything. */
    suspend fun clear() {
        previews.clear()
        withContext(Dispatchers.IO) { videos?.clear() }
    }

    /** What is kept of one photo or video ([width] × [height] is its own size), with a video's cached bytes. */
    suspend fun keptHere(sha256: String, isVideo: Boolean, width: Int, height: Int): KeptHere {
        val kept = previews.keptHere(sha256, width, height)
        if (!isVideo) return kept
        val video = withContext(Dispatchers.IO) { runCatching { videos?.cachedBytes(sha256) }.getOrNull() }
        return kept.copy(videoBytes = video?.takeIf { it > 0 })
    }

    companion object {
        /** What previews, and videos, may always keep however much the other takes. */
        const val MIN_SHARE_BYTES = 512L shl 20
    }
}
