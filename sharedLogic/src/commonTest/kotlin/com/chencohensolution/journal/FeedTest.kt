package com.chencohensolution.journal

import com.chencohensolution.journal.library.GeoPoint
import com.chencohensolution.journal.library.LibraryController
import com.chencohensolution.journal.library.LocalDay
import com.chencohensolution.journal.library.MediaItem
import com.chencohensolution.journal.library.MediaLibrary
import com.chencohensolution.journal.library.MediaSource
import com.chencohensolution.journal.library.MediaType
import com.chencohensolution.journal.library.PhotoAccess
import com.chencohensolution.journal.library.sourceOfFolder
import com.chencohensolution.journal.places.PhotoPlaces
import com.chencohensolution.journal.places.PlaceCache
import com.chencohensolution.journal.places.PlaceName
import com.chencohensolution.journal.places.PlaceReader
import com.chencohensolution.journal.places.Places
import com.chencohensolution.journal.places.cellKey
import com.chencohensolution.journal.settings.JournalSettings
import com.chencohensolution.journal.settings.KeyValueStore
import com.chencohensolution.journal.settings.SettingsController
import com.chencohensolution.journal.settings.TimelineGrouping
import com.chencohensolution.journal.similarity.PhotoFingerprint
import com.chencohensolution.journal.similarity.PhotoLooks
import com.chencohensolution.journal.stories.FeedBlock
import com.chencohensolution.journal.stories.FeedEntry
import com.chencohensolution.journal.stories.FilePhotoNoteStore
import com.chencohensolution.journal.stories.FileStoryStore
import com.chencohensolution.journal.stories.StoriesController
import com.chencohensolution.journal.stories.StoryTile
import com.chencohensolution.journal.stories.MomentsFeed
import com.chencohensolution.journal.stories.buildFeed
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private const val MINUTE = 60_000L
private const val HOUR = 60 * MINUTE

private val FEED_DAY = LocalDay(2026, 10, 8)

/** A photo taken [minute] minutes into [day] (UTC, so the test doesn't depend on the time zone). */
private fun pic(
    id: String,
    minute: Int,
    source: MediaSource = MediaSource.CAMERA,
    at: GeoPoint? = null,
    day: LocalDay = FEED_DAY,
    type: MediaType = MediaType.IMAGE,
    burst: String? = null,
    second: Int = 0,
) = MediaItem(
    id, type, day.toEpochDay() * 86_400_000 + minute * MINUTE + second * 1000L, day, null, 4, 3, 100, "$id.jpg",
    burstId = burst, source = source, location = at,
)

/** Newest first, as the library lists them. */
private fun newestFirst(vararg items: MediaItem) = items.sortedByDescending { it.takenAtMillis }

private val TEL_AVIV = GeoPoint(32.0853, 34.7818)
private val JAFFA = GeoPoint(32.0504, 34.7522)
private val HAIFA = GeoPoint(32.7940, 34.9896)

private fun feed(vararg items: MediaItem, places: Places = Places.NONE, looks: PhotoLooks = PhotoLooks.NONE, shown: Set<MediaSource> = MediaSource.entries.toSet()) =
    buildFeed(newestFirst(*items), shown, TimelineGrouping(), places, looks)

private fun MomentsFeed.sections() = entries.filterIsInstance<FeedEntry.Section>().map { it.section }

/** Each moment's photo ids, newest moment first. */
private fun MomentsFeed.moments() = sections().flatMap { section -> section.moments.map { moment -> moment.items.map { it.id } } }

class FeedTest {
    @Test
    fun foldersTellTheSource() {
        assertEquals(MediaSource.CAMERA, sourceOfFolder("DCIM/Camera/"))
        assertEquals(MediaSource.SCREENSHOTS, sourceOfFolder("DCIM/Screenshots/"))
        assertEquals(MediaSource.SCREENSHOTS, sourceOfFolder("Pictures/Screenshots/"))
        assertEquals(MediaSource.SCREENSHOTS, sourceOfFolder("Movies/Screen recordings/"))
        assertEquals(MediaSource.WHATSAPP, sourceOfFolder("Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Images/"))
        assertEquals(MediaSource.OTHER, sourceOfFolder("Download/"))
        assertEquals(MediaSource.OTHER, sourceOfFolder(null))
    }

