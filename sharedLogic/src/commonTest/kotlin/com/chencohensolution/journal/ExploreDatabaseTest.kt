package com.chencohensolution.journal

import com.chencohensolution.journal.backup.LedgerEntry
import com.chencohensolution.journal.backup.UploadLedger
import com.chencohensolution.journal.db.LibraryDatabase
import com.chencohensolution.journal.library.GeoPoint
import com.chencohensolution.journal.library.LibraryController
import com.chencohensolution.journal.library.LibraryRow
import com.chencohensolution.journal.library.LibraryTable
import com.chencohensolution.journal.library.LocalDay
import com.chencohensolution.journal.library.MediaItem
import com.chencohensolution.journal.library.MediaLibrary
import com.chencohensolution.journal.library.MediaSource
import com.chencohensolution.journal.library.MediaType
import com.chencohensolution.journal.library.PhotoAccess
import com.chencohensolution.journal.library.StoredMediaLibrary
import com.chencohensolution.journal.library.shown as shownItem
import com.chencohensolution.journal.places.PhotoPlaces
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
import com.chencohensolution.journal.stories.CountrySummary
import com.chencohensolution.journal.stories.ExploreHome
import com.chencohensolution.journal.stories.ExploreScope
import com.chencohensolution.journal.stories.ExploreSummary
import com.chencohensolution.journal.stories.FeedPager
import com.chencohensolution.journal.stories.FeedTable
import com.chencohensolution.journal.stories.FilePhotoNoteStore
import com.chencohensolution.journal.stories.GeoArea
import com.chencohensolution.journal.stories.MapArea
import com.chencohensolution.journal.stories.PlaceSummary
import com.chencohensolution.journal.stories.StoriesController
import com.chencohensolution.journal.stories.YearSummary
import com.chencohensolution.journal.stories.exploreOf
import com.chencohensolution.journal.stories.mapAreaOf
import com.chencohensolution.journal.stories.mapOverviewOf
import com.chencohensolution.journal.stories.scopeItemsOf
import com.chencohensolution.journal.stories.townArea
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// Step 6c (docs/plans/library-on-disk.md): Explore's lists, a town's, a year's or the map's photos, the map's numbers and
// a town's box for search, worked out from the database, equal those worked out from the whole list.

private class ExploreTestLibrary(var items: List<MediaItem>) : MediaLibrary {
    override fun access() = PhotoAccess.FULL
    override suspend fun loadItems() = items
}

/** Where the made-up photos are: two towns, a square across the equator and the meridian, Sydney, and Fiji (across the date line). */
private val EXPLORE_CENTERS = listOf(
    GeoPoint(32.07, 34.78), GeoPoint(31.77, 35.21), GeoPoint(-0.002, -0.003), GeoPoint(-33.86, 151.2), GeoPoint(-17.7, 179.998),
)

private val EXPLORE_SOURCES = listOf(MediaSource.CAMERA, MediaSource.WHATSAPP, MediaSource.SCREENSHOTS, MediaSource.folder("Petcube"))

/** How the server spells the sources: also as settings kept them before folders ("CAMERA"). */
private val SERVER_SOURCES = listOf("camera", "CAMERA", "whatsapp", "screenshots", "folder:Petcube")

private val FIRST_DAY = LocalDay(2019, 12, 20)

private fun exploreSha(n: Int) = n.toString(16).padStart(64, '0')

/**
 * A made-up library over several years: phone photos and videos (some located, some at the same time, some on a day
 * that isn't their time's), some backed up with the server's score, server-only files, and points on the edges of
 * place squares; with place names, places found for photos, this phone's scores, and the sources shown, all random.
 */
private class ExploreLibrary(seed: Int) {
    val random = Random(seed)
    val database: LibraryDatabase = testLibraryDatabase()
    val table = LibraryTable(database)
    private val platform = ExploreTestLibrary(emptyList())
    private val ledger = UploadLedger(FakeFileSystem(), "/backup".toPath(), database, Dispatchers.Unconfined)
    private val server = mutableListOf<ServerMedia>()
    private var next = 1

