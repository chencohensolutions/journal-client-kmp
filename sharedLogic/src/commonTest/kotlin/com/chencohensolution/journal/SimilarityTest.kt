package com.chencohensolution.journal

import com.chencohensolution.journal.library.LibraryController
import com.chencohensolution.journal.library.LocalDay
import com.chencohensolution.journal.library.MediaItem
import com.chencohensolution.journal.library.MediaLibrary
import com.chencohensolution.journal.library.MediaType
import com.chencohensolution.journal.library.PhotoAccess
import com.chencohensolution.journal.settings.JournalSettings
import com.chencohensolution.journal.settings.KeyValueStore
import com.chencohensolution.journal.settings.SettingsController
import com.chencohensolution.journal.similarity.FingerprintCache
import com.chencohensolution.journal.similarity.GrayImage
import com.chencohensolution.journal.similarity.PhotoAnalysis
import com.chencohensolution.journal.similarity.PhotoAnalyzer
import com.chencohensolution.journal.similarity.PhotoFingerprint
import com.chencohensolution.journal.similarity.PhotoLooks
import com.chencohensolution.journal.similarity.PhotoSimilarity
import com.chencohensolution.journal.similarity.cosine
import com.chencohensolution.journal.similarity.dHashes
import com.chencohensolution.journal.similarity.center
import com.chencohensolution.journal.similarity.DHASH_ZOOMS
import com.chencohensolution.journal.similarity.dHash
import com.chencohensolution.journal.similarity.hammingDistance
import com.chencohensolution.journal.similarity.quantize
import com.chencohensolution.journal.similarity.shrink
import com.chencohensolution.journal.stories.FeedEntry
import com.chencohensolution.journal.stories.FilePhotoNoteStore
import com.chencohensolution.journal.stories.StoriesController
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val DAY = LocalDay(2026, 10, 8)
private val DAY_START = DAY.toEpochDay() * 86_400_000

private fun photo(id: String, second: Int, type: MediaType = MediaType.IMAGE) =
    MediaItem(id, type, DAY_START + second * 1000L, DAY, null, 10, 10, 100, "$id.jpg")

/** A 90×80 picture whose brightness is [pattern] of x and y. */
private fun picture(pattern: (x: Int, y: Int) -> Int) =
    GrayImage(90, 80, IntArray(90 * 80) { pattern(it % 90, it / 90).coerceIn(0, 255) })

/** A fingerprint of the whole photo only. */
private fun fingerprint(dHash: Long, embedding: ByteArray?) = PhotoFingerprint(longArrayOf(dHash), listOfNotNull(embedding))

private val diagonal = picture { x, y -> (x * 2 + y) % 256 }
// Darkening to the right in bands: never like [diagonal], whole or zoomed.
private val waves = picture { x, y -> 230 - x * 2 + if ((y / 8) % 2 == 1) 40 else 0 }

class DHashTest {
    @Test
    fun theSamePictureBrighterHasTheSameHash() {
        val brighter = GrayImage(diagonal.width, diagonal.height, IntArray(diagonal.pixels.size) { diagonal.pixels[it] / 2 + 60 })
        assertEquals(dHash(diagonal), dHash(brighter))
    }

    @Test
    fun theSamePictureSmallerHasAlmostTheSameHash() {
        val smaller = GrayImage(45, 40, IntArray(45 * 40) { diagonal.pixels[(it / 45) * 2 * 90 + (it % 45) * 2] })
        assertTrue(hammingDistance(dHash(diagonal), dHash(smaller)) <= 4)
    }

    @Test
    fun differentPicturesAreFarApart() {
        val mirrored = picture { x, y -> diagonal.pixels[y * 90 + (89 - x)] }
        assertTrue(hammingDistance(dHash(diagonal), dHash(mirrored)) > 20)
        assertTrue(hammingDistance(dHash(diagonal), dHash(waves)) > 10)
    }

    @Test
    fun shrinkAveragesEachCell() {
        val image = GrayImage(4, 2, intArrayOf(0, 10, 100, 110, 20, 30, 120, 130))
        assertEquals(listOf(15.0, 115.0), shrink(image, 2, 1).toList())
    }
}