    @Test
    fun cameraMomentsSplitAfterTwoHoursOrAMove() {
        val walk = listOf(
            pic("a", 0, at = TEL_AVIV),
            // 1.2 km on foot, a few minutes later: the same moment.
            pic("b", 10, at = GeoPoint(32.0950, 34.7860)),
            pic("c", 15),
        )
        // Jaffa, 40 minutes later: moved, so a new moment.
        val jaffa = listOf(pic("d", 55, at = JAFFA), pic("e", 60))
        // Back home after more than two hours.
        val evening = listOf(pic("f", 200))
        assertEquals(listOf(listOf("f"), listOf("d", "e"), listOf("a", "b", "c")), feed(*(walk + jaffa + evening).toTypedArray()).moments())
    }

    @Test
    fun rowsReadBackInTimeAllTheWayDown() {
        // Two moments of the day; the near-duplicates stay oldest first inside their set.
        val section = feed(pic("a", 0), pic("b", 30), pic("c", 300), pic("d1", 330, burst = "x"), pic("d2", 330, burst = "x", second = 1)).sections().single()
        assertEquals(listOf("d1", "d2", "c", "b", "a"), section.items.map { it.id })
        assertEquals(listOf(listOf("d1", "d2"), listOf("c")), section.moments.first().shownBlocks.map { block -> block.items.map { it.id } })
    }

    @Test
    fun appPhotosAreOneMomentPerRowAndHiddenSourcesAreLeftOut() {
        val items = arrayOf(
            pic("cam", 0),
            pic("w1", 10, MediaSource.WHATSAPP),
            pic("w2", 400, MediaSource.WHATSAPP),
            pic("s1", 20, MediaSource.SCREENSHOTS),
        )
        assertEquals(listOf(listOf("s1"), listOf("w1", "w2"), listOf("cam")), feed(*items).moments())

        val cameraOnly = feed(*items, shown = setOf(MediaSource.CAMERA))
        assertEquals(listOf(listOf("cam")), cameraOnly.moments())
        assertEquals(mapOf(MediaSource.CAMERA to 1, MediaSource.WHATSAPP to 2, MediaSource.SCREENSHOTS to 1), cameraOnly.counts)
    }

    @Test
    fun nearDuplicatesAreOneBlock() {
        val looks = PhotoLooks(
            mapOf(
                "x1" to PhotoFingerprint(longArrayOf(0L), listOf(byteArrayOf(1, 2, 3))),
                "x2" to PhotoFingerprint(longArrayOf(1L), listOf(byteArrayOf(1, 2, 3))),
                "y" to PhotoFingerprint(longArrayOf(-1L), listOf(byteArrayOf(-3, 2, -1))),
            ),
            0.9f,
        )
        val items = arrayOf(
            pic("b1", 0, burst = "burst"), pic("b2", 1, burst = "burst"),
            // Looked at: alike although minutes apart; not alike although seconds apart.
            pic("x1", 5), pic("x2", 9), pic("y", 10),
            // Not looked at yet: by time.
            pic("t1", 30), pic("t2", 30, second = 5),
            pic("v", 30, type = MediaType.VIDEO, second = 8),
        )
        val blocks = feed(*items, looks = looks).sections().single().moments.single().blocks
        assertEquals(listOf(listOf("b1", "b2"), listOf("x1", "x2"), listOf("y"), listOf("t1", "t2"), listOf("v")), blocks.map { block -> block.items.map { it.id } })
        assertIs<FeedBlock.Single>(blocks[2])

        // A cropped photo leaves its set, and the ones after it don't join it.
        val cropped = buildFeed(newestFirst(*items), MediaSource.entries.toSet(), TimelineGrouping(), looks = looks, alone = setOf("b1"))
            .sections().single().moments.single().blocks
        assertEquals(listOf("b1"), cropped.first().items.map { it.id })
        assertEquals(listOf("b2"), cropped[1].items.map { it.id })
        assertEquals(listOf(listOf("b1", "b2"), listOf("x1", "x2"), listOf("t1", "t2")), feed(*items, looks = looks).similarSets(items.map { it.id }).map { set -> set.map { it.id } })
    }