    fun near(): GeoPoint {
        // Now and then right on the edge of a place square, south and west of zero too.
        if (random.nextInt(12) == 0) return GeoPoint(random.nextInt(-3, 3) * 0.005, random.nextInt(-3, 3) * 0.005)
        val center = EXPLORE_CENTERS[random.nextInt(EXPLORE_CENTERS.size)]
        return GeoPoint(center.latitude + random.nextDouble(-0.012, 0.012), (center.longitude + random.nextDouble(-0.012, 0.012)).let { if (it > 180) it - 360 else it })
    }

    private fun day(): LocalDay = FIRST_DAY.plusDays(random.nextInt(0, 7 * 365).toLong().let { if (random.nextInt(4) == 0) it % 40 else it })

    suspend fun grow(phone: Int, serverOnly: Int) {
        val added = (0 until phone).map {
            val n = next++
            val day = day()
            val taken = day.toEpochDay() * 86_400_000 + random.nextInt(4) * 3_600_000L
            MediaItem(
                id = n.toString(), type = if (random.nextInt(6) == 0) MediaType.VIDEO else MediaType.IMAGE, takenAtMillis = taken,
                day = if (random.nextInt(10) == 0) day.plusDays(1) else day, durationMillis = null, width = 10, height = 10,
                sizeBytes = 1_000L + n, fileName = "IMG_$n.jpg", source = EXPLORE_SOURCES[random.nextInt(EXPLORE_SOURCES.size)],
                location = if (random.nextInt(3) != 0) near() else null, modifiedMillis = 100L + n,
            )
        }
        platform.items = platform.items + added
        // Backup read some: the server has them, with its score or none.
        added.filter { random.nextInt(2) == 0 }.forEach { item ->
            val n = item.id.toInt()
            ledger.record(LedgerEntry(item.id, item.sizeBytes!!, item.modifiedMillis, exploreSha(n), uploaded = true))
            server += serverMedia(exploreSha(n), n, item.day)
        }
        repeat(serverOnly) {
            val n = 10_000 + next++
            server += serverMedia(exploreSha(n), n, day())
        }
        StoredMediaLibrary(platform, database, Dispatchers.Unconfined).loadItems()
        ServerMediaStore(database).replaceAll(server, emptySet(), cursor = null, showPrivate = true)
        table.refresh()
    }

    private fun serverMedia(sha: String, n: Int, day: LocalDay): ServerMedia {
        val at = if (random.nextInt(4) != 0) near() else null
        return ServerMedia(
            sha256 = sha, isVideo = random.nextInt(8) == 0, fileName = "server_$n.jpg", mimeType = "image/jpeg", size = 5_000L + n,
            takenAt = "${day}T1${random.nextInt(4)}:00:00Z", width = 10, height = 10,
            latitude = at?.latitude, longitude = at?.longitude, source = SERVER_SOURCES[random.nextInt(SERVER_SOURCES.size)],
            uploadedAt = "2026-10-20T10:00:00Z",
            // Ties in score too.
            aesthetic = if (random.nextInt(3) != 0) random.nextInt(8) / 2.0 else null,
        )
    }

    /** This phone's scores: for some of the server's files, some of them other than the server's. */
    fun scores(): Map<String, Float> = server.filter { random.nextInt(3) == 0 }.associate { it.sha256 to random.nextInt(8) / 2f }

    /** The library as LibraryController lists it (newest day first, newest first, then by id), as shown. */
    fun items(scores: Map<String, Float>): List<MediaItem> = table.all().map { shownItem(it, emptyMap(), scores) }.sortedWith(
        compareByDescending<MediaItem> { it.day }.thenByDescending { it.takenAtMillis }.thenBy { it.id },
    )

