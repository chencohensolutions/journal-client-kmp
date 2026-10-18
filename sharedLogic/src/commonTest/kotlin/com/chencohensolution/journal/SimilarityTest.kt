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
import com.chencohensolution.journal.stories.FilePhotoNoteStore
import com.chencohensolution.journal.stories.FileStoryStore
import com.chencohensolution.journal.stories.StoriesController
import com.chencohensolution.journal.stories.findSuggestions
import com.chencohensolution.journal.stories.photosToCompare
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

class SimilarSuggestionRulesTest {
    // dHash, embedding: "scene" photos look alike by embedding; "dup" photos by dHash.
    private val scene = quantize(FloatArray(64) { it.toFloat() })
    private val alsoScene = quantize(FloatArray(64) { it + 0.5f })
    private val other = quantize(FloatArray(64) { if (it % 2 == 0) 1f else -1f })
    private val third = quantize(FloatArray(64) { if (it < 32) -1f else 2f })

    private fun looks(vararg pairs: Pair<String, PhotoFingerprint>) = PhotoLooks(pairs.toMap(), 0.9f)
    private fun ids(suggestions: List<com.chencohensolution.journal.stories.BurstSuggestion>) = suggestions.map { s -> s.items.map { it.id } }

    @Test
    fun photosThatLookAlikeAreSuggestedWhenever() {
        // Hours apart, with another scene in between.
        val items = listOf(photo("a", 0), photo("x", 600), photo("b", 7_200), photo("c", 80_000))
        val looks = looks(
            "a" to fingerprint(0L, scene),
            "x" to fingerprint(-1L, other),
            "b" to fingerprint(0x5555_5555_5555_5555L, alsoScene),
            "c" to fingerprint(0x3333_3333_3333_3333L, scene),
        )
        val suggestions = findSuggestions(items, emptySet(), looks)
        assertEquals(listOf(listOf("a", "b", "c")), ids(suggestions))
        assertTrue(suggestions.single().byLooks)
    }

    @Test
    fun looksSplitWhatTimeWouldJoin() {
        // One second apart, two different scenes.
        val items = listOf(photo("a", 0), photo("x", 1), photo("b", 2), photo("y", 3))
        val looks = looks(
            "a" to fingerprint(0L, scene),
            "x" to fingerprint(-1L, other),
            "b" to fingerprint(0x5555_5555_5555_5555L, alsoScene),
            "y" to fingerprint(0x0F0F_0F0F_0F0F_0F0FL, other),
        )
        assertEquals(listOf(listOf("a", "b"), listOf("x", "y")), ids(findSuggestions(items, emptySet(), looks)))
        assertEquals(listOf(listOf("a", "x", "b", "y")), ids(findSuggestions(items, emptySet())))
    }

    @Test
    fun aPhotoJoinsOnlyIfItLooksLikeMostOfTheSet() {
        // "b" looks like "a" by dHash, "c" like "b" only: a chain, not a set.
        val items = listOf(photo("a", 0), photo("b", 100), photo("c", 200))
        val looks = looks(
            "a" to fingerprint(0L, scene),
            "b" to fingerprint(0xFFL, other),
            "c" to fingerprint(0xFFFFL, third),
        )
        assertEquals(listOf(listOf("a", "b")), ids(findSuggestions(items, emptySet(), looks)))
    }

    @Test
    fun photosNotReadYetGoByTime() {
        val items = listOf(photo("a", 0), photo("b", 3), photo("c", 6), photo("d", 500), photo("e", 900))
        val looks = looks("d" to fingerprint(0L, scene), "e" to fingerprint(-1L, alsoScene))
        val suggestions = findSuggestions(items, emptySet(), looks)
        assertEquals(listOf(listOf("a", "b", "c"), listOf("d", "e")), ids(suggestions))
        assertEquals(listOf(false, true), suggestions.map { it.byLooks })
    }