    @Test
    fun momentsAreNamedByTheirPlaces() {
        val places = Places(
            emptyMap(),
            mapOf(cellKey(TEL_AVIV) to PlaceName("Old North", "Tel Aviv"), cellKey(JAFFA) to PlaceName("Jaffa Port", "Jaffa")),
        )
        val feed = feed(pic("a", 0, at = TEL_AVIV), pic("b", 5, at = TEL_AVIV), pic("c", 60, at = JAFFA), pic("d", 65), places = places)
        val section = feed.sections().single()
        assertEquals(listOf("Jaffa Port", "Old North"), section.moments.map { it.place })
        assertEquals(listOf("Tel Aviv", "Jaffa"), section.places)
        assertEquals("Haifa", PlaceName("79", "Haifa").label)
    }

    @Test
    fun videosFromOnePlaceAreOneSet() {
        val near = GeoPoint(32.0858, 34.7818) // About 55 m from TEL_AVIV.
        val far = GeoPoint(32.0900, 34.7818) // About 500 m: the same moment, another place.
        val items = arrayOf(
            pic("v1", 0, at = TEL_AVIV, type = MediaType.VIDEO),
            pic("p", 2, at = TEL_AVIV),
            pic("v2", 4, at = near, type = MediaType.VIDEO),
            pic("v3", 6, at = far, type = MediaType.VIDEO),
            pic("v4", 8, type = MediaType.VIDEO),
            pic("v5", 10, at = near, type = MediaType.VIDEO),
        )
        val blocks = feed(*items).sections().single().moments.single().blocks.map { block -> block.items.map { it.id } }
        // Where the first video of the place is, with the photo between after it; no location: alone.
        assertEquals(listOf(listOf("v1", "v2", "v5"), listOf("p"), listOf("v3"), listOf("v4")), blocks)
    }

    @Test
    fun photosMergedByHandAreOneSetWhereTheOldestIs() {
        // Two moments (3 hours apart) and a burst; "a" and "late" merged by hand, "b2" taken out of its burst.
        val items = arrayOf(
            pic("a", 0), pic("b1", 10, burst = "x"), pic("b2", 11, burst = "x"), pic("b3", 12, burst = "x"),
            pic("late", 200), pic("later", 210),
        )
        val feed = buildFeed(
            newestFirst(*items), MediaSource.entries.toSet(), TimelineGrouping(),
            alone = setOf("b2"),
            sets = mapOf("a" to "s1", "late" to "s1"),
        )
        val blocks = feed.sections().single().moments.map { moment -> moment.blocks.map { block -> block.items.map { it.id } } }
        // Newest moment first: "late" now shows only in the set, with "a", in the older moment.
        assertEquals(listOf(listOf(listOf("later")), listOf(listOf("a", "late"), listOf("b1"), listOf("b2"), listOf("b3"))), blocks)
    }

