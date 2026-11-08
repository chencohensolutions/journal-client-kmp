package com.chencohensolution.journal.similarity

import com.chencohensolution.journal.library.BatchWalk
import com.chencohensolution.journal.library.LibraryKey
import com.chencohensolution.journal.library.MediaItem
import com.chencohensolution.journal.library.key
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
import kotlin.time.Duration.Companion.minutes
import kotlin.time.TimeMark
import kotlin.time.TimeSource
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
    /** Rows read from the database at a time ([requestFrom]); tests read fewer. */
    private val batchSize: Int = BATCH,
) {
    // One coroutine at a time touches the fields below; analyzing suspends, so new requests still get in.
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val scope = CoroutineScope(SupervisorJob() + dispatcher.limitedParallelism(1))
    private var known: MutableMap<String, PhotoFingerprint>? = null
    private val failed = mutableSetOf<String>()
    private var wanted: List<MediaItem> = emptyList()
    private var worker: Job? = null

    /** With [requestFrom]: the phone's photos read from the database a batch at a time, and which of them are wanted. */
    private var batches: (suspend (after: LibraryKey?, limit: Int) -> List<MediaItem>)? = null
    private var wants: (MediaItem) -> Boolean = { true }

    /** Set by [requestFrom] while the worker reads: it starts again from the newest. */
    private var again = false

    /** While the analyzer's model is being downloaded ([PhotoAnalyzer.prepare]), and when the last try failed. */
    private var preparing = false
    private var preparedFailed: TimeMark? = null

    private val _looks = MutableStateFlow(PhotoLooks.NONE)

    /** Every fingerprint worked out so far; grows in batches while photos are read. */
    val looks: StateFlow<PhotoLooks> = _looks.asStateFlow()

    private val _loaded = MutableStateFlow(false)

    /** True once the fingerprints kept on this phone are in [looks]: they are read at once, before any request. */
    val loaded: StateFlow<Boolean> = _loaded.asStateFlow()

    init {
        scope.launch { known() }
        scope.launch { analyzer.modelArrived.collect { modelHere() } }
    }

    /** The photos to fingerprint, most wanted first; replaces the previous request. Known ones are skipped. */
    fun request(items: List<MediaItem>) {
        scope.launch {
            wanted = items
            batches = null
            if (worker?.isActive != true) worker = scope.launch { work() }
        }
    }

    /**
     * The photos to fingerprint read from the library database instead of a list (docs/plans/library-on-disk.md, step 6b):
     * [batches] gives the phone's photos a batch at a time, newest first, after a row; those [wants] takes are read in
     * that order, as [request] reads a list. Called again (the library or what it shows changed), the next read starts
     * from the newest.
     */
    internal fun requestFrom(batches: suspend (after: LibraryKey?, limit: Int) -> List<MediaItem>, wants: (MediaItem) -> Boolean) {
        scope.launch {
            this@PhotoSimilarity.batches = batches
            this@PhotoSimilarity.wants = wants
            again = true
            if (worker?.isActive != true) worker = scope.launch { work() }
        }
    }

    fun close() {
        scope.cancel()
    }

    /** The fingerprints kept on this phone, read once and published. */
    private fun known(): MutableMap<String, PhotoFingerprint> = known ?: cache.load(analyzer.modelId).toMutableMap().also {
        known = it
        publish(it)
        _loaded.value = true
    }

    private suspend fun work() {
        val known = known()
        var added = 0
        // A photo only on the server is never read here (its fingerprint, if any, was made while it was on the device).
        // One read before the model was here is read again once it is, for its embeddings.
        fun needed(item: MediaItem) = item.isOnDevice && item.id !in failed &&
            (known[item.id]?.let { it.withoutModel && analyzer.embeds } ?: true)
        if (!analyzer.embeds) prepare()
        do {
            again = false
            val walk = batches?.let { BatchWalk(MediaItem::key, batchSize, it) }
            while (true) {
                val next = (if (walk != null) walk.next { needed(it) && wants(it) } else wanted.firstOrNull(::needed)) ?: break
                // Asked again while this batch was read: from the newest.
                if (again) break
                if (!fingerprint(next, known)) continue
                if (++added % PUBLISH_EVERY == 0) publish(known)
            }
        } while (again)
        if (added > 0) {
            publish(known)
            cache.save(analyzer.modelId, known)
        }
    }

    /** Reads [next]'s fingerprint into [known]; false when it can't be read (it isn't tried again while the app runs). */
    private suspend fun fingerprint(next: MediaItem, known: MutableMap<String, PhotoFingerprint>): Boolean {
        val analysis = try {
            analyzer.analyze(next)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        if (analysis == null) {
            failed += next.id
            return false
        }
        known[next.id] = PhotoFingerprint(dHashes(analysis.gray), analysis.embeddings.mapNotNull(::quantize), analysis.withoutModel)
        return true
    }

    /**
     * Gets the analyzer's model in the background while photos are read by dHash alone; once it is here, those photos
     * are read again for their embeddings. A failed download is tried again on a later request, after [RETRY].
     */
    private fun prepare() {
        if (preparing || preparedFailed?.let { it.elapsedNow() < RETRY } == true) return
        preparing = true
        scope.launch {
            val ready = try {
                analyzer.prepare()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                false
            }
            preparing = false
            if (!ready) {
                preparedFailed = TimeSource.Monotonic.markNow()
                return@launch
            }
            modelHere()
        }
    }

    /** The model is here now: the photos read without it are read again, the newest first. */
    private fun modelHere() {
        preparedFailed = null
        again = true
        if (worker?.isActive != true) worker = scope.launch { work() }
    }

    private fun publish(known: Map<String, PhotoFingerprint>) {
        _looks.value = PhotoLooks(known.toMap(), analyzer.sameScene)
    }

    private companion object {
        /** Screens update after this many new photos, not after each one. */
        const val PUBLISH_EVERY = 12

        /** Rows read from the database at a time ([requestFrom]): a few hundred photos. */
        const val BATCH = 500

        /** How long after a failed model download ([prepare]) a request tries again. */
        val RETRY = 10.minutes
    }
}

/**
 * `<dir>/fingerprints.json`: device photo id → dHashes (16 hex digits each) and embeddings (base64 bytes each), the
 * whole photo first, for one model; `n` when it was read before the model was on the phone.
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
                PhotoFingerprint(photo.d.map { it.toULong(16).toLong() }.toLongArray(), photo.e.map { Base64.decode(it) }, photo.n)
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
                    it.withoutModel,
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
    private class CachedPhoto(val d: List<String>, val e: List<String> = emptyList(), val n: Boolean = false)

    private companion object {
        // 2: dHashes and embeddings of zoomed centers too.
        const val VERSION = 2
        val json = Json { ignoreUnknownKeys = true }
    }
}
