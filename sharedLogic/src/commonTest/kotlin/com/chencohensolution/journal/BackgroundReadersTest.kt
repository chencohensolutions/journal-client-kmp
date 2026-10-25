package com.chencohensolution.journal

import com.chencohensolution.journal.backup.LedgerEntry
import com.chencohensolution.journal.backup.UploadLedger
import com.chencohensolution.journal.db.LibraryDatabase
import com.chencohensolution.journal.library.mergeWithServer
import com.chencohensolution.journal.library.GeoPoint
import com.chencohensolution.journal.library.LibraryController
import com.chencohensolution.journal.library.LibraryKey
import com.chencohensolution.journal.library.LibraryTable
import com.chencohensolution.journal.library.LocalDay
import com.chencohensolution.journal.library.MediaItem
import com.chencohensolution.journal.library.MediaLibrary
import com.chencohensolution.journal.library.MediaSource
import com.chencohensolution.journal.library.MediaType
import com.chencohensolution.journal.library.PhotoAccess
import com.chencohensolution.journal.library.StoredMediaLibrary
import com.chencohensolution.journal.places.PhotoPlaces
import com.chencohensolution.journal.places.PlaceBatches
import com.chencohensolution.journal.places.PlaceCache
import com.chencohensolution.journal.places.PlaceName
import com.chencohensolution.journal.places.PlaceReader
import com.chencohensolution.journal.places.Places
import com.chencohensolution.journal.places.cellKey
import com.chencohensolution.journal.server.ServerMedia
import com.chencohensolution.journal.server.ServerMediaStore
import com.chencohensolution.journal.settings.DaySource
import com.chencohensolution.journal.settings.JournalSettings
import com.chencohensolution.journal.settings.KeyValueStore
import com.chencohensolution.journal.settings.SettingsController
import com.chencohensolution.journal.settings.ShownSources
import com.chencohensolution.journal.similarity.FingerprintCache
import com.chencohensolution.journal.similarity.GrayImage
import com.chencohensolution.journal.similarity.PhotoAnalysis
import com.chencohensolution.journal.similarity.PhotoAnalyzer
import com.chencohensolution.journal.similarity.PhotoSimilarity
import com.chencohensolution.journal.stories.ExploreHome
import com.chencohensolution.journal.stories.FeedPager
import com.chencohensolution.journal.stories.FeedTable
import com.chencohensolution.journal.stories.FilePhotoNoteStore
import com.chencohensolution.journal.stories.StoriesController
import com.chencohensolution.journal.stories.home
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// Step 6b (docs/plans/library-on-disk.md): fingerprints, places and Explore's home read from the database a batch at a
// time give what they gave from the whole list.

private class ReadersTestLibrary(var items: List<MediaItem>) : MediaLibrary {
    override fun access() = PhotoAccess.FULL
    override suspend fun loadItems() = items
}

/** Where the made-up photos are: two towns, a square across the equator and the meridian, and Sydney. */
private val CENTERS = listOf(GeoPoint(32.07, 34.78), GeoPoint(31.77, 35.21), GeoPoint(-0.002, -0.003), GeoPoint(-33.86, 151.2))

private val SOURCES = listOf(MediaSource.CAMERA, MediaSource.WHATSAPP, MediaSource.SCREENSHOTS, MediaSource.folder("Petcube"))

private val BASE_DAY = LocalDay(2026, 10, 1)

private fun near(random: Random): GeoPoint = CENTERS[random.nextInt(CENTERS.size)].let {
    GeoPoint(it.latitude + random.nextDouble(-0.004, 0.004), it.longitude + random.nextDouble(-0.004, 0.004))
}

private fun readersSha(n: Int) = n.toString(16).padStart(64, '0')

/**
 * A made-up library that grows: phone photos and videos (some with a location, many taken at the same hour, some on a
 * day that isn't their time's), server files (some the phone's, some deleted from it under their old ids).
 */
private class GrowingLibrary(seed: Int) {
    val random = Random(seed)
    val database: LibraryDatabase = testLibraryDatabase()
    val table = LibraryTable(database)
    private val platform = ReadersTestLibrary(emptyList())
    private val ledger = UploadLedger(FakeFileSystem(), "/backup".toPath(), database, Dispatchers.Unconfined)
    private val server = mutableListOf<ServerMedia>()
    private var next = 1