    @Test
    fun daysAwayFromHomeAreOfferedAsATrip() {
        val home = (1..20).map { pic("home$it", it, at = TEL_AVIV, day = LocalDay(2026, 9, 1)) }
        val trip = listOf(
            pic("t1", 600, at = HAIFA, day = LocalDay(2026, 10, 4)),
            pic("t2", 600, at = HAIFA, day = LocalDay(2026, 10, 5)),
            pic("t3", 600, day = LocalDay(2026, 10, 6)),
        )
        val back = pic("back", 600, at = TEL_AVIV, day = LocalDay(2026, 10, 7))
        val items = home + trip + back
        val places = Places(emptyMap(), mapOf(cellKey(HAIFA) to PlaceName(null, "Haifa")))
        val feed = buildFeed(newestFirst(*items.toTypedArray()), MediaSource.entries.toSet(), TimelineGrouping(), places)

        // A day without locations in the middle of a trip belongs to it; one at its end doesn't.
        val offered = feed.entries.filterIsInstance<FeedEntry.Trip>().single().trip
        assertEquals("trip-2026-10-04", offered.key)
        assertEquals("Haifa", offered.place)
        assertEquals(2, offered.days)
        assertEquals(listOf("t1", "t2"), offered.items.map { it.id })
        // Just above its newest day.
        val index = feed.entries.indexOfFirst { it is FeedEntry.Trip }
        assertEquals(LocalDay(2026, 10, 5), assertIs<FeedEntry.Section>(feed.entries[index + 1]).section.span.newestDay)

        val dismissed = buildFeed(newestFirst(*items.toTypedArray()), MediaSource.entries.toSet(), TimelineGrouping(), places, dismissedTrips = setOf("trip-2026-10-04"))
        assertTrue(dismissed.entries.none { it is FeedEntry.Trip })

        // Once its photos are in a story, it isn't offered again; they still show in the feed.
        val saved = buildFeed(newestFirst(*items.toTypedArray()), MediaSource.entries.toSet(), TimelineGrouping(), places, sorted = setOf("t1", "t2"))
        assertTrue(saved.entries.none { it is FeedEntry.Trip })
        assertTrue(saved.sections().any { section -> section.items.any { it.id == "t1" } })
    }
}

private class FakePlaceReader(var canRead: Boolean = true) : PlaceReader {
    val read = mutableListOf<String>()
    val named = mutableListOf<GeoPoint>()
    var online = true
    override val canReadLocations get() = canRead

    override suspend fun location(item: MediaItem): GeoPoint? {
        read += item.id
        if (item.id.startsWith("cloud")) error("only in the cloud")
        return if (item.id.startsWith("gps")) TEL_AVIV else null
    }

    override suspend fun name(point: GeoPoint): PlaceName? {
        named += point
        return if (online) PlaceName("Old North", "Tel Aviv") else null
    }
}

private val PLACES_DIR = "/cache/places".toPath()

class PhotoPlacesTest {
    @Test
    fun locationsAndNamesAreReadOnceAndCached() = runTest {
        val fileSystem = FakeFileSystem()
        val reader = FakePlaceReader()
        val places = PhotoPlaces(reader, PlaceCache(fileSystem, PLACES_DIR), StandardTestDispatcher(testScheduler))
        val items = listOf(pic("gps1", 0), pic("gps2", 1), pic("none", 2), pic("cloud", 3), pic("video", 4, type = MediaType.VIDEO))
        places.request(items)
        advanceUntilIdle()
        // Videos too: without a place a video joins the moment before it, even after a drive.
        assertEquals(listOf("gps1", "gps2", "none", "cloud", "video"), reader.read)
        assertEquals(TEL_AVIV, places.places.value.location(items[0]))
        // Both photos are in one square: named once.
        assertEquals(1, reader.named.size)
        assertEquals("Old North", places.places.value.name(TEL_AVIV)?.label)

        val again = FakePlaceReader()
        val reloaded = PhotoPlaces(again, PlaceCache(fileSystem, PLACES_DIR), StandardTestDispatcher(testScheduler))
        reloaded.request(items)
        advanceUntilIdle()
        // Only the photo that couldn't be read is tried again.
        assertEquals(listOf("cloud"), again.read)
        assertTrue(again.named.isEmpty())
        assertEquals("Tel Aviv", reloaded.places.value.name(TEL_AVIV)?.town)
    }

    @Test
    fun withoutPermissionNothingIsMarkedAsHavingNoLocation() = runTest {
        val fileSystem = FakeFileSystem()
        val reader = FakePlaceReader(canRead = false)
        reader.online = false
        val places = PhotoPlaces(reader, PlaceCache(fileSystem, PLACES_DIR), StandardTestDispatcher(testScheduler))
        // iOS gives the location with the library; it's named, though offline it isn't saved.
        places.request(listOf(pic("gps1", 0), pic("ios", 1, at = JAFFA)))
        advanceUntilIdle()
        assertTrue(reader.read.isEmpty())
        assertEquals(1, reader.named.size)

        val granted = FakePlaceReader()
        val later = PhotoPlaces(granted, PlaceCache(fileSystem, PLACES_DIR), StandardTestDispatcher(testScheduler))
        later.request(listOf(pic("gps1", 0), pic("ios", 1, at = JAFFA)))
        advanceUntilIdle()
        assertEquals(listOf("gps1"), granted.read)
        assertEquals("Old North", later.places.value.name(JAFFA)?.label)
    }
}