    /** Names for most place squares the photos are in, a few towns in a few countries (or none), and places found. */
    fun places(items: List<MediaItem>): Places {
        val found = (items.map { it.id } + listOf("gone1", "gone2")).filter { random.nextInt(3) == 0 }.associateWith { near() }
        val cells = (items.mapNotNull { it.location } + found.values).map(::cellKey).distinct()
        val towns = listOf("Tel Aviv" to "Israel", "Jerusalem" to "Israel", "Haifa" to "Israel", "Sydney" to "Australia", "Suva" to "Fiji", "Nowhere" to null)
        val names = cells.filter { random.nextInt(8) != 0 }.associateWith { cell ->
            val (town, country) = towns[random.nextInt(towns.size)]
            PlaceName("area $cell", town, country.takeIf { random.nextInt(10) != 0 })
        }
        return Places(found, names)
    }

    /** Some of what the sources setting can be: hidden sources, and days some are shown on anyway. */
    fun shown(items: List<MediaItem>): ShownSources {
        val hidden = EXPLORE_SOURCES.filter { random.nextInt(3) == 0 }.toSet()
        val always = items.filter { it.source in hidden && random.nextInt(5) == 0 }.map { DaySource(it.day, it.source) }.toSet()
        return ShownSources(hidden = hidden, alwaysShown = always)
    }
}

/** What the screens show of a town (its items aside, which the database's leaves out). */
private fun PlaceSummary.facts() = listOf(town, home, country, count, cover, firstYear, lastYear, years)

private fun CountrySummary.facts() = listOf(country, home, photoCount, firstYear, lastYear, covers, places.map { it.facts() })

private fun YearSummary.facts() = listOf(year, count, months, towns, covers, hidden)

private fun ExploreSummary.facts() = listOf(countries.map { it.facts() }, years.map { it.facts() }, anyLocated, counts)

/** [area]'s squares as their counts and ids, and their middles within rounding. */
private fun assertSameArea(expected: MapArea, actual: MapArea, message: String) {
    assertEquals(expected.inView, actual.inView, "$message: in view")
    assertEquals(expected.cells.map { it.count to it.id }, actual.cells.map { it.count to it.id }, "$message: squares")
    expected.cells.zip(actual.cells).forEach { (a, b) ->
        assertTrue(abs(a.point.latitude - b.point.latitude) < 1e-9 && abs(a.point.longitude - b.point.longitude) < 1e-9, "$message: middle")
    }
}

private fun exploreStore() = object : KeyValueStore {
    private val values = mutableMapOf<String, String>()
    override fun getString(key: String): String? = values[key]
    override fun putString(key: String, value: String?) {
        if (value == null) values.remove(key) else values[key] = value
    }
}

class ExploreDatabaseTest {
    /** Explore's lists from the database are the whole list's: towns, home, countries and their covers, years. */
    @Test
    fun exploreFromTheDatabaseIsTheWholeLists() = runTest {
        val seen = mutableMapOf("home town" to 0, "spread covers" to 0, "only hidden year" to 0, "scored cover" to 0, "no country" to 0)
        repeat(40) { seed ->
            val library = ExploreLibrary(seed)
            library.grow(phone = 30 + library.random.nextInt(150), serverOnly = library.random.nextInt(80))
            repeat(3) { round ->
                val scores = library.scores()
                val items = library.items(scores)
                val places = library.places(items)
                val shown = library.shown(items)
                // Home as the database worked it out (step 6b), none located enough, or not known yet.
                val home = when (round) {
                    0 -> null
                    1 -> ExploreHome(null)
                    else -> ExploreHome(items.firstNotNullOfOrNull { places.location(it) })
                }
                val expected = exploreOf(items, places, shown, home)
                val actual = exploreOf(
                    library.table, places, shown, home,
                    scoreOf = { sha, aesthetic -> sha?.let(scores::get) ?: aesthetic },
                    shownRow = { row: LibraryRow -> shownItem(row, emptyMap(), scores) },
                )
                assertEquals(expected.facts(), actual.facts(), "seed $seed round $round")
                val countries = expected.countries
                if (countries.any { it.home }) seen["home town"] = seen.getValue("home town") + 1
                if (countries.any { it.places.size < 4 && it.covers.size > it.places.size }) seen["spread covers"] = seen.getValue("spread covers") + 1
                if (expected.years.any { it.count == 0 }) seen["only hidden year"] = seen.getValue("only hidden year") + 1
                if (countries.any { c -> c.places.any { it.cover.aesthetic != null } }) seen["scored cover"] = seen.getValue("scored cover") + 1
                if (countries.any { it.country == null }) seen["no country"] = seen.getValue("no country") + 1
            }
        }
        seen.forEach { (case, count) -> assertTrue(count > 0, "no case of: $case") }
    }