    suspend fun grow(phone: Int, serverOnly: Int) {
        val added = (0 until phone).map {
            val n = next++
            val day = BASE_DAY.plusDays(random.nextInt(8).toLong())
            val taken = day.toEpochDay() * 86_400_000 + random.nextInt(6) * 3_600_000L
            MediaItem(
                id = n.toString(), type = if (random.nextInt(6) == 0) MediaType.VIDEO else MediaType.IMAGE, takenAtMillis = taken,
                // Now and then a day that isn't the time's (taken in another time zone).
                day = if (random.nextInt(10) == 0) day.plusDays(1) else day, durationMillis = null, width = 10, height = 10,
                sizeBytes = 1_000L + n, fileName = "IMG_$n.jpg", source = SOURCES[random.nextInt(SOURCES.size)],
                location = if (random.nextInt(3) == 0) near(random) else null, modifiedMillis = 100L + n,
            )
        }
        platform.items = platform.items + added
        // Backup read some: the server has them, with its own location or none.
        added.filter { random.nextInt(3) == 0 }.forEach { item ->
            val n = item.id.toInt()
            ledger.record(LedgerEntry(item.id, item.sizeBytes!!, item.modifiedMillis, readersSha(n), uploaded = true))
            server += serverMedia(readersSha(n), n)
        }
        repeat(serverOnly) {
            val n = 10_000 + next++
            server += serverMedia(readersSha(n), n)
            // Deleted from this phone after backup: the library keeps its old id.
            if (random.nextInt(3) == 0) ledger.record(LedgerEntry("old$n", 5_000L + n, null, readersSha(n), uploaded = true))
        }
        StoredMediaLibrary(platform, database, Dispatchers.Unconfined).loadItems()
        ServerMediaStore(database).replaceAll(server, emptySet(), cursor = null, showPrivate = true)
        table.refresh()
    }

    private fun serverMedia(sha: String, n: Int): ServerMedia {
        val at = if (random.nextInt(4) != 0) near(random) else null
        return ServerMedia(
            sha256 = sha, isVideo = false, fileName = "server_$n.jpg", mimeType = "image/jpeg", size = 5_000L + n,
            takenAt = "2026-10-0${1 + random.nextInt(8)}T1${random.nextInt(6)}:00:00Z", width = 10, height = 10,
            latitude = at?.latitude, longitude = at?.longitude, source = listOf("camera", "whatsapp", "screenshots", "folder:Petcube")[random.nextInt(4)],
            uploadedAt = "2026-10-20T10:00:00Z",
        )
    }

    /** The library as LibraryController lists it: newest day first, newest first, then by id. */
    fun items(): List<MediaItem> = table.all().map { it.item }.sortedWith(
        compareByDescending<MediaItem> { it.day }.thenByDescending { it.takenAtMillis }.thenBy { it.id },
    )

    /** Some of what the sources setting can be: hidden sources, and days some are shown on anyway. */
    fun shown(): ShownSources {
        val hidden = SOURCES.filter { random.nextBoolean() }.toSet()
        val always = (0 until random.nextInt(4)).mapNotNull {
            hidden.randomOrNull(random)?.let { DaySource(BASE_DAY.plusDays(random.nextInt(9).toLong()), it) }
        }.toSet()
        return ShownSources(hidden = hidden, alwaysShown = always)
    }
}

private class ReadersAnalyzer : PhotoAnalyzer {
    override val modelId = "fake-1"
    override val sameScene = 0.9f
    val analyzed = mutableListOf<String>()

    override suspend fun analyze(item: MediaItem): PhotoAnalysis? {
        analyzed += item.id
        if (item.id.hashCode() % 5 == 0) return null
        return PhotoAnalysis(GrayImage(90, 80, IntArray(90 * 80) { (it * item.id.hashCode()) and 255 }), listOf(floatArrayOf(1f, 2f, 3f)))
    }
}

private class ReadersPlaceReader(override val canReadLocations: Boolean = true) : PlaceReader {
    val read = mutableListOf<String>()
    val named = mutableListOf<GeoPoint>()

    override suspend fun location(item: MediaItem): GeoPoint? {
        read += item.id
        val random = Random(item.id.hashCode())
        return when (random.nextInt(4)) {
            0 -> error("only in the cloud")
            1 -> null
            else -> near(random)
        }
    }

    override suspend fun name(point: GeoPoint): PlaceName? {
        named += point
        val cell = cellKey(point)
        return if (cell.hashCode() % 4 == 0) null else PlaceName("area $cell", "town ${cell.hashCode() % 3}", "country")
    }
}