class ZoomTest {
    /** [image]'s center zoomed in by [zoom] and scaled back up, as a camera's digital zoom would show it. */
    private fun zoomed(image: GrayImage, zoom: Float): GrayImage {
        val center = image.center(zoom)
        return GrayImage(image.width, image.height, IntArray(image.pixels.size) {
            center.pixels[(it / image.width) * center.height / image.height * center.width + (it % image.width) * center.width / image.width]
        })
    }

    @Test
    fun aZoomedShotMatchesTheOthersCenter() {
        val photo = picture { x, y -> ((x - 45) * (x - 45) + (y - 40) * (y - 40) * 2) % 256 }
        val shot = zoomed(photo, 2f)
        assertTrue(hammingDistance(dHash(photo), dHash(shot)) > 10)
        assertTrue(hammingDistance(dHashes(photo)[DHASH_ZOOMS.indexOf(2f)], dHash(shot)) <= 4)
    }

    @Test
    fun eitherPhotoCanBeTheZoomedOne() {
        val wide = quantize(FloatArray(64) { it.toFloat() })!!
        val center = quantize(FloatArray(64) { if (it % 2 == 0) 1f else -1f })!!
        val unrelated = quantize(FloatArray(64) { if (it < 32) -1f else 2f })!!
        // "w" is the wide shot: its 2× center looks like "z", the zoomed one.
        val looks = PhotoLooks(
            mapOf(
                "w" to PhotoFingerprint(longArrayOf(0L, 0x5555_5555_5555_5555L), listOf(wide, center)),
                "z" to PhotoFingerprint(longArrayOf(-1L, 0x3333_3333_3333_3333L), listOf(center, unrelated)),
                "u" to PhotoFingerprint(longArrayOf(0x0F0F_0F0F_0F0F_0F0FL), listOf(unrelated)),
            ),
            0.9f,
        )
        assertTrue(looks.similar(photo("w", 0), photo("z", 1)))
        assertTrue(looks.similar(photo("z", 1), photo("w", 0)))
        // Two zoomed centers are never compared with each other: "z"'s center is "u"'s whole frame, which counts...
        assertTrue(looks.similar(photo("z", 1), photo("u", 2)))
        // ...but "w" and "u" share nothing: their wholes differ and neither's center is the other's whole.
        assertTrue(!looks.similar(photo("w", 0), photo("u", 2)))
    }
}

class EmbeddingTest {
    @Test
    fun cosineSurvivesQuantizing() {
        val a = FloatArray(512) { ((it * 37) % 101 - 50) / 50f }
        val b = FloatArray(512) { a[it] + ((it * 13) % 7 - 3) / 20f }
        var dot = 0.0
        var na = 0.0
        var nb = 0.0
        for (i in a.indices) {
            dot += a[i] * b[i]; na += a[i] * a[i]; nb += b[i] * b[i]
        }
        val exact = dot / kotlin.math.sqrt(na * nb)
        assertTrue(abs(cosine(quantize(a)!!, quantize(b)!!) - exact) < 0.01)
        assertEquals(1f, cosine(quantize(a)!!, quantize(a)!!), 0.0001f)
    }

    @Test
    fun emptyOrMismatchedEmbeddingsAreNotSimilar() {
        assertNull(quantize(FloatArray(4)))
        assertEquals(0f, cosine(byteArrayOf(1, 2), byteArrayOf(1, 2, 3)))
    }
}

private class FakeAnalyzer(override val modelId: String = "fake-1") : PhotoAnalyzer {
    override val sameScene = 0.9f
    val analyzed = mutableListOf<String>()

    override suspend fun analyze(item: MediaItem): PhotoAnalysis? {
        analyzed += item.id
        if (item.id.startsWith("broken")) return null
        return if (item.id.startsWith("w")) {
            PhotoAnalysis(waves, listOf(floatArrayOf(-3f, 2f, -1f)))
        } else {
            PhotoAnalysis(diagonal, listOf(floatArrayOf(1f, 2f, 3f)))
        }
    }
}