    @Test
    fun aSetWithADismissedPhotoStaysDismissed() {
        val items = listOf(photo("a", 0), photo("b", 5_000))
        val looks = looks("a" to fingerprint(0L, scene), "b" to fingerprint(0L, scene))
        assertTrue(findSuggestions(items, setOf("b"), looks).isEmpty())
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
    fun aDamagedCacheIsIgnored() = runTest {
        val fileSystem = FakeFileSystem()
        fileSystem.createDirectories(CACHE_DIR)
        fileSystem.write(CACHE_DIR / "fingerprints.json") { writeUtf8("{not json") }
        val analyzer = FakeAnalyzer()
        similarity(fileSystem, analyzer).request(listOf(photo("a", 0)))
        advanceUntilIdle()
        assertEquals(listOf("a"), analyzer.analyzed)
    }

    @Test
    fun theStoryChangedLastIsReadFirst() = runTest {
        fun story(id: String, updatedAt: String, vararg items: MediaItem) = com.chencohensolution.journal.stories.StoryView(
            com.chencohensolution.journal.stories.Story(
                id, "", items.map { com.chencohensolution.journal.stories.StoryMember(it.id, it.fileName, it.takenAtMillis) }, "", updatedAt,
            ),
            items.toList(),
        )
        val old = story("old", "2026-10-01T00:00:00Z", photo("a", 0), photo("b", 9_000))
        val new = story("new", "2026-10-18T00:00:00Z", photo("c", 0), photo("v", 1, MediaType.VIDEO), photo("a", 0))
        assertEquals(listOf("c", "a", "b"), photosToCompare(listOf(old, new)).map { it.id })
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

class SimilarSuggestionsTest {
    @Test
    fun storiesSuggestPhotosThatLookAlike() = runTest {
        // Minutes apart: by time alone no burst. "a", "b", "c" look alike; "w1" doesn't.
        val items = listOf(photo("a", 0), photo("b", 90), photo("c", 200), photo("w1", 260))
        val dispatcher = StandardTestDispatcher(testScheduler)
        val library = LibraryController(SimilarityTestLibrary(items), JournalSettings(SimilarityTestStore()), dispatcher)
        library.refresh()
        val fileSystem = FakeFileSystem()
        val stories = StoriesController(
            store = FileStoryStore(fileSystem, "/data/journal".toPath()),
            noteStore = FilePhotoNoteStore(fileSystem, "/data/journal".toPath()),
            library = library,
            settings = SettingsController(JournalSettings(SimilarityTestStore()), dispatcher),
            newId = { "story-1" },
            similarity = PhotoSimilarity(FakeAnalyzer(), FingerprintCache(fileSystem, CACHE_DIR), dispatcher),
            dispatcher = dispatcher,
            now = { "2026-10-17T12:00:00Z" },
        )
        advanceUntilIdle()
        val storyId = assertNotNull(stories.createStoryFromDay("", DAY))
        advanceUntilIdle()

        val suggestion = stories.currentState.story(storyId)!!.suggestions.single()
        assertEquals(listOf("a", "b", "c"), suggestion.items.map { it.id })
        assertEquals(200, suggestion.seconds)

        // Inside a group, its photos that look alike are suggested as a subgroup.
        val groupId = assertNotNull(stories.createGroup(storyId, listOf("a", "b", "c", "w1")))
        val inGroup = stories.currentState.story(storyId)!!.group(groupId)!!.suggestions.single()
        assertEquals(listOf("a", "b", "c"), inGroup.items.map { it.id })
        val subgroupId = assertNotNull(stories.acceptGroupSuggestion(storyId, groupId, inGroup.key))
        val story = stories.currentState.story(storyId)!!
        assertEquals(groupId, story.group(subgroupId)!!.parentId)
        assertTrue(story.group(groupId)!!.suggestions.isEmpty())
        stories.close()
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
    fun suggestionsFollowTheThreshold() = runTest {
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
            store = FileStoryStore(fileSystem, "/data/journal".toPath()),
            noteStore = FilePhotoNoteStore(fileSystem, "/data/journal".toPath()),
            library = library,
            settings = settings,
            newId = { "story-1" },
            similarity = PhotoSimilarity(analyzer, FingerprintCache(fileSystem, CACHE_DIR), dispatcher),
            dispatcher = dispatcher,
            now = { "2026-10-17T12:00:00Z" },
        )
        advanceUntilIdle()
        val storyId = assertNotNull(stories.createStoryFromDay("", DAY))
        advanceUntilIdle()
        assertTrue(stories.currentState.story(storyId)!!.suggestions.isEmpty())

        settings.setSimilarityPercent(75)
        advanceUntilIdle()
        assertEquals(listOf("a", "b"), stories.currentState.story(storyId)!!.suggestions.single().items.map { it.id })
        stories.close()
    }
}
