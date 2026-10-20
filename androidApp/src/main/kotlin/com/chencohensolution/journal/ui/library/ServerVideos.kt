package com.chencohensolution.journal.ui.library

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem as PlayerItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheEvictor
import androidx.media3.datasource.cache.CacheSpan
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.okhttp.OkHttpDataSource
import com.chencohensolution.journal.keptOnPhoneLimitBytes
import com.chencohensolution.journal.library.MediaItem
import com.chencohensolution.journal.serverPreviews
import com.chencohensolution.journal.serverStream
import java.io.File
import java.util.TreeSet

/** What the player needs for one video: where its bytes come from, and the item to play. */
internal class PlayableVideo(val dataSource: DataSource.Factory, val item: PlayerItem)

/**
 * Videos only on the server, streamed from their original (`GET /media/{sha256}` with Range) into `cache/videos`: the
 * parts played or buffered are kept, keyed by sha256, so a video swiped away after a few seconds keeps them, and
 * playing it again reads them here and fetches only what is missing. The cache shares the "Kept on this phone" limit
 * with the previews and originals of server photos (least recently used first).
 */
@OptIn(UnstableApi::class)
internal object ServerVideos {
    private var cache: SimpleCache? = null

    /** The process's one video cache (Media3 allows one per folder). */
    @Synchronized
    fun cache(context: Context): SimpleCache {
        cache?.let { return it }
        val app = context.applicationContext
        val previews = serverPreviews(app)
        val evictor = SharedLimitEvictor { keptOnPhoneLimitBytes(app) - previews.knownBytes }
        return SimpleCache(File(app.cacheDir, "videos"), evictor, StandaloneDatabaseProvider(app)).also { videos ->
            cache = videos
            previews.otherBytes = { videos.cacheSpace }
        }
    }

    /** Bytes the cached videos take up. */
    fun usedBytes(context: Context): Long = cache(context).cacheSpace

    /** Deletes every cached video; they stream again when played. Blocks. */
    fun clear(context: Context) {
        val videos = cache(context)
        videos.keys.toList().forEach(videos::removeResource)
    }

    /**
     * How to play [item]: from the phone's library or a kept original as it is, else streamed from the server through
     * the cache. Null when it can't be had (not registered). Blocks the first time: call off the main thread.
     */
    fun playable(context: Context, item: MediaItem): PlayableVideo? {
        item.playableUri()?.let { uri ->
            return PlayableVideo(DefaultDataSource.Factory(context), PlayerItem.fromUri(uri))
        }
        val sha = item.sha256 ?: return null
        val stream = serverStream(context, sha) ?: return null
        val source = CacheDataSource.Factory()
            .setCache(cache(context))
            .setUpstreamDataSourceFactory(OkHttpDataSource.Factory(stream.client))
            // Unreadable cached parts are fetched again instead of failing the video.
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        return PlayableVideo(source, PlayerItem.Builder().setUri(stream.url).setCustomCacheKey(sha).build())
    }
}

/**
 * Least recently used first, like Media3's own evictor, but within a limit that changes: what Settings allows less
 * what the previews of server photos take.
 */
@OptIn(UnstableApi::class)
private class SharedLimitEvictor(private val maxBytes: () -> Long) : CacheEvictor {
    private val spans = TreeSet<CacheSpan> { a, b ->
        if (a.lastTouchTimestamp != b.lastTouchTimestamp) a.lastTouchTimestamp.compareTo(b.lastTouchTimestamp) else a.compareTo(b)
    }
    private var size = 0L

    override fun requiresCacheSpanTouches() = true

    override fun onCacheInitialized() {}

    override fun onStartFile(cache: Cache, key: String, position: Long, length: Long) {
        if (length != C.LENGTH_UNSET.toLong()) evict(cache, length)
    }

    override fun onSpanAdded(cache: Cache, span: CacheSpan) {
        spans.add(span)
        size += span.length
        evict(cache, 0)
    }

    override fun onSpanRemoved(cache: Cache, span: CacheSpan) {
        spans.remove(span)
        size -= span.length
    }

    override fun onSpanTouched(cache: Cache, oldSpan: CacheSpan, newSpan: CacheSpan) {
        onSpanRemoved(cache, oldSpan)
        onSpanAdded(cache, newSpan)
    }

    private fun evict(cache: Cache, required: Long) {
        val limit = maxBytes().coerceAtLeast(MIN_VIDEO_BYTES)
        while (size + required > limit && spans.isNotEmpty()) cache.removeSpan(spans.first())
    }

    private companion object {
        /** Enough for a video being played, however much the previews take. */
        const val MIN_VIDEO_BYTES = 512L shl 20
    }
}