/** An analyzer whose model is downloaded on first use ([prepare]); dHashes only until then. */
private class ModelLaterAnalyzer : PhotoAnalyzer {
    override val modelId = "fake-later"
    override val sameScene = 0.9f
    var downloadWorks = false
    var prepared = 0
    override var embeds = false
    val analyzed = mutableListOf<String>()

    override suspend fun prepare(): Boolean {
        prepared++
        embeds = downloadWorks
        return embeds
    }

    override suspend fun analyze(item: MediaItem): PhotoAnalysis {
        analyzed += item.id
        val gray = if (item.id.startsWith("w")) waves else diagonal
        return if (embeds) PhotoAnalysis(gray, listOf(floatArrayOf(1f, 2f, 3f))) else PhotoAnalysis(gray, emptyList(), withoutModel = true)
    }
}

private val CACHE_DIR = "/cache/similarity".toPath()

class PhotoSimilarityTest {
    private fun TestScope.similarity(fileSystem: FakeFileSystem, analyzer: PhotoAnalyzer) =
        PhotoSimilarity(analyzer, FingerprintCache(fileSystem, CACHE_DIR), StandardTestDispatcher(testScheduler))

    @Test
    fun eachPhotoIsReadOnceAndCached() = runTest {
        val fileSystem = FakeFileSystem()
        val analyzer = FakeAnalyzer()
        val similarity = similarity(fileSystem, analyzer)
        similarity.request(listOf(photo("a", 0), photo("w", 1), photo("broken", 2)))
        advanceUntilIdle()
        similarity.request(listOf(photo("a", 0), photo("w", 1), photo("broken", 2)))
        advanceUntilIdle()
        assertEquals(listOf("a", "w", "broken"), analyzer.analyzed)
        assertEquals(setOf("a", "w"), similarity.looks.value.fingerprints.keys)
        assertNotNull(similarity.looks.value.fingerprints["a"]?.embeddings?.singleOrNull())

        val again = FakeAnalyzer()
        val reloaded = similarity(fileSystem, again)
        reloaded.request(listOf(photo("a", 0), photo("w", 1)))
        advanceUntilIdle()
        assertTrue(again.analyzed.isEmpty())
        assertEquals(dHash(diagonal), reloaded.looks.value.fingerprints["a"]?.dHash)
        assertEquals(dHash(waves), reloaded.looks.value.fingerprints["w"]?.dHash)
    }

    @Test
    fun anotherModelStartsOver() = runTest {
        val fileSystem = FakeFileSystem()
        similarity(fileSystem, FakeAnalyzer("fake-1")).request(listOf(photo("a", 0)))
        advanceUntilIdle()
        val newModel = FakeAnalyzer("fake-2")
        similarity(fileSystem, newModel).request(listOf(photo("a", 0)))
        advanceUntilIdle()
        assertEquals(listOf("a"), newModel.analyzed)
    }

    @Test
    fun photosReadBeforeTheModelArrivedAreReadAgain() = runTest {
        val fileSystem = FakeFileSystem()
        val analyzer = ModelLaterAnalyzer()
        val similarity = similarity(fileSystem, analyzer)
        similarity.request(listOf(photo("a", 0), photo("w", 1)))
        advanceUntilIdle()
        // The first download failed: dHashes only, kept as read without the model.
        assertEquals(1, analyzer.prepared)
        assertEquals(listOf("a", "w"), analyzer.analyzed)
        assertTrue(similarity.looks.value.fingerprints.values.all { it.embeddings.isEmpty() && it.withoutModel })

        // Asked again soon after: no new download, and nothing to read.
        similarity.request(listOf(photo("a", 0), photo("w", 1)))
        advanceUntilIdle()
        assertEquals(1, analyzer.prepared)
        assertEquals(listOf("a", "w"), analyzer.analyzed)

        // Another start, with the download working this time: both are read again, with embeddings.
        analyzer.analyzed.clear()
        analyzer.downloadWorks = true
        val again = similarity(fileSystem, analyzer)
        again.request(listOf(photo("a", 0), photo("w", 1)))
        advanceUntilIdle()
        assertEquals(2, analyzer.prepared)
        assertEquals(listOf("a", "w"), analyzer.analyzed)
        assertTrue(again.looks.value.fingerprints.values.all { it.embeddings.isNotEmpty() && !it.withoutModel })

        // Kept that way: the next start reads nothing.
        analyzer.analyzed.clear()
        similarity(fileSystem, analyzer).request(listOf(photo("a", 0), photo("w", 1)))
        advanceUntilIdle()
        assertTrue(analyzer.analyzed.isEmpty())
    }