private fun store() = object : KeyValueStore {
    private val values = mutableMapOf<String, String>()
    override fun getString(key: String): String? = values[key]
    override fun putString(key: String, value: String?) {
        if (value == null) values.remove(key) else values[key] = value
    }
}

class BackgroundReadersTest {
    private fun TestScope.similarity(analyzer: PhotoAnalyzer, batch: Int = 500) =
        PhotoSimilarity(analyzer, FingerprintCache(FakeFileSystem(), "/cache/similarity".toPath()), StandardTestDispatcher(testScheduler), batch)

    private fun TestScope.places(reader: PlaceReader, batch: Int = 500) =
        PhotoPlaces(reader, PlaceCache(FakeFileSystem(), "/cache/places".toPath()), StandardTestDispatcher(testScheduler), batch)

    /** The phone's photos Moments shows, fingerprinted from the database, are those of the whole list, in its order. */
    @Test
    fun fingerprintsFromTheDatabaseAreTheWholeListsInItsOrder() = runTest {
        var read = 0
        repeat(20) { seed ->
            val library = GrowingLibrary(seed)
            val shown = library.shown()
            val inMemory = ReadersAnalyzer()
            val fromDatabase = ReadersAnalyzer()
            val whole = similarity(inMemory)
            val fed = similarity(fromDatabase, batch = 1 + seed % 7)
            repeat(3) { round ->
                library.grow(phone = 15 + library.random.nextInt(40), serverOnly = 10)
                whole.request(library.items().filter { it.type == MediaType.IMAGE && shown.shows(it) })
                fed.requestFrom({ after: LibraryKey?, limit: Int -> library.table.deviceImages(after, limit) }) { shown.shows(it) }
                advanceUntilIdle()
                assertEquals(inMemory.analyzed, fromDatabase.analyzed, "seed $seed round $round")
                assertEquals(whole.looks.value.fingerprints.keys, fed.looks.value.fingerprints.keys, "seed $seed round $round")
            }
            read += inMemory.analyzed.size
            whole.close()
            fed.close()
        }
        assertTrue(read > 0)
    }

    /** Photos located and places named from the database are those of the whole list, in its order. */
    @Test
    fun placesFromTheDatabaseAreTheWholeListsInItsOrder() = runTest {
        var named = 0
        repeat(24) { seed ->
            val library = GrowingLibrary(seed + 100)
            val canRead = seed % 6 != 5
            val inMemory = ReadersPlaceReader(canRead)
            val fromDatabase = ReadersPlaceReader(canRead)
            val whole = places(inMemory)
            val fed = places(fromDatabase, batch = 1 + seed % 5)
            val batches = object : PlaceBatches {
                override suspend fun unlocated(after: LibraryKey?, limit: Int) = library.table.deviceUnlocated(after, limit)
                override suspend fun points(after: LibraryKey?, limit: Int) = library.table.points(after, limit)
            }
            repeat(3) { round ->
                library.grow(phone = 10 + library.random.nextInt(40), serverOnly = 5 + library.random.nextInt(20))
                whole.request(library.items())
                fed.requestFrom(batches)
                advanceUntilIdle()
                assertEquals(inMemory.read, fromDatabase.read, "seed $seed round $round: located")
                assertEquals(inMemory.named, fromDatabase.named, "seed $seed round $round: named")
                assertEquals(whole.places.value, fed.places.value, "seed $seed round $round")
            }
            named += inMemory.named.size
            whole.close()
            fed.close()
        }
        assertTrue(named > 0)
    }

    /** Explore's home from the database is the whole list's: the same square, ties and all, and the same average. */
    @Test
    fun homeFromTheDatabaseIsTheWholeLists() = runTest {
        val seen = mutableMapOf("home" to 0, "no home" to 0, "a tie" to 0, "found places count" to 0)
        repeat(60) { seed ->
            val library = GrowingLibrary(seed + 200)
            library.grow(phone = 20 + library.random.nextInt(80), serverOnly = library.random.nextInt(60))
            val items = library.items()
            repeat(4) { round ->
                val shown = library.shown()
                // Places found for photos without a location of their own, for some with one (theirs goes first), and
                // for ids the library doesn't have.
                val found = (items.map { it.id } + listOf("gone1", "gone2")).filter { library.random.nextInt(3) == 0 }.associateWith { near(library.random) }
                val places = Places(found, emptyMap())
                val shownItems = items.filter(shown::shows)
                val expected = home(shownItems, places)
                assertEquals(expected, home(library.table, found, shown), "seed $seed round $round")
                if (expected == null) seen["no home"] = seen.getValue("no home") + 1 else seen["home"] = seen.getValue("home") + 1
                val squares = shownItems.mapNotNull { places.location(it) }.groupingBy { (it.latitude * 10).toInt() to (it.longitude * 10).toInt() }.eachCount()
                if (expected != null && squares.values.count { it == squares.values.max() } > 1) seen["a tie"] = seen.getValue("a tie") + 1
                if (shownItems.any { it.location == null && it.id in found }) seen["found places count"] = seen.getValue("found places count") + 1
            }
        }
        seen.forEach { (case, count) -> assertTrue(count > 0, "no case of: $case") }
    }