private class FeedTestLibrary(val items: List<MediaItem>) : MediaLibrary {
    override fun access() = PhotoAccess.FULL
    override suspend fun loadItems() = items
}

private class FeedTestStore : KeyValueStore {
    private val values = mutableMapOf<String, String>()
    override fun getString(key: String) = values[key]
    override fun putString(key: String, value: String?) {
        if (value == null) values.remove(key) else values[key] = value
    }
}

class FeedStoryTest {
    @Test
    fun aStoryFromTheFeedKeepsItsNearDuplicatesGrouped() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val items = listOf(pic("a", 0, burst = "x"), pic("b", 1, burst = "x"), pic("c", 30), pic("w", 40, MediaSource.WHATSAPP), pic("s", 50, MediaSource.SCREENSHOTS))
        val library = LibraryController(FeedTestLibrary(items), JournalSettings(FeedTestStore()), dispatcher)
        library.refresh()
        val settings = SettingsController(JournalSettings(FeedTestStore()), dispatcher)
        val fileSystem = FakeFileSystem()
        val stories = StoriesController(
            store = FileStoryStore(fileSystem, "/data/journal".toPath()),
            noteStore = FilePhotoNoteStore(fileSystem, "/data/journal".toPath()),
            library = library,
            settings = settings,
            newId = { "story-1" },
            dispatcher = dispatcher,
            now = { "2026-10-17T12:00:00Z" },
        )
        advanceUntilIdle()
        // Camera and WhatsApp are shown at first; screenshots aren't.
        val section = stories.currentState.feed.sections().single()
        assertEquals(setOf("a", "b", "c", "w"), section.items.map { it.id }.toSet())

        settings.toggleFeedSource(MediaSource.SCREENSHOTS)
        advanceUntilIdle()
        assertEquals(5, stories.currentState.feed.sections().single().items.size)

        // The photo a set was turned to is kept in its note file, so it syncs; turning back to its first forgets it.
        stories.turnSet(listOf("a", "b"), "b")
        assertEquals(true, stories.currentState.note("b")?.front)
        assertEquals(true, FilePhotoNoteStore(fileSystem, "/data/journal".toPath()).loadAll().single { it.deviceId == "b" }.front)
        stories.turnSet(listOf("a", "b"), "a")
        assertEquals(null, stories.currentState.note("b"))
        assertEquals(null, stories.currentState.note("a"))

        val storyId = assertNotNull(stories.createStoryFromFeed("", stories.currentState.feed.sections().single().key))
        advanceUntilIdle()
        val story = assertNotNull(stories.currentState.story(storyId))
        assertEquals(5, story.itemCount)
        assertEquals(listOf("a", "b"), assertIs<StoryTile.Group>(story.tiles.first()).group.items.map { it.id })
        // Merged by hand, then one taken out: kept in the note files, so they survive a restart.
        stories.mergeInFeed(listOf("c", "w"))
        advanceUntilIdle()
        assertTrue(stories.currentState.feed.sections().single().moments.any { moment -> moment.blocks.any { block -> block.items.map { it.id } == listOf("c", "w") } })
        stories.takeOutOfSet("b")
        advanceUntilIdle()
        val blocks = stories.currentState.feed.sections().single().moments.flatMap { it.blocks }.map { block -> block.items.map { it.id } }
        assertTrue(listOf("a") in blocks && listOf("b") in blocks, "$blocks")

        // The feed shows every photo: those in a story stay in it.
        assertEquals(5, stories.currentState.feed.sections().single().items.size)
        assertEquals(0, stories.currentState.unsortedCount)
    }
}