    /** A town's, a year's, a month's and the map's photos read from the database are the whole list's. */
    @Test
    fun pagesFromTheDatabaseAreTheWholeLists() = runTest {
        var nonEmpty = 0
        repeat(30) { seed ->
            val library = ExploreLibrary(seed + 100)
            library.grow(phone = 30 + library.random.nextInt(120), serverOnly = library.random.nextInt(60))
            val items = library.items(emptyMap())
            val places = library.places(items)
            val random = library.random
            val towns = places.names.values.mapNotNull { it.town }.distinct() + "Atlantis"
            repeat(12) { round ->
                val year = 2019 + random.nextInt(8)
                val center = library.near()
                val size = listOf(0.003, 0.02, 1.0, 40.0)[random.nextInt(4)]
                // Now and then across the date line.
                val area = GeoArea(center.latitude - size, center.longitude - size, center.latitude + size, (center.longitude + size).let { if (it > 180) it - 360 else it })
                val scope = when (round % 6) {
                    0 -> ExploreScope(town = towns.random(random))
                    1 -> ExploreScope(town = towns.random(random), year = year)
                    2 -> ExploreScope(year = year)
                    3 -> ExploreScope(year = year, month = 1 + random.nextInt(12))
                    4 -> ExploreScope(fromYear = year - random.nextInt(3), toYear = year, area = area)
                    else -> ExploreScope(area = area)
                }
                val expected = items.filter { scope.matches(it, places) }
                assertEquals(expected, scopeItemsOf(library.table, places, scope) { shownItem(it, emptyMap(), emptyMap()) }, "seed $seed: $scope")
                if (expected.isNotEmpty()) nonEmpty++
            }
        }
        assertTrue(nonEmpty > 50)
    }

    /** A town's box for search, from the database's place squares, is the whole list's. */
    @Test
    fun aTownsBoxFromTheDatabaseIsTheWholeLists() = runTest {
        repeat(30) { seed ->
            val library = ExploreLibrary(seed + 200)
            library.grow(phone = 30 + library.random.nextInt(120), serverOnly = library.random.nextInt(60))
            val items = library.items(emptyMap())
            val places = library.places(items)
            (places.names.values.mapNotNull { it.town }.distinct() + "Atlantis").forEach { town ->
                val points = items.mapNotNull { item -> places.location(item)?.takeIf { places.name(it)?.town == town } }
                val expected = if (points.isEmpty()) null else GeoArea(points.minOf { it.latitude }, points.minOf { it.longitude }, points.maxOf { it.latitude }, points.maxOf { it.longitude })
                assertEquals(expected, townArea(library.table, places, town), "seed $seed: $town")
            }
        }
    }

    /** The map's years, where it opens and its squares and count in view, from the database, are the whole list's. */
    @Test
    fun theMapFromTheDatabaseIsTheWholeLists() = runTest {
        var squares = 0
        repeat(30) { seed ->
            val library = ExploreLibrary(seed + 300)
            library.grow(phone = 30 + library.random.nextInt(150), serverOnly = library.random.nextInt(80))
            val items = library.items(emptyMap())
            val places = library.places(items)
            val shown = library.shown(items)
            val random = library.random
            val memory = mapOverviewOf(items, places, shown)
            val database = mapOverviewOf(library.table, places, shown)
            assertEquals(memory.years, database.years, "seed $seed: years")
            assertEquals(memory.counts, database.counts, "seed $seed: counts")
            val years = memory.years ?: return@repeat
            repeat(10) { round ->
                val from = years.first + random.nextInt(years.last - years.first + 1)
                val to = from + random.nextInt(years.last - from + 1)
                assertEquals(memory.bounds(from, to), database.bounds(from, to), "seed $seed round $round: bounds")
                val center = library.near()
                val size = listOf(0.002, 0.03, 2.0, 70.0)[random.nextInt(4)]
                val visible = GeoArea(
                    (center.latitude - size).coerceAtLeast(-90.0), center.longitude - size,
                    (center.latitude + size).coerceAtMost(90.0), (center.longitude + size).let { if (it > 180) it - 360 else it },
                )
                val zoom = random.nextDouble(0.0, 20.0)
                val expected = mapAreaOf(memory, visible, zoom, from, to)
                assertSameArea(expected, mapAreaOf(library.table, database, shown, visible, zoom, from, to), "seed $seed round $round")
                squares += expected.cells.size
            }
        }
        assertTrue(squares > 100)
    }