    /**
     * A StoriesController with Moments from the database feeds its readers from it: the photos fingerprinted and located
     * are those of the whole list, in its order, again when photos come; and its home is the whole list's.
     */
    @Test
    fun storiesFeedTheReadersFromTheDatabase() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val random = Random(7)
        val first = LocalDay(2026, 1, 1)
        fun photo(n: Int): MediaItem {
            val day = first.plusDays((n / 5).toLong())
            return MediaItem(
                "$n", if (n % 9 == 0) MediaType.VIDEO else MediaType.IMAGE, day.toEpochDay() * 86_400_000 + (n % 3) * 60_000L, day, null, 10, 10,
                100L + n, "IMG_$n.jpg", modifiedMillis = 1, source = SOURCES[n % SOURCES.size], location = if (n % 4 == 0) near(random) else null,
            )
        }
        val platform = ReadersTestLibrary((0 until 120).map(::photo))
        val database = testLibraryDatabase()
        val library = LibraryController(
            StoredMediaLibrary(platform, database, dispatcher), JournalSettings(store()), dispatcher, table = LibraryTable(database),
        )
        val settings = SettingsController(JournalSettings(store()), dispatcher)
        val analyzer = ReadersAnalyzer()
        val reader = ReadersPlaceReader()
        val stories = StoriesController(
            noteStore = FilePhotoNoteStore(FakeFileSystem(), "/data/journal".toPath()),
            library = library,
            settings = settings,
            newId = { "set" },
            similarity = PhotoSimilarity(analyzer, FingerprintCache(FakeFileSystem(), "/cache/similarity".toPath()), dispatcher, batchSize = 7),
            places = PhotoPlaces(reader, PlaceCache(FakeFileSystem(), "/cache/places".toPath()), dispatcher, batchSize = 5),
            dispatcher = dispatcher,
            feed = FeedPager(FeedTable(database), LibraryTable(database), dispatcher, pageSize = { 3 }),
            shownRow = library::shownRow,
        )
        library.refresh()
        advanceUntilIdle()

        val referenceAnalyzer = ReadersAnalyzer()
        val referenceReader = ReadersPlaceReader()
        val reference = similarity(referenceAnalyzer)
        val referencePlaces = places(referenceReader)
        fun check(round: Int) {
            // The library merged in memory, in the order the database's rows are shown (the controller keeps no list: step 6f).
            val items = shownDays(mergeWithServer(platform.items, emptyMap(), emptyList())).flatMap { it.items }
            val shown = settings.state.value.shownSources
            reference.request(items.filter { it.type == MediaType.IMAGE && shown.shows(it) })
            referencePlaces.request(items)
            advanceUntilIdle()
            assertEquals(referenceAnalyzer.analyzed, analyzer.analyzed, "round $round: fingerprinted")
            assertEquals(referenceReader.read, reader.read, "round $round: located")
            assertEquals(referenceReader.named, reader.named, "round $round: named")
            val state = stories.currentState
            assertEquals(referencePlaces.places.value, state.places, "round $round: places")
            assertEquals(ExploreHome(home(items.filter(shown::shows), state.places)), state.home, "round $round: home")
        }
        check(0)
        assertTrue(analyzer.analyzed.isNotEmpty() && reader.read.isNotEmpty())

        // New photos on the phone: read from the newest again, only what is new.
        platform.items = platform.items + (120 until 160).map(::photo)
        library.refresh()
        advanceUntilIdle()
        check(1)
        assertTrue(analyzer.analyzed.any { it.toInt() >= 120 })
        reference.close()
        referencePlaces.close()
        stories.close()
        settings.close()
        library.close()
    }
}
