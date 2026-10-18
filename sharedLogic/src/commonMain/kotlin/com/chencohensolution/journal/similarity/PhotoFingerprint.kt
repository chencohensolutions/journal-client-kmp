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

/**
 * What the platform reads from a photo: a small grayscale copy, and its image model's embeddings of the whole photo
 * and of its center zoomed in by each of [EMBEDDING_ZOOMS] after the first (empty if the model failed; the whole
 * photo first).
 */
internal class PhotoAnalysis(val gray: GrayImage, val embeddings: List<FloatArray>)

/**
 * Zooms into the center that the platform embeds besides the whole photo (1). A camera's zoom is a center crop, so
 * a zoomed shot looks like the other photo's center: comparing one photo's centers with the other's whole frame
 * catches zoom up to about 2.5×.
 */
internal val EMBEDDING_ZOOMS = listOf(1f, 1.4f, 2f)

/** The same for dHashes, which are cheap: closer steps, so a zoomed near-duplicate matches a crop almost exactly. */
internal val DHASH_ZOOMS = listOf(1f, 1.15f, 1.3f, 1.5f, 1.7f, 2f, 2.3f, 2.6f, 3f)

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
 * What a photo looks like: dHashes of the whole photo and of its center zoomed in ([DHASH_ZOOMS]), and the platform
 * model's embeddings the same way ([EMBEDDING_ZOOMS]) scaled to bytes; the whole photo first in both. Equality is
 * identity: [PhotoSimilarity] keeps one per photo, so states built from the same fingerprints stay equal.
 */
class PhotoFingerprint internal constructor(internal val dHashes: LongArray, internal val embeddings: List<ByteArray>) {
    /** The whole photo's dHash. */
    val dHash: Long get() = dHashes.first()
}

/** Fingerprints of photos, and when two of them look alike; what [com.chencohensolution.journal.stories.StoryView] groups with. */
class PhotoLooks internal constructor(
    internal val fingerprints: Map<String, PhotoFingerprint>,
    private val sameScene: Float,
) {
    /**
     * Near-duplicates (dHash within [NEAR_DUPLICATE_BITS]), or the same scene by their embeddings, either one possibly
     * zoomed in: one photo's whole frame is compared with the other's whole frame and zoomed centers, both ways.
     */
    internal fun similar(a: MediaItem, b: MediaItem): Boolean {
        val first = fingerprints[a.id] ?: return false
        val second = fingerprints[b.id] ?: return false
        if (zoomedPairs(first.dHashes, second.dHashes).any { (x, y) -> hammingDistance(x, y) <= NEAR_DUPLICATE_BITS }) return true
        return zoomedPairs(first.embeddings, second.embeddings).any { (x, y) -> cosine(x, y) >= sameScene }
    }

    /** Whether [item] has been read with an embedding; photos without one are grouped by time instead. */
    internal fun canCompare(item: MediaItem): Boolean = fingerprints[item.id]?.embeddings?.isNotEmpty() == true

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

/** Each whole frame (index 0) with every view of the other photo: both whole frames, and each against the other's centers. */
private fun <T> zoomedPairs(a: List<T>, b: List<T>): Sequence<Pair<T, T>> = sequence {
    val wholeA = a.firstOrNull() ?: return@sequence
    val wholeB = b.firstOrNull() ?: return@sequence
    b.forEach { yield(wholeA to it) }
    a.drop(1).forEach { yield(it to wholeB) }
}

private fun zoomedPairs(a: LongArray, b: LongArray) = zoomedPairs(a.asList(), b.asList())

/** The center of [image] zoomed in by [zoom] (1 is the whole image). */
internal fun GrayImage.center(zoom: Float): GrayImage {
    if (zoom <= 1f) return this
    val w = (width / zoom).toInt().coerceAtLeast(9)
    val h = (height / zoom).toInt().coerceAtLeast(8)
    val left = (width - w) / 2
    val top = (height - h) / 2
    return GrayImage(w, h, IntArray(w * h) { pixels[(top + it / w) * width + left + it % w] })
}

/** [dHash] of the whole image and of its center at each of [DHASH_ZOOMS]. */
internal fun dHashes(image: GrayImage): LongArray = LongArray(DHASH_ZOOMS.size) { dHash(image.center(DHASH_ZOOMS[it])) }

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
