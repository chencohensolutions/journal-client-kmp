package com.chencohensolution.journal.similarity

import com.chencohensolution.journal.library.MediaItem
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** A grayscale picture: one brightness (0..255) per pixel, row by row. */
internal class GrayImage(val width: Int, val height: Int, val pixels: IntArray) {
    init {
        require(width > 0 && height > 0 && pixels.size == width * height) { "bad image size" }
    }
}

/** What the platform reads from a photo: a small grayscale copy, and its image model's embedding (null if it failed). */
internal class PhotoAnalysis(val gray: GrayImage, val embedding: FloatArray?)

/** Reads photos for [PhotoSimilarity]: Vision feature prints on iOS, a MediaPipe image embedder on Android. */
internal interface PhotoAnalyzer {
    /** Names the embedding model; embeddings of different models are never compared (the cache is per model). */
    val modelId: String

    /** Cosine similarity of two embeddings from which two photos count as the same scene. */
    val sameScene: Float

    /** Null when the photo can't be read, e.g. when it is only in iCloud. Called one photo at a time. */
    suspend fun analyze(item: MediaItem): PhotoAnalysis?
}

/**
 * What a photo looks like: its [dHash], and the platform model's embedding scaled to bytes. Equality is identity:
 * [PhotoSimilarity] keeps one per photo, so states built from the same fingerprints stay equal.
 */
class PhotoFingerprint internal constructor(val dHash: Long, internal val embedding: ByteArray?)

/** Fingerprints of photos, and when two of them look alike; what [com.chencohensolution.journal.stories.StoryView] groups with. */
class PhotoLooks internal constructor(
    internal val fingerprints: Map<String, PhotoFingerprint>,
    private val sameScene: Float,
) {
    /** Near-duplicates (dHash within [NEAR_DUPLICATE_BITS]), or the same scene by their embeddings. */
    internal fun similar(a: MediaItem, b: MediaItem): Boolean {
        val first = fingerprints[a.id] ?: return false
        val second = fingerprints[b.id] ?: return false
        if (hammingDistance(first.dHash, second.dHash) <= NEAR_DUPLICATE_BITS) return true
        val e1 = first.embedding ?: return false
        val e2 = second.embedding ?: return false
        return cosine(e1, e2) >= sameScene
    }

    /** Whether [item] has been read with an embedding; photos without one are grouped by time instead. */
    internal fun canCompare(item: MediaItem): Boolean = fingerprints[item.id]?.embedding != null

    /** The same fingerprints compared with another threshold ([PhotoAnalyzer.sameScene]). */
    internal fun withSameScene(threshold: Float): PhotoLooks =
        if (fingerprints.isEmpty()) this else PhotoLooks(fingerprints, threshold)

    /** The same looks for [ids] only. */
    internal fun only(ids: Collection<String>): PhotoLooks =
        if (fingerprints.isEmpty()) this else PhotoLooks(ids.mapNotNull { id -> fingerprints[id]?.let { id to it } }.toMap(), sameScene)

    override fun equals(other: Any?): Boolean =
        other is PhotoLooks && other.fingerprints == fingerprints && other.sameScene == sameScene

    override fun hashCode(): Int = fingerprints.hashCode() * 31 + sameScene.hashCode()

    companion object {
        val NONE = PhotoLooks(emptyMap(), Float.MAX_VALUE)
    }
}

/** dHashes this many bits apart or fewer are the same shot: a burst, a re-save, a small change in exposure. */
internal const val NEAR_DUPLICATE_BITS = 10

/**
 * The difference hash: [image] averaged down to 9×8 cells, then one bit per pair of neighbors in a row, set when
 * the left cell is brighter. Survives resizing, recompression and brightness changes; not crops or rotation.
 */
internal fun dHash(image: GrayImage): Long {
    val cells = shrink(image, 9, 8)
    var hash = 0L
    for (y in 0 until 8) {
        for (x in 0 until 8) {
            hash = hash shl 1
            if (cells[y * 9 + x] > cells[y * 9 + x + 1]) hash = hash or 1L
        }
    }
    return hash
}

internal fun hammingDistance(a: Long, b: Long): Int = (a xor b).countOneBits()

/** Averages [image] into [width]×[height] cells; each pixel counts in the cell it falls in. */
internal fun shrink(image: GrayImage, width: Int, height: Int): DoubleArray {
    val sums = DoubleArray(width * height)
    val counts = IntArray(width * height)
    for (y in 0 until image.height) {
        val row = y * height / image.height * width
        for (x in 0 until image.width) {
            val cell = row + x * width / image.width
            sums[cell] += image.pixels[y * image.width + x]
            counts[cell]++
        }
    }
    return DoubleArray(sums.size) { if (counts[it] == 0) 0.0 else sums[it] / counts[it] }
}

/** [embedding] scaled so its largest value is ±127: a quarter of the size, and cosine similarity barely changes. */
internal fun quantize(embedding: FloatArray): ByteArray? {
    val largest = embedding.maxOfOrNull { abs(it) } ?: return null
    if (!largest.isFinite() || largest == 0f) return null
    return ByteArray(embedding.size) { (embedding[it] / largest * 127).roundToInt().coerceIn(-127, 127).toByte() }
}

/** Cosine similarity, -1..1; 0 for embeddings of different sizes. */
internal fun cosine(a: ByteArray, b: ByteArray): Float {
    if (a.size != b.size) return 0f
    var dot = 0L
    var normA = 0L
    var normB = 0L
    for (i in a.indices) {
        dot += a[i] * b[i]
        normA += a[i] * a[i]
        normB += b[i] * b[i]
    }
    if (normA == 0L || normB == 0L) return 0f
    return (dot / sqrt(normA.toDouble() * normB.toDouble())).toFloat()
}