    @Test
    fun aDamagedCacheIsIgnored() = runTest {
        val fileSystem = FakeFileSystem()
        fileSystem.createDirectories(CACHE_DIR)
        fileSystem.write(CACHE_DIR / "fingerprints.json") { writeUtf8("{not json") }
        val analyzer = FakeAnalyzer()
        similarity(fileSystem, analyzer).request(listOf(photo("a", 0)))
        advanceUntilIdle()
        assertEquals(listOf("a"), analyzer.analyzed)
    }
}

private class SimilarityTestLibrary(val items: List<MediaItem>) : MediaLibrary {
    override fun access() = PhotoAccess.FULL
    override suspend fun loadItems() = items
}

private class SimilarityTestStore : KeyValueStore {
    private val values = mutableMapOf<String, String>()
    override fun getString(key: String) = values[key]
    override fun putString(key: String, value: String?) {
        if (value == null) values.remove(key) else values[key] = value
    }
}

class SimilaritySettingTest {
    @Test
    fun theThresholdIsSavedAndCanBeReset() = runTest {
        val store = SimilarityTestStore()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val settings = SettingsController(JournalSettings(store), dispatcher, defaultSimilarityPercent = 80)
        assertEquals(80, settings.currentState.similarity.percent)
        assertTrue(settings.currentState.similarity.isDefault)

        settings.setSimilarityPercent(70)
        settings.setSimilarityPercent(120)
        assertEquals(70, settings.currentState.similarity.percent)
        assertEquals(70, SettingsController(JournalSettings(store), dispatcher, defaultSimilarityPercent = 80).currentState.similarity.percent)

        settings.resetSimilarity()
        assertEquals(80, settings.currentState.similarity.percent)
        assertNull(JournalSettings(store).similarityPercent)
    }

    @Test
    fun setsInMomentsFollowTheThreshold() = runTest {
        // Embeddings with cosine 0.8 between "a" and "b"; their dHashes are far apart.
        val analyzer = object : PhotoAnalyzer {
            override val modelId = "fake"
            override val sameScene = 0.9f
            override suspend fun analyze(item: MediaItem) = when (item.id) {
                "a" -> PhotoAnalysis(diagonal, listOf(floatArrayOf(1f, 0f)))
                else -> PhotoAnalysis(waves, listOf(floatArrayOf(0.8f, 0.6f)))
            }
        }
        val items = listOf(photo("a", 0), photo("b", 3_000))
        val dispatcher = StandardTestDispatcher(testScheduler)
        val library = LibraryController(SimilarityTestLibrary(items), JournalSettings(SimilarityTestStore()), dispatcher)
        library.refresh()
        val fileSystem = FakeFileSystem()
        val settings = SettingsController(JournalSettings(SimilarityTestStore()), dispatcher, defaultSimilarityPercent = 85)
        val stories = StoriesController(
            noteStore = FilePhotoNoteStore(fileSystem, "/data/journal".toPath()),
            library = library,
            settings = settings,
            newId = { "set-1" },
            similarity = PhotoSimilarity(analyzer, FingerprintCache(fileSystem, CACHE_DIR), dispatcher),
            dispatcher = dispatcher,
            now = { "2026-10-17T12:00:00Z" },
        )
        advanceUntilIdle()
        fun blocks() = stories.currentState.feed.entries.filterIsInstance<FeedEntry.Section>()
            .flatMap { it.section.moments }.flatMap { it.blocks }.map { block -> block.items.map { it.id } }
        assertEquals(listOf(listOf("a"), listOf("b")), blocks())

        settings.setSimilarityPercent(75)
        advanceUntilIdle()
        assertEquals(listOf(listOf("a", "b")), blocks())
        stories.close()
    }
}
