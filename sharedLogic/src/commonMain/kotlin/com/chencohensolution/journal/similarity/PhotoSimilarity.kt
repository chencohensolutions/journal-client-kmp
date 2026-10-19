package com.chencohensolution.journal.similarity

import com.chencohensolution.journal.library.MediaItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okio.FileSystem
import okio.Path
import kotlin.io.encoding.Base64

/**
 * Fingerprints ([PhotoFingerprint]) of the photos asked for, worked out one at a time in the background and kept in a
 * cache file, so each photo is read once per device. The cache is only a cache: it can be deleted at any time, and
 * it is never synced (another platform's model gives numbers that mean nothing here).
 */
class PhotoSimilarity internal constructor(
    private val analyzer: PhotoAnalyzer,
    private val cache: FingerprintCache,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    // One coroutine at a time touches the fields below; analyzing suspends, so new requests still get in.
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val scope = CoroutineScope(SupervisorJob() + dispatcher.limitedParallelism(1))
    private var known: MutableMap<String, PhotoFingerprint>? = null
    private val failed = mutableSetOf<String>()
    private var wanted: List<MediaItem> = emptyList()
    private var worker: Job? = null

    private val _looks = MutableStateFlow(PhotoLooks.NONE)

    /** Every fingerprint worked out so far; grows in batches while photos are read. */
    val looks: StateFlow<PhotoLooks> = _looks.asStateFlow()

    /** The photos to fingerprint, most wanted first; replaces the previous request. Known ones are skipped. */
    fun request(items: List<MediaItem>) {
        scope.launch {
            wanted = items
            if (worker?.isActive != true) worker = scope.launch { work() }
        }
    }

    fun close() {
        scope.cancel()
    }

    private suspend fun work() {
        val known = known ?: cache.load(analyzer.modelId).toMutableMap().also {
            known = it
            publish(it)
        }
        var added = 0
        while (true) {
            // A photo only on the server is never read here (its fingerprint, if any, was made while it was on the device).
            val next = wanted.firstOrNull { it.isOnDevice && it.id !in known && it.id !in failed } ?: break
            val analysis = try {
                analyzer.analyze(next)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            if (analysis == null) {
                failed += next.id
                continue
            }
            known[next.id] = PhotoFingerprint(dHashes(analysis.gray), analysis.embeddings.mapNotNull(::quantize))
            if (++added % PUBLISH_EVERY == 0) publish(known)
        }
        if (added > 0) {
            publish(known)
            cache.save(analyzer.modelId, known)
        }
    }

    private fun publish(known: Map<String, PhotoFingerprint>) {
        _looks.value = PhotoLooks(known.toMap(), analyzer.sameScene)
    }

    private companion object {
        /** Screens update after this many new photos, not after each one. */
        const val PUBLISH_EVERY = 12
    }
}

/**
 * `<dir>/fingerprints.json`: device photo id → dHashes (16 hex digits each) and embeddings (base64 bytes each), the
 * whole photo first, for one model.
 * A cache in the platform's caches folder, rebuilt when it's missing, damaged, or from another model.
 */
internal class FingerprintCache(private val fileSystem: FileSystem, private val dir: Path) {
    private val file = dir / "fingerprints.json"

    fun load(modelId: String): Map<String, PhotoFingerprint> = try {
        val saved = fileSystem.read(file) { json.decodeFromString<CacheFile>(readUtf8()) }
        if (saved.v != VERSION || saved.model != modelId) {
            emptyMap()
        } else {
            saved.photos.mapValues { (_, photo) ->
                PhotoFingerprint(photo.d.map { it.toULong(16).toLong() }.toLongArray(), photo.e.map { Base64.decode(it) })
            }
        }
    } catch (e: Exception) {
        emptyMap()
    }

    fun save(modelId: String, fingerprints: Map<String, PhotoFingerprint>) {
        try {
            val photos = fingerprints.mapValues { (_, it) ->
                CachedPhoto(
                    it.dHashes.map { hash -> hash.toULong().toString(16).padStart(16, '0') },
                    it.embeddings.map { bytes -> Base64.encode(bytes) },
                )
            }
            fileSystem.createDirectories(dir)
            val temp = dir / "fingerprints.json.tmp"
            fileSystem.write(temp) { writeUtf8(json.encodeToString(CacheFile(VERSION, modelId, photos))) }
            fileSystem.atomicMove(temp, file)
        } catch (e: Exception) {
            // Only a cache: the photos are read again next time.
        }
    }

    @Serializable
    private class CacheFile(val v: Int, val model: String, val photos: Map<String, CachedPhoto>)

    @Serializable
    private class CachedPhoto(val d: List<String>, val e: List<String> = emptyList())

    private companion object {
        // 2: dHashes and embeddings of zoomed centers too.
        const val VERSION = 2
        val json = Json { ignoreUnknownKeys = true }
    }
}