    /**
     * StoriesController with Moments from the database works Explore and the map out from it, the same as from the
     * whole list, and again once photos come (not before: the same answer is kept meanwhile).
     */
    @Test
    fun storiesWorkExploreOutFromTheDatabase() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val random = Random(11)
        fun photo(n: Int): MediaItem {
            val day = FIRST_DAY.plusDays((n * 37L) % 900)
            return MediaItem(
                "$n", if (n % 9 == 0) MediaType.VIDEO else MediaType.IMAGE, day.toEpochDay() * 86_400_000 + (n % 3) * 60_000L, day, null, 10, 10,
                100L + n, "IMG_$n.jpg", modifiedMillis = 1, source = EXPLORE_SOURCES[n % EXPLORE_SOURCES.size],
                location = if (n % 3 != 0) EXPLORE_CENTERS[n % 2].let { GeoPoint(it.latitude + random.nextDouble(-0.01, 0.01), it.longitude) } else null,
            )
        }
        val platform = ExploreTestLibrary((0 until 120).map(::photo))
        val database = testLibraryDatabase()
        val library = LibraryController(
            StoredMediaLibrary(platform, database, dispatcher), JournalSettings(exploreStore()), dispatcher, table = LibraryTable(database),
        )
        val settings = SettingsController(JournalSettings(exploreStore()), dispatcher)
        val reader = object : PlaceReader {
            override val canReadLocations = true
            override suspend fun location(item: MediaItem): GeoPoint? = EXPLORE_CENTERS[item.id.toInt() % 2].takeIf { item.id.toInt() % 2 == 0 }
            override suspend fun name(point: GeoPoint): PlaceName = if (point.latitude > 32) PlaceName(null, "Tel Aviv", "Israel") else PlaceName(null, "Jerusalem", "Israel")
        }
        val stories = StoriesController(
            noteStore = FilePhotoNoteStore(FakeFileSystem(), "/data/journal".toPath()),
            library = library,
            settings = settings,
            newId = { "set" },
            places = PhotoPlaces(reader, PlaceCache(FakeFileSystem(), "/cache/places".toPath()), dispatcher, batchSize = 7),
            dispatcher = dispatcher,
            feed = FeedPager(FeedTable(database), LibraryTable(database), dispatcher, pageSize = { 3 }),
            shownRow = library::shownRow,
        )
        library.refresh()
        advanceUntilIdle()

        suspend fun check(round: Int) {
            val state = stories.currentState
            val items = library.currentState.days.flatMap { it.items }
            val summary = stories.explore(state)
            assertEquals(exploreOf(items, state.places, state.shownSources, state.home).facts(), summary.facts(), "round $round: explore")
            assertTrue(summary.countries.isNotEmpty(), "round $round: towns named")
            // Asked again with nothing changed: the same lists.
            assertEquals(summary.facts(), stories.explore(state).facts(), "round $round: kept")
            val town = ExploreScope(town = "Tel Aviv")
            assertEquals(items.filter { town.matches(it, state.places) }, stories.scopeItems(town, state), "round $round: a town's photos")
            val overview = stories.mapOverview(state)
            assertEquals(mapOverviewOf(items, state.places, state.shownSources).years, overview.years, "round $round: the map's years")
        }
        check(0)

        // New photos on the phone: worked out again.
        platform.items = platform.items + (120 until 170).map(::photo)
        library.refresh()
        advanceUntilIdle()
        check(1)
        stories.close()
        settings.close()
        library.close()
    }
}
